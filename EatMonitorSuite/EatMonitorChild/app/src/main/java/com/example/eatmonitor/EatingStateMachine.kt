package com.example.eatmonitor

import kotlin.math.abs
import kotlin.math.hypot

data class Point2D(val x: Float, val y: Float)

enum class EatingState {
    NO_FACE,
    CHEWING,
    HOLDING_FOOD,
    SCREEN_LOCKED
}

data class EatingTelemetry(
    val state: EatingState,
    val currentMar: Float,
    val deltaMar: Float,
    val idleSec: Float,
    val maxHoldSec: Float,
    val isForceLocked: Boolean
)

class EatingStateMachine(
    var maxHoldingDurationMs: Long = 15_000L,
    var chewDeltaThreshold: Float = 0.030f,
    var newBiteMarThreshold: Float = 0.35f
) {
    @Volatile var currentState: EatingState = EatingState.CHEWING
        private set

    @Volatile var lastMar: Float = 0f
        private set

    @Volatile var lastDeltaMar: Float = 0f
        private set

    @Volatile var isForceLocked: Boolean = false

    private var lastActiveChewTimeMs: Long = System.currentTimeMillis()
    private var lastFaceSeenTimeMs: Long = System.currentTimeMillis()
    private var consecutiveChewFrames: Int = 0

    fun calculateMar(landmarks: List<Point2D>): Float {
        if (landmarks.size < 468) return 0f
        fun dist(i1: Int, i2: Int): Float {
            return hypot(landmarks[i1].x - landmarks[i2].x, landmarks[i1].y - landmarks[i2].y)
        }

        val v1 = dist(82, 87)
        val v2 = dist(13, 14)
        val v3 = dist(312, 317)
        val horizontal = dist(78, 308)

        if (horizontal == 0f) return 0f
        return (v1 + v2 + v3) / (3f * horizontal)
    }

    fun triggerBlankNow(nowMs: Long = System.currentTimeMillis()) {
        isForceLocked = false
        lastActiveChewTimeMs = nowMs - maxHoldingDurationMs - 1000L
        consecutiveChewFrames = 0
        currentState = EatingState.SCREEN_LOCKED
    }

    fun getCurrentIdleSec(nowMs: Long = System.currentTimeMillis()): Float {
        return ((nowMs - lastActiveChewTimeMs).coerceAtLeast(0L)) / 1000f
    }

    fun onFrameProcessed(landmarks: List<Point2D>?, nowMs: Long = System.currentTimeMillis()): EatingTelemetry {
        val maxHoldSec = maxHoldingDurationMs / 1000f

        if (isForceLocked) {
            currentState = EatingState.SCREEN_LOCKED
            val mar = if (landmarks != null) calculateMar(landmarks) else 0f
            lastMar = mar
            return EatingTelemetry(currentState, mar, 0f, getCurrentIdleSec(nowMs), maxHoldSec, true)
        }

        if (landmarks == null) {
            if (nowMs - lastFaceSeenTimeMs > 5_000L && currentState != EatingState.SCREEN_LOCKED) {
                currentState = EatingState.NO_FACE
            }
            return EatingTelemetry(currentState, 0f, 0f, getCurrentIdleSec(nowMs), maxHoldSec, false)
        }

        lastFaceSeenTimeMs = nowMs
        val currentMar = calculateMar(landmarks)
        val deltaMar = abs(currentMar - lastMar)
        lastMar = currentMar
        lastDeltaMar = deltaMar

        val isNewBite = currentMar >= newBiteMarThreshold
        val isActivelyChewing = deltaMar >= chewDeltaThreshold

        if (isNewBite || isActivelyChewing) {
            consecutiveChewFrames++
            if (currentState != EatingState.SCREEN_LOCKED || consecutiveChewFrames >= 3 || isNewBite) {
                lastActiveChewTimeMs = nowMs
                currentState = EatingState.CHEWING
            }
            return EatingTelemetry(currentState, currentMar, deltaMar, getCurrentIdleSec(nowMs), maxHoldSec, false)
        } else {
            consecutiveChewFrames = 0
        }

        val idleDurationMs = nowMs - lastActiveChewTimeMs
        currentState = when {
            idleDurationMs >= maxHoldingDurationMs -> EatingState.SCREEN_LOCKED
            idleDurationMs >= 3_000L -> EatingState.HOLDING_FOOD
            else -> EatingState.CHEWING
        }

        return EatingTelemetry(currentState, currentMar, deltaMar, idleDurationMs / 1000f, maxHoldSec, false)
    }

    fun reset(nowMs: Long = System.currentTimeMillis()) {
        isForceLocked = false
        lastActiveChewTimeMs = nowMs
        lastFaceSeenTimeMs = nowMs
        consecutiveChewFrames = 0
        lastMar = 0f
        lastDeltaMar = 0f
        currentState = EatingState.CHEWING
    }
}
