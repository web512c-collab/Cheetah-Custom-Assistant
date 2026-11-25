package com.example.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.R

/**
 * Backward-compatibility entry point delegating all wake-word monitoring and actions
 * directly to [WakeWordDetectionService].
 */
class CheetahWakeWordService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureMinimalForegroundIfNeeded()
        when (intent?.action) {
            ACTION_STOP -> WakeWordDetectionService.stop(applicationContext)
            ACTION_TOGGLE_MUTE -> WakeWordDetectionService.toggleMute(applicationContext)
            ACTION_RELOAD_TEMPLATES -> WakeWordDetectionService.reloadTemplates(applicationContext)
            ACTION_RUN_DIAGNOSTIC_TEST -> WakeWordDetectionService.runDiagnosticTest(applicationContext)
            else -> WakeWordDetectionService.start(applicationContext)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        return START_NOT_STICKY
    }

    private fun ensureMinimalForegroundIfNeeded() {
        val channelId = "cheetah_wake_word_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        getString(R.string.notification_channel_wake_word),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(getString(R.string.notification_listening_title))
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(4402, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(4402, notif)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = WakeWordDetectionService.ACTION_START
        const val ACTION_STOP = WakeWordDetectionService.ACTION_STOP
        const val ACTION_TOGGLE_MUTE = WakeWordDetectionService.ACTION_TOGGLE_MUTE
        const val ACTION_RELOAD_TEMPLATES = WakeWordDetectionService.ACTION_RELOAD_TEMPLATES
        const val ACTION_RUN_DIAGNOSTIC_TEST = WakeWordDetectionService.ACTION_RUN_DIAGNOSTIC_TEST
        const val EXTRA_FROM_WAKE_WORD = WakeWordDetectionService.EXTRA_FROM_WAKE_WORD
        const val EXTRA_VOICE_MATCH_SCORE = WakeWordDetectionService.EXTRA_VOICE_MATCH_SCORE

        fun start(context: Context) = WakeWordDetectionService.start(context)
        fun stop(context: Context) = WakeWordDetectionService.stop(context)
        fun reloadTemplates(context: Context) = WakeWordDetectionService.reloadTemplates(context)
        fun runDiagnosticTest(context: Context) = WakeWordDetectionService.runDiagnosticTest(context)
    }
}
