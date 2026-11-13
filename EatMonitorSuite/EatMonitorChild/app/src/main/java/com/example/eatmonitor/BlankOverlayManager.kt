package com.example.eatmonitor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale

class BlankOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var isShowing = false

    private var debugView: TextView? = null
    private var debugParams: WindowManager.LayoutParams? = null
    private var isDebugShowing = false

    private fun getOverlayLayoutType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    fun showOverlay() {
        mainHandler.post {
            if (isShowing) return@post

            val container = FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true
                isFocusable = true
            }

            val messageText = TextView(context).apply {
                text = "Ayo dikunyah dulu makanannya :)\nLayar akan menyala otomatis saat kamu mengunyah."
                setTextColor(Color.DKGRAY)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
            }

            container.addView(
                messageText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                getOverlayLayoutType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE
            )

            try {
                windowManager.addView(container, params)
                overlayView = container
                isShowing = true
                pauseBackgroundMedia()

                if (isDebugShowing && debugView != null) {
                    windowManager.removeView(debugView)
                    windowManager.addView(debugView, debugParams)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun hideOverlay() {
        mainHandler.post {
            if (!isShowing || overlayView == null) return@post
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                overlayView = null
                isShowing = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showDebugOverlay() {
        mainHandler.post {
            if (isDebugShowing) return@post

            val bgDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#CC121212"))
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#55FFFFFF"))
            }

            val badge = TextView(context).apply {
                background = bgDrawable
                setTextColor(Color.GREEN)
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setPadding(24, 16, 24, 16)
                text = "MEMULAI KAMERA..."
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayLayoutType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 120
            }

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            badge.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager.updateViewLayout(badge, params)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        true
                    }
                    else -> false
                }
            }

            try {
                windowManager.addView(badge, params)
                debugView = badge
                debugParams = params
                isDebugShowing = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateDebugStats(telemetry: EatingTelemetry, threshold: Float) {
        if (!isDebugShowing) return
        mainHandler.post {
            val badge = debugView ?: return@post

            val color = when (telemetry.state) {
                EatingState.CHEWING -> Color.parseColor("#69F0AE")
                EatingState.HOLDING_FOOD -> Color.parseColor("#FFD740")
                EatingState.SCREEN_LOCKED -> Color.parseColor("#FF5252")
                EatingState.NO_FACE -> Color.parseColor("#B0BEC5")
            }

            val lockTag = if (telemetry.isForceLocked) " [FORCE]" else ""
            badge.setTextColor(color)
            badge.text = String.format(
                Locale.US,
                "STATE : %s%s\nMAR   : %.3f\nΔMAR  : %.3f (Min: %.3f)\nIDLE  : %.1fs / %.0fs",
                telemetry.state.name,
                lockTag,
                telemetry.currentMar,
                telemetry.deltaMar,
                threshold,
                telemetry.idleSec,
                telemetry.maxHoldSec
            )
        }
    }

    fun hideDebugOverlay() {
        mainHandler.post {
            if (!isDebugShowing || debugView == null) return@post
            try {
                windowManager.removeView(debugView)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                debugView = null
                debugParams = null
                isDebugShowing = false
            }
        }
    }

    fun hideAll() {
        hideOverlay()
        hideDebugOverlay()
    }

    private fun pauseBackgroundMedia() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioManager.isMusicActive) {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        }
    }
}
