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
 */
class MainActivity : AppCompatActivity() {

    companion object {
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

        const val REQUESTED_MTU = 185

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

        class StrobePattern(val name: String, val steps: List<Pair<Boolean, Int>>?, val ultra: Boolean = false)

        private fun blink(n: Int, on: Int, off: Int): List<Pair<Boolean, Int>> =
            (1..n).flatMap { listOf(true to on, false to off) }

        val STROBE_PATTERNS = listOf(
            StrobePattern("1. Ровное мигание", listOf(true to 1, false to 1)),
            StrobePattern("2. Короткая вспышка", listOf(true to 1, false to 4)),
            StrobePattern("3. Двойная вспышка", blink(1, 1, 1) + listOf(true to 1, false to 5)),
            StrobePattern("4. Тройная вспышка", blink(2, 1, 1) + listOf
