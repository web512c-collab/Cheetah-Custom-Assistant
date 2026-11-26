package com.example.voice

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Watchdog receiver for BUG A1 background reliability:
 * - Triggered after WakeWordDetectionService.onTaskRemoved() or periodic watchdog alarm.
 * - Restarts WakeWordDetectionService when continuousListeningEnabled is true and microphone
 *   permission is granted.
 * - On Android 14+ (API 34+), if the OS throws ForegroundServiceStartNotAllowedException from
 *   the background, posts a single-tap recovery notification instead of crashing.
 */
class WakeWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_WATCHDOG_CHECK) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(appContext).currentSettings()
                if (!settings.continuousListeningEnabled || settings.isListeningMuted) {
                    return@launch
                }
                val hasMic = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasMic) return@launch

                if (!CheetahRuntimeBus.serviceRunning.value) {
                    val started = runCatching {
                        WakeWordDetectionService.start(appContext)
                        true
                    }.getOrDefault(false)
                    if (!started) {
                        postRecoveryNotification(appContext)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_WATCHDOG_CHECK = "com.example.voice.action.WATCHDOG_CHECK"
        private const val REQUEST_RESTART = 4410
        private const val REQUEST_PERIODIC = 4411
        private const val RECOVERY_CHANNEL_ID = "cheetah_boot_resume"
        private const val RECOVERY_NOTIFICATION_ID = 4409

        fun scheduleImmediateRestart(context: Context, delayMs: Long = 1_500L) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_RESTART,
                Intent(context, WakeWatchdogReceiver::class.java).apply {
                    action = ACTION_WATCHDOG_CHECK
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pi
            )
        }

        fun schedulePeriodicWatchdog(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_PERIODIC,
                Intent(context, WakeWatchdogReceiver::class.java).apply {
                    action = ACTION_WATCHDOG_CHECK
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 15 * 60_000L,
                15 * 60_000L,
                pi
            )
        }

        fun cancelWatchdog(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            for (req in intArrayOf(REQUEST_RESTART, REQUEST_PERIODIC)) {
                val pi = PendingIntent.getBroadcast(
                    context,
                    req,
                    Intent(context, WakeWatchdogReceiver::class.java).apply {
                        action = ACTION_WATCHDOG_CHECK
                    },
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pi != null) {
                    am.cancel(pi)
                    pi.cancel()
                }
            }
        }

        fun postRecoveryNotification(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    RECOVERY_CHANNEL_ID,
                    "استئناف المساعد في الخلفية",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                nm.createNotificationChannel(channel)
            }

            val openIntent = PendingIntent.getActivity(
                context,
                501,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BootCompletedReceiver.EXTRA_RESUME_WAKE_LISTENER, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, RECOVERY_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setContentTitle("شيتا جاهز للاستماع")
                .setContentText("اضغط هنا لاستئناف الاستماع لكلمة التنبيه في الخلفية.")
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .build()

            nm.notify(RECOVERY_NOTIFICATION_ID, notification)
        }
    }
}
