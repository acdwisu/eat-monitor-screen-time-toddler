package com.example.eatmonitorremote

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class RemoteMainActivity : AppCompatActivity() {

    private lateinit var connectionBanner: TextView
    private lateinit var telemetryCard: TextView
    private lateinit var btnTriggerBlank: Button
    private lateinit var btnForceLock: Button
    private lateinit var btnUnlock: Button
    private lateinit var btnRescan: Button

    private var connectedDevice: DiscoveredChildDevice? = null
    private lateinit var discoveryManager: AutoDiscoveryManager

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPolling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isPolling) return
            sendCommand("/status")
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUi()

        discoveryManager = AutoDiscoveryManager(this) { device ->
            if (connectedDevice?.hostIp != device.hostIp) {
                connectedDevice = device
                onChildDeviceConnected(device)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        discoveryManager.startDiscovery()
        startStatusPolling()
    }

    override fun onPause() {
        super.onPause()
        stopStatusPolling()
        discoveryManager.stopDiscovery()
    }

    private fun setupUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(48, 48, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        connectionBanner = TextView(this).apply {
            text = "Mencari HP Anak di Wi-Fi / Hotspot secara otomatis..."
            setTextColor(Color.parseColor("#FFD740"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 24f
            }
        }
        root.addView(
            connectionBanner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 32 }
        )

        telemetryCard = TextView(this).apply {
            text = "STATE : MENUNGGU KONEKSI\nMAR   : -\nIDLE  : -"
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 16f
            setPadding(40, 40, 40, 40)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#263238"))
                cornerRadius = 28f
                setStroke(3, Color.parseColor("#37474F"))
            }
        }
        root.addView(
            telemetryCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 48 }
        )

        btnTriggerBlank = createActionButton(
            text = "BLANK LAYAR SEKARANG\n(Otomatis Terbuka Saat Anak Mengunyah)",
            bgColor = "#F57C00"
        ) {
            sendCommand("/trigger")
        }
        root.addView(btnTriggerBlank, buttonLayoutParams())

        btnForceLock = createActionButton(
            text = "KUNCI PAKSA (FORCE BLANK)\n(Tetap Hitam Sampai Di-Unlock)",
            bgColor = "#D32F2F"
        ) {
            sendCommand("/lock")
        }
        root.addView(btnForceLock, buttonLayoutParams())

        btnUnlock = createActionButton(
            text = "BUKA LAYAR (UNLOCK / RESET TIMER)",
            bgColor = "#388E3C"
        ) {
            sendCommand("/unlock")
        }
        root.addView(btnUnlock, buttonLayoutParams())

        btnRescan = createActionButton(
            text = "Pindai Ulang Jaringan (Auto-Detect)",
            bgColor = "#455A64"
        ) {
            connectedDevice = null
            connectionBanner.text = "Memindai ulang HP Anak di jaringan..."
            connectionBanner.setTextColor(Color.parseColor("#FFD740"))
            setActionButtonsEnabled(false)
            discoveryManager.triggerRescanBroadcast()
        }
        root.addView(btnRescan, buttonLayoutParams(topMargin = 36))

        setActionButtonsEnabled(false)
        setContentView(root)
    }

    private fun createActionButton(text: String, bgColor: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            isAllCaps = false
            setPadding(24, 32, 24, 32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadius = 24f
            }
            setOnClickListener { onClick() }
        }
    }

    private fun buttonLayoutParams(topMargin: Int = 20): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = topMargin
        }
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.4f
        btnTriggerBlank.isEnabled = enabled
        btnForceLock.isEnabled = enabled
        btnUnlock.isEnabled = enabled
        btnTriggerBlank.alpha = alpha
        btnForceLock.alpha = alpha
        btnUnlock.alpha = alpha
    }

    private fun onChildDeviceConnected(device: DiscoveredChildDevice) {
        connectionBanner.text = "TERHUBUNG OTOMATIS\n${device.deviceName} (${device.hostIp}:${device.port})"
        connectionBanner.setTextColor(Color.parseColor("#69F0AE"))
        setActionButtonsEnabled(true)
        sendCommand("/status")
    }

    private fun startStatusPolling() {
        if (isPolling) return
        isPolling = true
        mainHandler.post(pollRunnable)
    }

    private fun stopStatusPolling() {
        isPolling = false
        mainHandler.removeCallbacks(pollRunnable)
    }

    private fun sendCommand(endpoint: String) {
        val target = connectedDevice ?: return
        networkExecutor.execute {
            try {
                val url = URL("http://${target.hostIp}:${target.port}$endpoint")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1500
                    readTimeout = 1500
                }
                val code = conn.responseCode
                if (code == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    mainHandler.post { updateTelemetryUi(json) }
                }
                conn.disconnect()
            } catch (_: Exception) {
                mainHandler.post {
                    connectionBanner.text = "Koneksi terputus ke ${target.deviceName}. Mencari ulang otomatis..."
                    connectionBanner.setTextColor(Color.parseColor("#FF5252"))
                }
            }
        }
    }

    private fun updateTelemetryUi(json: JSONObject) {
        val state = json.optString("state", "UNKNOWN")
        val mar = json.optDouble("mar", 0.0)
        val deltaMar = json.optDouble("deltaMar", 0.0)
        val idleSec = json.optDouble("idleSec", 0.0)
        val maxHoldSec = json.optDouble("maxHoldSec", 15.0)
        val forceLocked = json.optBoolean("forceLocked", false)

        val stateColor = when (state) {
            "CHEWING" -> Color.parseColor("#69F0AE")
            "HOLDING_FOOD" -> Color.parseColor("#FFD740")
            "SCREEN_LOCKED" -> Color.parseColor("#FF5252")
            else -> Color.parseColor("#B0BEC5")
        }

        val lockLabel = if (forceLocked) " [KUNCI PAKSA]" else ""
        telemetryCard.setTextColor(stateColor)
        telemetryCard.text = String.format(
            Locale.US,
            "STATE : %s%s\nMAR   : %.3f (Δ: %.3f)\nIDLE  : %.1f dtk / %.0f dtk",
            state,
            lockLabel,
            mar,
            deltaMar,
            idleSec,
            maxHoldSec
        )
    }
}
