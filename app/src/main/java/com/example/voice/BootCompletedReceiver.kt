package com.example.voice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles device reboot / package replacement safely across Android 7–16:
 * - On API 24–33: attempts to start WakeWordDetectionService when continuous listening was enabled.
 * - On API 34+ (Android 14+): starting a microphone foreground service directly from BOOT_COMPLETED
 *   is restricted by OS policy, so we post a clear one-tap Resume notification that opens/starts it.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

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

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ prohibits starting microphone FGS from BOOT_COMPLETED
                    postResumeNotification(appContext)
                } else {
                    val started = runCatching {
                        WakeWordDetectionService.start(appContext)
                        true
                    }.getOrDefault(false)
                    if (!started) {
                        postResumeNotification(appContext)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postResumeNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                RESUME_CHANNEL_ID,
                "استئناف شيتا بعد إعادة التشغيل",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            context,
            501,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_RESUME_WAKE_LISTENER, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, RESUME_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("شيتا جاهز للاستماع")
            .setContentText("اضغط لتفعيل خدمة كلمة التنبيه بعد إعادة تشغيل الهاتف (مطلوب في أندرويد 14+).")
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        nm.notify(RESUME_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val RESUME_CHANNEL_ID = "cheetah_boot_resume"
        private const val RESUME_NOTIFICATION_ID = 4409
        const val EXTRA_RESUME_WAKE_LISTENER = "extra_resume_wake_listener"
    }
}
