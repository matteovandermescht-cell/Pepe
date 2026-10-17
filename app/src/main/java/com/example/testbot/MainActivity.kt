package com.example.testbot

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var accessibilityHelpButton: Button
    private var setupInProgress = false
    private var waitingForSettings = false
    private var pendingSettingsRequest = NO_SETTINGS_REQUEST

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupInProgress = savedInstanceState?.getBoolean(STATE_SETUP_IN_PROGRESS) ?: false
        waitingForSettings = savedInstanceState?.getBoolean(STATE_WAITING_FOR_SETTINGS) ?: false
        pendingSettingsRequest =
            savedInstanceState?.getInt(STATE_PENDING_SETTINGS_REQUEST) ?: NO_SETTINGS_REQUEST
        val showAccessibilityHelp =
            savedInstanceState?.getBoolean(STATE_SHOW_ACCESSIBILITY_HELP) ?: false

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        statusText = TextView(this).apply {
            text = getString(R.string.setup_instructions)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        content.addView(statusText, matchWidth())

        content.addView(Button(this).apply {
            text = getString(R.string.setup_button)
            textSize = 18f
            minHeight = (64 * resources.displayMetrics.density).toInt()
            setOnClickListener {
                setupInProgress = true
                continueSetup()
            }
        }, matchWidth())

        accessibilityHelpButton = Button(this).apply {
            text = getString(R.string.open_app_info)
            visibility = if (showAccessibilityHelp) android.view.View.VISIBLE else android.view.View.GONE
            setOnClickListener { openAppInfoSettings() }
        }
        content.addView(accessibilityHelpButton, matchWidth())

        setContentView(content)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_SETUP_IN_PROGRESS, setupInProgress)
        outState.putBoolean(STATE_WAITING_FOR_SETTINGS, waitingForSettings)
        outState.putInt(STATE_PENDING_SETTINGS_REQUEST, pendingSettingsRequest)
        outState.putBoolean(
            STATE_SHOW_ACCESSIBILITY_HELP,
            accessibilityHelpButton.visibility == android.view.View.VISIBLE
        )
        super.onSaveInstanceState(outState)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!setupInProgress || requestCode != pendingSettingsRequest) return

        waitingForSettings = false
        pendingSettingsRequest = NO_SETTINGS_REQUEST

        when (requestCode) {
            OVERLAY_SETTINGS_REQUEST -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSetup(R.string.overlay_permission_missing)
                    return
                }
            }

            ACCESSIBILITY_SETTINGS_REQUEST -> {
                if (!isAccessibilityServiceEnabled()) {
                    showAccessibilityRestrictedHelp()
                    return
                }
            }

            APP_INFO_SETTINGS_REQUEST -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSetup(R.string.overlay_permission_missing)
                    return
                }
                setupInProgress = true
            }
        }
        continueSetup()
    }

    private fun continueSetup() {
        if (!setupInProgress || waitingForSettings) return

        when {
            !Settings.canDrawOverlays(this) -> openSettings(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
                OVERLAY_SETTINGS_REQUEST,
                R.string.open_overlay_settings
            )

            !isAccessibilityServiceEnabled() -> openSettings(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                ACCESSIBILITY_SETTINGS_REQUEST,
                R.string.open_accessibility_settings
            )

            else -> startOverlayService()
        }
    }

    private fun openSettings(intent: Intent, requestCode: Int, statusMessage: Int) {
        try {
            pendingSettingsRequest = requestCode
            waitingForSettings = true
            statusText.setText(statusMessage)
            startActivityForResult(intent, requestCode)
        } catch (error: android.content.ActivityNotFoundException) {
            waitingForSettings = false
            pendingSettingsRequest = NO_SETTINGS_REQUEST
            stopSetup(R.string.settings_unavailable)
        } catch (error: SecurityException) {
            waitingForSettings = false
            pendingSettingsRequest = NO_SETTINGS_REQUEST
            stopSetup(R.string.settings_unavailable)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val serviceName = ComponentName(this, BotAccessibilityService::class.java).flattenToString()
        return enabledServices.split(':').any { it.equals(serviceName, ignoreCase = true) }
    }

    private fun startOverlayService() {
        setupInProgress = false
        waitingForSettings = false
        pendingSettingsRequest = NO_SETTINGS_REQUEST
        accessibilityHelpButton.visibility = android.view.View.GONE
        statusText.setText(R.string.setup_complete)

        try {
            startForegroundService(Intent(this, BotOverlayService::class.java))
        } catch (error: IllegalStateException) {
            statusText.setText(R.string.overlay_service_failed)
        } catch (error: SecurityException) {
            statusText.setText(R.string.overlay_service_failed)
        }
    }

    private fun stopSetup(message: Int) {
        setupInProgress = false
        waitingForSettings = false
        pendingSettingsRequest = NO_SETTINGS_REQUEST
        statusText.setText(message)
    }

    private fun showAccessibilityRestrictedHelp() {
        setupInProgress = false
        waitingForSettings = false
        pendingSettingsRequest = NO_SETTINGS_REQUEST
        statusText.setText(R.string.accessibility_restricted_settings_help)
        accessibilityHelpButton.visibility = android.view.View.VISIBLE
    }

    private fun openAppInfoSettings() {
        setupInProgress = true
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        openSettings(intent, APP_INFO_SETTINGS_REQUEST, R.string.open_app_info)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        bottomMargin = (24 * resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val NO_SETTINGS_REQUEST = 0
        const val OVERLAY_SETTINGS_REQUEST = 1001
        const val ACCESSIBILITY_SETTINGS_REQUEST = 1002
        const val APP_INFO_SETTINGS_REQUEST = 1003
        const val STATE_SETUP_IN_PROGRESS = "setup_in_progress"
        const val STATE_WAITING_FOR_SETTINGS = "waiting_for_settings"
        const val STATE_PENDING_SETTINGS_REQUEST = "pending_settings_request"
        const val STATE_SHOW_ACCESSIBILITY_HELP = "show_accessibility_help"
    }
}
