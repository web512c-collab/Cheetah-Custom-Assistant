package com.example.controller

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.example.assistant.CheetahVoiceInteractionService
import com.example.core.model.PermissionHealthState

/**
 * Inspects real Android OS permission/role states across API 24–36 and builds
 * exact Settings Intents for Default Digital Assistant, Accessibility, and Battery.
 */
object PermissionHealthChecker {

    fun inspect(context: Context): PermissionHealthState {
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val isDefaultAssistant = checkIsDefaultAssistant(context)
        val isAccessibility = checkAccessibilityEnabled(context) ||
            CheetahAccessibilityService.activeInstance != null
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        val autoRevokeExempted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.packageManager.isAutoRevokeWhitelisted }.getOrDefault(true)
        } else {
            true
        }

        return PermissionHealthState(
            hasMicPermission = hasMic,
            hasNotificationPermission = hasNotif,
            isDefaultAssistant = isDefaultAssistant,
            isAccessibilityEnabled = isAccessibility,
            isBatteryOptimizationIgnored = batteryIgnored,
            isAutoRevokeExempted = autoRevokeExempted
        )
    }

    private fun checkIsDefaultAssistant(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            ) {
                return true
            }
        }
        val expectedComponent = ComponentName(
            context,
            CheetahVoiceInteractionService::class.java
        ).flattenToShortString()
        val voiceInteraction = runCatching {
            Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
        }.getOrNull().orEmpty()
        val assistantSetting = runCatching {
            Settings.Secure.getString(context.contentResolver, "assistant")
        }.getOrNull().orEmpty()

        return voiceInteraction.contains(context.packageName) ||
            assistantSetting.contains(context.packageName) ||
            voiceInteraction == expectedComponent
    }

    private fun checkAccessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any {
            it.resolveInfo?.serviceInfo?.packageName == context.packageName
        }
    }

    fun buildDefaultAssistantIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            ) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            }
        }
        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }

    fun buildVoiceInputSettingsIntent(): Intent =
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun buildAccessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun buildBatteryOptimizationIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun buildAppDetailsSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun buildAutoRevokeIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                return intent
            }
        }
        return buildAppDetailsSettingsIntent(context)
    }

    fun isPermissionPermanentlyDenied(
        activity: android.app.Activity?,
        permission: String,
        wasRequestedBefore: Boolean
    ): Boolean {
        if (activity == null || !wasRequestedBefore) return false
        val granted = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        if (granted) return false
        return !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
}
