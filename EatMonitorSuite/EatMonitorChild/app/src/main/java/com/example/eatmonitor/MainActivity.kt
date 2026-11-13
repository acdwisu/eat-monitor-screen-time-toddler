package com.example.eatmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var holdTimeInput: EditText
    private lateinit var sensitivitySeek: SeekBar
    private lateinit var sensitivityLabel: TextView
    private lateinit var debugCheckBox: CheckBox
    private lateinit var toggleBtn: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateUiState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupProgrammaticUi()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun setupProgrammaticUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, 32)
        }
        root.addView(statusText)

        val holdLabel = TextView(this).apply {
            text = "Batas Waktu Mengemut / Diam (detik):"
        }
        root.addView(holdLabel)

        holdTimeInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("15")
        }
        root.addView(holdTimeInput)

        sensitivityLabel = TextView(this).apply {
            text = "Sensitivitas Gerak Mulut (Threshold ΔMAR: 0.030)"
            setPadding(0, 32, 0, 8)
        }
        root.addView(sensitivityLabel)

        sensitivitySeek = SeekBar(this).apply {
            max = 50
            progress = 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = 0.010f + (progress / 1000f)
                    sensitivityLabel.text = String.format(
                        Locale.US,
                        "Sensitivitas Gerak Mulut (Threshold ΔMAR: %.3f)",
                        value
                    )
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(sensitivitySeek)

        debugCheckBox = CheckBox(this).apply {
            text = "Tampilkan Floating Debug (MAR & Timer)"
            isChecked = true
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 32 }
            layoutParams = params
        }
        root.addView(debugCheckBox)

        toggleBtn = Button(this).apply {
            textSize = 16f
            setOnClickListener { onActionButtonClicked() }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 48 }
        root.addView(toggleBtn, btnParams)

        setContentView(root)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun updateUiState() {
        val ip = RemoteControlServer.getLocalIpAddress()
        when {
            !hasCameraPermission() -> {
                statusText.text = "Status: Izin Kamera belum diberikan."
                toggleBtn.text = "1. Berikan Izin Kamera"
            }
            !hasOverlayPermission() -> {
                statusText.text = "Status: Izin Tampil di Atas Aplikasi Lain (Overlay) belum aktif."
                toggleBtn.text = "2. Aktifkan Izin Overlay"
            }
            EatingMonitorService.isRunning -> {
                statusText.text = "Status: MONITORING AKTIF\nAuto-Discovery Remote: ON ($ip:8080)\nBuka aplikasi EatMonitor Remote di HP Orang Tua."
                toggleBtn.text = "Hentikan Monitoring"
            }
            else -> {
                statusText.text = "Status: Siap dijalankan (IP Lokal: $ip)."
                toggleBtn.text = "Mulai Monitoring Makan"
            }
        }
    }

    private fun onActionButtonClicked() {
        if (!hasCameraPermission()) {
            val perms = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
            return
        }

        if (!hasOverlayPermission()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        if (EatingMonitorService.isRunning) {
            val stopIntent = Intent(this, EatingMonitorService::class.java).apply {
                action = EatingMonitorService.ACTION_STOP
            }
            startService(stopIntent)
            toggleBtn.postDelayed({ updateUiState() }, 300)
        } else {
            val holdSec = holdTimeInput.text.toString().toIntOrNull()?.coerceAtLeast(5) ?: 15
            val sensitivity = 0.010f + (sensitivitySeek.progress / 1000f)
            val showDebug = debugCheckBox.isChecked

            val startIntent = Intent(this, EatingMonitorService::class.java).apply {
                action = EatingMonitorService.ACTION_START
                putExtra(EatingMonitorService.EXTRA_MAX_HOLD_SEC, holdSec)
                putExtra(EatingMonitorService.EXTRA_SENSITIVITY, sensitivity)
                putExtra(EatingMonitorService.EXTRA_SHOW_DEBUG, showDebug)
            }
            ContextCompat.startForegroundService(this, startIntent)
            toggleBtn.postDelayed({ updateUiState() }, 300)
        }
    }
}
