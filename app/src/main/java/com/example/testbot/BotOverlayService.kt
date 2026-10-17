package com.example.testbot

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button

class BotOverlayService : Service() {
    private class OverlayButton(context: Context) : Button(context) {
        override fun performClick(): Boolean = super.performClick()
    }

    private lateinit var windowManager: WindowManager
    private var button: Button? = null
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        startAsForegroundService()

        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay-Berechtigung fehlt; zuerst in den Systemeinstellungen erlauben")
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlayButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (isRunning) sendBotCommand(BotAccessibilityService.ACTION_STOP_BOT)
        button?.let {
            try {
                windowManager.removeView(it)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Overlay-Ansicht war bereits entfernt", error)
            }
        }
        button = null
        super.onDestroy()
    }

    private fun createOverlayButton() {
        val overlayButton = OverlayButton(this).apply {
            text = START_TEXT
            setOnClickListener {
                isRunning = !isRunning
                text = if (isRunning) STOP_TEXT else START_TEXT
                val action = if (isRunning) {
                    BotAccessibilityService.ACTION_START_BOT
                } else {
                    BotAccessibilityService.ACTION_STOP_BOT
                }
                sendBotCommand(action)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = INITIAL_X
            y = INITIAL_Y
        }
        installDragHandler(overlayButton, params)

        try {
            windowManager.addView(overlayButton, params)
            button = overlayButton
        } catch (error: RuntimeException) {
            Log.e(TAG, "Overlay-Button konnte nicht angezeigt werden", error)
            stopSelf()
        }
    }

    private fun startAsForegroundService() {
        val notificationManager = requireNotNull(
            getSystemService(NotificationManager::class.java)
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.bot_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.bot_notification_title))
            .setContentText(getString(R.string.bot_notification_text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDragHandler(
        overlayButton: Button,
        params: WindowManager.LayoutParams
    ) {
        var initialX = 0
        var initialY = 0
        var touchDownX = 0f
        var touchDownY = 0f
        var moved = false

        overlayButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - touchDownX).toInt()
                    val deltaY = (event.rawY - touchDownY).toInt()
                    if (kotlin.math.abs(deltaX) > TOUCH_SLOP || kotlin.math.abs(deltaY) > TOUCH_SLOP) {
                        moved = true
                    }
                    params.x = initialX + deltaX
                    params.y = initialY + deltaY
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (error: IllegalArgumentException) {
                        Log.e(TAG, "Overlay-Position konnte nicht aktualisiert werden", error)
                        return@setOnTouchListener false
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) overlayButton.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun sendBotCommand(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    private companion object {
        const val TAG = "BotOverlay"
        const val START_TEXT = "START"
        const val STOP_TEXT = "STOP"
        const val INITIAL_X = 24
        const val INITIAL_Y = 180
        const val TOUCH_SLOP = 8
        const val NOTIFICATION_CHANNEL_ID = "bot_overlay"
        const val NOTIFICATION_ID = 1
    }
}
