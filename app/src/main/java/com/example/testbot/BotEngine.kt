package com.example.testbot

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class BotEngine(
    private val captureScreenshot: suspend () -> Bitmap?,
    private val performSwipe: (startX: Float, startY: Float, endX: Float, endY: Float) -> Boolean
) {
    enum class Decision {
        ZONE,
        BUSH,
        GROUND,
        UNKNOWN,
        INVALID_BITMAP
    }

    private enum class Terrain {
        ZONE,
        BUSH,
        GROUND,
        UNKNOWN
    }

    private var loopJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return

        loopJob = scope.launch {
            while (isActive) {
                val tickStartedAt = SystemClock.uptimeMillis()
                try {
                    val bitmap = try {
                        withTimeout(CAPTURE_TIMEOUT_MS) { captureScreenshot() }
                    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                        Log.w(TAG, "Screenshot-Aufnahme hat das Zeitlimit überschritten", timeout)
                        null
                    }
                    if (bitmap != null) {
                        try {
                            when (processScreenshot(bitmap)) {
                                Decision.ZONE -> swipeTo(50f, 600f)
                                Decision.GROUND -> swipeTo(250f, 700f)
                                Decision.BUSH, Decision.UNKNOWN, Decision.INVALID_BITMAP -> Unit
                            }
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "Bot-Iteration fehlgeschlagen", error)
                }

                val remaining = TICK_INTERVAL_MS - (SystemClock.uptimeMillis() - tickStartedAt)
                if (remaining > 0) delay(remaining)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    private fun processScreenshot(bitmap: Bitmap): Decision {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return Decision.INVALID_BITMAP
        }

        var hasZone = false
        var hasBush = false
        var hasGround = false

        for (virtualY in SAMPLE_TOP_Y..SAMPLE_BOTTOM_Y step SAMPLE_STEP) {
            val x = (PLAYER_X * bitmap.width / VIRTUAL_WIDTH).coerceIn(0, bitmap.width - 1)
            val y = (virtualY * bitmap.height / VIRTUAL_HEIGHT).coerceIn(0, bitmap.height - 1)
            val color = try {
                bitmap.getPixel(x, y)
            } catch (error: RuntimeException) {
                Log.e(TAG, "Screenshot-Pixel konnte nicht gelesen werden", error)
                return Decision.INVALID_BITMAP
            }

            when (classify(color)) {
                Terrain.ZONE -> hasZone = true
                Terrain.BUSH -> hasBush = true
                Terrain.GROUND -> hasGround = true
                Terrain.UNKNOWN -> Unit
            }
        }

        return when {
            hasZone -> Decision.ZONE
            hasBush -> Decision.BUSH
            hasGround -> Decision.GROUND
            else -> Decision.UNKNOWN
        }
    }

    private fun swipeTo(endX: Float, endY: Float) {
        if (!performSwipe(JOYSTICK_X, JOYSTICK_Y, endX, endY)) {
            Log.w(TAG, "AccessibilityService hat die Wischgeste nicht angenommen")
        }
    }

    private fun classify(color: Int): Terrain {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF

        return when {
            near(red, green, blue, 50, 200, 50, ZONE_TOLERANCE) -> Terrain.ZONE
            near(red, green, blue, 0, 100, 0, BUSH_TOLERANCE) -> Terrain.BUSH
            near(red, green, blue, 100, 50, 150, GROUND_TOLERANCE) -> Terrain.GROUND
            else -> Terrain.UNKNOWN
        }
    }

    private fun near(
        red: Int,
        green: Int,
        blue: Int,
        expectedRed: Int,
        expectedGreen: Int,
        expectedBlue: Int,
        tolerance: Int
    ): Boolean =
        kotlin.math.abs(red - expectedRed) <= tolerance &&
            kotlin.math.abs(green - expectedGreen) <= tolerance &&
            kotlin.math.abs(blue - expectedBlue) <= tolerance

    private companion object {
        const val TAG = "BotEngine"
        const val VIRTUAL_WIDTH = 1280
        const val VIRTUAL_HEIGHT = 720
        const val PLAYER_X = 640
        const val JOYSTICK_X = 150f
        const val JOYSTICK_Y = 600f
        const val SAMPLE_TOP_Y = 360
        const val SAMPLE_BOTTOM_Y = 400
        const val SAMPLE_STEP = 5
        const val ZONE_TOLERANCE = 45
        const val BUSH_TOLERANCE = 35
        const val GROUND_TOLERANCE = 45
        const val CAPTURE_TIMEOUT_MS = 2_000L
        const val TICK_INTERVAL_MS = 500L
    }
}
