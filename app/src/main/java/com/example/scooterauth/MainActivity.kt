package com.example.scooterauth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Приложение для самоката Kugoo Kirin (модель kgkrn14, прошивка BNSH10S10):
 * сканирует BLE, подключается, проходит рукопожатие +VER/+PM/+PA (алгоритм из
 * KugooCrypto, портированный 1:1) и после этого даёт управлять тем, что самокат
 * реально поддерживает: свет, стробоскоп (мигание фарой), гудок (подбор команды),
 * блокировка, круиз, передачи.
 *
 * Скорость/батарея/пробег убраны: прошивка твоего самоката на все запросы этих
 * данных отвечает ERROR, а оригинальное приложение для kgkrn14 их тоже не запрашивает.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // Реальные UUID из декомпилированного LFBluetootService.java (kugookirin / kugoo).
        // Индекс в этой карте == "mode" в KugooCrypto, т.к. именно так была
        // устроена CONNECT_TYPE в оригинальном приложении.
        data class KnownService(val mode: Int, val label: String, val service: UUID, val write: UUID, val read: UUID)

        val KNOWN_SERVICES = listOf(
            KnownService(1, "default", UUID.fromString("9546bdc0-60ba-11e9-8647-0002a5d5c51b"),
                UUID.fromString("9546bdc1-60ba-11e9-8647-0002a5d5c51b"),
                UUID.fromString("9546bdc4-60ba-11e9-8647-0002a5d5c51b")),
            KnownService(2, "kugoo", UUID.fromString("b1b99ca0-98e7-11e9-80fe-0002a5d5c51b"),
                UUID.fromString("b1b99ca1-98e7-11e9-80fe-0002a5d5c51b"),
                UUID.fromString("b1b99ca4-98e7-11e9-80fe-0002a5d5c51b")),
            KnownService(3, "fmi", UUID.fromString("331023c0-fdcb-11e8-8eb2-0002a5d5c51b"),
                UUID.fromString("331023c1-fdcb-11e8-8eb2-0002a5d5c51b"),
                UUID.fromString("331023c4-fdcb-11e8-8eb2-0002a5d5c51b")),
        )

        // Запрос большого MTU сразу после подключения: по умолчанию в одну запись BLE
        // помещается только 20 байт, а команды протокола бывают длиннее.
        const val REQUESTED_MTU = 185

        // Кандидаты на команду гудка. В протоколе оригинального приложения Kugoo Kirin
        // команды гудка НЕТ, поэтому это подбор вслепую: только безопасные текстовые
        // команды вида "ключ=1"/"ключ=0" (самокат на неизвестные отвечает ERROR).
        val HORN_CANDIDATES = listOf(
            "+HORN=1" to "+HORN=0",
            "HORN=1" to "HORN=0",
            "+BELL=1" to "+BELL=0",
            "BELL=1" to "BELL=0",
            "+BEEP=1" to "+BEEP=0",
            "+BUZZ=1" to "+BUZZ=0",
            "+SIREN=1" to "+SIREN=0",
            "+ALRM=1" to "+ALRM=0",
            "+FIND=1" to "+FIND=0",
        )

        // Паттерны стробоскопа: список шагов (свет включён?, длительность в "тиках").
        // Длительность тика задаёт ползунок. null вместо шагов = генерируется случайно.
        class StrobePattern(val name: String, val steps: List<Pair<Boolean, Int>>?, val ultra: Boolean = false)

        private fun blink(n: Int, on: Int, off: Int): List<Pair<Boolean, Int>> =
            (1..n).flatMap { listOf(true to on, false to off) }

        val STROBE_PATTERNS = listOf(
            StrobePattern("1. Ровное мигание", listOf(true to 1, false to 1)),
            StrobePattern("2. Короткая вспышка", listOf(true to 1, false to 4)),
            StrobePattern("3. Двойная вспышка", blink(1, 1, 1) + listOf(true to 1, false to 5)),
            StrobePattern("4. Тройная вспышка", blink(2, 1, 1) + listOf(true to 1, false to 6)),
            StrobePattern("5. Полиция (серия + длинные)", blink(3, 1, 1) + listOf(true to 4, false to 4)),
            StrobePattern("6. SOS", blink(3, 1, 1) + blink(3, 3, 1) + blink(2, 1, 1) + listOf(true to 1, false to 6)),
            StrobePattern("7. Пульс (нарастание)", listOf(true to 1, false to 5, true to 2, false to 4,
                true to 3, false to 3, true to 4, false to 2, true to 5, false to 1)),
            StrobePattern("8. Маяк (редкая вспышка)", listOf(true to 1, false to 9)),
            StrobePattern("9. Случайный", null),
            StrobePattern("10. УЛЬТРА-быстро (макс. скорость)", listOf(true to 1, false to 1), ultra = true),
        )

        // Запросы для проверки поддержки: ключ ответа -> команда-запрос.
        val SUPPORT_QUERIES = listOf(
            "MODE" to "+MODE=?",
            "LOCK" to "+LOCK=?",
            "CRZE" to "+CRZE=?",
            "UNIT" to "+UNIT=?",
            "HLGT" to "HLGT=?",
        )
    }

    // ---- Общие элементы ----
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var listDevices: ListView
    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnTabControl: Button
    private lateinit var btnTabDiag: Button
    private lateinit var tabControl: View
    private lateinit var tabDiagnostics: View

    // ---- Вкладка "Диагностика" ----
    private lateinit var btnSend: Button
    private lateinit var btnSendCode: Button
    private lateinit var btnHandshake: Button
    private lateinit var editManual: EditText
    private lateinit var radioMode: RadioGroup
    private lateinit var checkAutoReply: CheckBox
    private lateinit var tvTelemetry: TextView

    // ---- Вкладка "Управление" ----
    private lateinit var tvAuthState: TextView
    private lateinit var checkBinaryLight: CheckBox
    private lateinit var btnLightOn: Button
    private lateinit var btnLightOff: Button
    private lateinit var btnStrobe: Button
    private lateinit var spinnerStrobe: Spinner
    private lateinit var tvLockState: TextView
    private lateinit var tvCruiseState: TextView
    private lateinit var editCode: EditText
    private lateinit var btnCodeSend: Button
    private lateinit var checkAutoCode: CheckBox
    private lateinit var tvCodeState: TextView

    // --- НОВЫЕ ПЕРЕМЕННЫЕ ДЛЯ БРУТФОРСА ---
    private lateinit var btnSetBruteList: Button
    private lateinit var btnStartBrute: Button
    private lateinit var tvBruteState: TextView

    private var bruteForceList = listOf<String>()
    private var bruteForceIndex = 0
    private var isBruteForcing = false
    private var currentBruteForcePassword = ""
    private val bruteForceTimeoutRunnable = Runnable {
        if (isBruteForcing) {
            log("Таймаут ответа на пароль $currentBruteForcePassword, пробуем следующий...")
            bruteForceIndex++
            sendNextBrutePassword()
        }
    }
    // --------------------------------------

    private lateinit var btnLockDiag: Button
    private lateinit var tvLockDiag: TextView
    private lateinit var tvStrobeSpeed: TextView
    private lateinit var seekStrobe: SeekBar
    private lateinit var spinnerHorn: Spinner
    private lateinit var btnHorn: Button
    private lateinit var btnHornProbe: Button
    private lateinit var btnHornFound: Button
    private lateinit var tvHornProbe: TextView
    private lateinit var btnLock: Button
    private lateinit var btnUnlock: Button
    private lateinit var btnCruiseOn: Button
    private lateinit var btnCruiseOff: Button
    private lateinit var btnGearUp: Button
    private lateinit var btnGearDown: Button
    private lateinit var btnCheckSupport: Button
    private lateinit var tvSupport: TextView

    // Текущая передача (+MODE=<n>): кнопки шлют +1/-1, диапазон ограничивает сама железка.
    private var gearNum = 1

    // Известное состояние света (из нашей команды или push-сообщения HLGT=0/1 от самоката).
    private var lightOn: Boolean? = null

    // Разобранные текстовые поля протокола вида "+КЛЮЧ=значение".
    private val telemetry = linkedMapOf<String, String>()

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager by lazy { getSystemService(BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }
    private val scanner by lazy { bluetoothAdapter.bluetoothLeScanner }

    private val scanResults = LinkedHashMap<String, BluetoothDevice>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var gatt: BluetoothGatt? = null
    private var scanning = false

    // Чтобы discoverServices() вызывался ровно один раз на соединение
    // (после ответа на запрос MTU либо по таймауту, если самокат MTU не поддержал).
    @Volatile private var servicesRequested = false

    // ---- Очередь GATT-операций (и запись в характеристику, и запись дескриптора) ----
    // BluetoothGatt позволяет иметь только одну GATT-операцию "в полёте" на всё соединение.
    // Вторая, вызванная до колбэка на первую, молча теряется — поэтому ВСЕ операции
    // идут через эту очередь и ждут подтверждения предыдущей.
    private sealed class GattOp {
        data class CharWrite(
            val ch: BluetoothGattCharacteristic,
            val bytes: ByteArray,
            val text: String,
            val quiet: Boolean = false,
            val noResp: Boolean = false
        ) : GattOp()
        data class DescWrite(val desc: BluetoothGattDescriptor, val value: ByteArray, val label: String) : GattOp()
    }
    // Явно kotlin.collections.ArrayDeque: в файле есть "import java.util.*", который иначе
    // перекрыл бы простое имя "ArrayDeque" java-версией без addLast/removeFirstOrNull.
    private val gattQueue = kotlin.collections.ArrayDeque<GattOp>()
    private var gattBusy = false
    private var currentOpQuiet = false

    // Writable характеристики для ручного выбора
    private val writableChars =
