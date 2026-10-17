package com.example.testbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class BotAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var botEngine: BotEngine? = null
    private var receiverRegistered = false

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START_BOT -> botEngine?.start(serviceScope)
                ACTION_STOP_BOT -> botEngine?.stop()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        botEngine = BotEngine(
            captureScreenshot = { captureScreenshot() },
            performSwipe = { startX, startY, endX, endY ->
                dispatchSwipe(startX, startY, endX, endY)
            }
        )

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_START_BOT)
                addAction(ACTION_STOP_BOT)
            }
            ContextCompat.registerReceiver(
                this,
                controlReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onInterrupt() {
        botEngine?.stop()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onDestroy() {
        botEngine?.stop()
        serviceScope.cancel()
        if (receiverRegistered) {
            unregisterReceiver(controlReceiver)
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private suspend fun captureScreenshot(): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            var buffer: HardwareBuffer? = null
                            var wrappedBitmap: Bitmap? = null
                            var softwareBitmap: Bitmap? = null
                            try {
                                val hardwareBuffer = screenshot.hardwareBuffer
                                buffer = hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                    ?: ColorSpace.get(ColorSpace.Named.SRGB)
                                wrappedBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                    ?: throw IllegalStateException("Screenshot-Bitmap konnte nicht erstellt werden")
                                softwareBitmap = wrappedBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    ?: throw IllegalStateException("Screenshot konnte nicht kopiert werden")
                            } catch (error: Exception) {
                                Log.e(TAG, "Screenshot konnte nicht verarbeitet werden", error)
                            } finally {
                                wrappedBitmap?.recycle()
                                buffer?.close()
                            }

                            if (continuation.isActive) {
                                continuation.resume(softwareBitmap)
                            } else {
                                softwareBitmap?.recycle()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot fehlgeschlagen (Fehlercode $errorCode)")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                )
            } catch (error: Exception) {
                Log.e(TAG, "Screenshot-Aufnahme konnte nicht gestartet werden", error)
                if (continuation.isActive) continuation.resume(null)
            }
        }

    private fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): Boolean {
        return try {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val bounds = windowManager.currentWindowMetrics.bounds
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                Log.e(TAG, "Display-Grenzen sind ungültig; Wischgeste wird ausgelassen")
                return false
            }

            fun screenX(x: Float): Float =
                (bounds.left + x * bounds.width() / VIRTUAL_WIDTH)
                    .coerceIn(bounds.left.toFloat(), (bounds.right - 1).toFloat())

            fun screenY(y: Float): Float =
                (bounds.top + y * bounds.height() / VIRTUAL_HEIGHT)
                    .coerceIn(bounds.top.toFloat(), (bounds.bottom - 1).toFloat())

            val path = Path().apply {
                moveTo(screenX(startX), screenY(startY))
                lineTo(screenX(endX), screenY(endY))
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, SWIPE_DURATION_MS))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (error: SecurityException) {
            Log.e(TAG, "Wischgeste nicht erlaubt; Accessibility-Konfiguration prüfen", error)
            false
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Ungültige Koordinaten für Wischgeste", error)
            false
        } catch (error: RuntimeException) {
            Log.e(TAG, "Wischgeste konnte nicht vorbereitet werden", error)
            false
        }
    }

    companion object {
        const val ACTION_START_BOT = "com.example.testbot.action.START_BOT"
        const val ACTION_STOP_BOT = "com.example.testbot.action.STOP_BOT"

        private const val TAG = "BotAccessibility"
        private const val VIRTUAL_WIDTH = 1280f
        private const val VIRTUAL_HEIGHT = 720f
        private const val SWIPE_DURATION_MS = 200L
    }
}
