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
import android.widget.Toast
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
                            var processingFailure: Exception? = null
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
                                processingFailure = error
                            } finally {
                                wrappedBitmap?.recycle()
                                buffer?.close()
                            }

                            if (continuation.isActive) {
                                if (processingFailure != null || softwareBitmap == null) {
                                    reportScreenshotFailure(
                                        "Screenshot-Bild konnte nicht verarbeitet werden. " +
                                            "Prüfe, ob der Accessibility-Service aktiv ist und Screenshots erlaubt."
                                    )
                                    continuation.resume(null)
                                } else {
                                    continuation.resume(softwareBitmap)
                                }
                            } else {
                                softwareBitmap?.recycle()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            val message = screenshotErrorMessage(errorCode)
                            Log.e(TAG, "$message (Fehlercode $errorCode)")
                            if (continuation.isActive) {
                                reportScreenshotFailure(message)
                                continuation.resume(null)
                            }
                        }
                    }
                )
            } catch (error: Exception) {
                Log.e(TAG, "Screenshot-Aufnahme konnte nicht gestartet werden", error)
                if (continuation.isActive) {
                    reportScreenshotFailure(
                        "Screenshot-Aufnahme konnte nicht gestartet werden. " +
                            "Prüfe, ob der Accessibility-Service aktiv ist."
                    )
                    continuation.resume(null)
                }
            }
        }

    private fun screenshotErrorMessage(errorCode: Int): String =
        when (errorCode) {
            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                "Android hat den Screenshot-Zugriff verweigert. Aktiviere den Color Bot Accessibility-Service."
            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                "Android verweigert Screenshots für diesen geschützten Bildschirm."
            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                "Android hat die Screenshot-Anfrage abgelehnt, weil die vorherige zu kurz zurückliegt."
            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
                "Der Bildschirm für den Screenshot ist nicht verfügbar."
            ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
                "Das Fenster für den Screenshot ist nicht verfügbar."
            else ->
                "Der Screenshot ist fehlgeschlagen. Prüfe die Accessibility-Berechtigung und versuche es erneut."
        }

    private fun reportScreenshotFailure(message: String) {
        botEngine?.stop()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
