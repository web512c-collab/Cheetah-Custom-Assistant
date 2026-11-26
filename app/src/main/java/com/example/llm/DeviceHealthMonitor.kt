package com.example.llm

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.example.core.model.DeviceHealthSnapshot

/**
 * Inspects device battery level, charging state, and thermal status (API 29+)
 * so LocalLlmManager & SearchJudgePipeline can scale threads/context/results safely.
 */
object DeviceHealthMonitor {

    fun inspect(context: Context): DeviceHealthSnapshot {
        val batteryIntent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 100

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        var thermalLabel = "طبيعية"
        var isThrottled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            when (pm?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE,
                PowerManager.THERMAL_STATUS_LIGHT -> {
                    thermalLabel = "طبيعية"
                    isThrottled = false
                }
                PowerManager.THERMAL_STATUS_MODERATE -> {
                    thermalLabel = "دافئة (متوسطة)"
                    isThrottled = true
                }
                PowerManager.THERMAL_STATUS_SEVERE,
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                    thermalLabel = "مرتفعة (تخفيف تلقائي)"
                    isThrottled = true
                }
            }
        }

        return DeviceHealthSnapshot(
            batteryPercent = batteryPct,
            isCharging = isCharging,
            thermalStatusLabelAr = thermalLabel,
            isThermalThrottled = isThrottled,
            isBatteryLow = !isCharging && batteryPct <= 20
        )
    }
}
