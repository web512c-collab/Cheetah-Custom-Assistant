package com.example.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.assistant.CheetahAssistActivity
import com.example.core.model.AssistantPipelineState
import com.example.data.SettingsRepository
import com.example.data.VoiceTemplateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Transparent Foreground Service for continuous local Wake-Word & Voice Match monitoring.
 * Uses Android 14+ FOREGROUND_SERVICE_TYPE_MICROPHONE and exposes instant Mute / Stop controls
 * in the persistent privacy notification.
 */
class CheetahWakeWordService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var templateStore: VoiceTemplateStore

    @Volatile
    private var isMuted = false

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        templateStore = VoiceTemplateStore(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    settingsRepository.setContinuousListening(false)
                    stopForegroundCompat()
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_MUTE -> {
                isMuted = !isMuted
                serviceScope.launch {
                    settingsRepository.setListeningMuted(isMuted)
                }
                updateNotification()
                return START_STICKY
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        CheetahRuntimeBus.setServiceRunning(true)
        CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.LISTENING_WAKE_WORD)
        startAcousticMonitorLoop()
        return START_STICKY
    }

    private fun startAcousticMonitorLoop() {
        if (captureJob?.isActive == true) return
        captureJob = serviceScope.launch {
            val sampleRate = AudioFeatureExtractor.SAMPLE_RATE
            val windowSamples = (sampleRate * 1.4f).toInt()
            val ringBuffer = ShortArray(windowSamples)
            var ringCursor = 0

            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            }.getOrNull()

            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder?.release()
                CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.ERROR)
                stopSelf()
                return@launch
            }

            val chunk = ShortArray(640) // 40ms chunks
            var cooldownUntilMs = 0L
            var activeFrames = 0

            try {
                recorder.startRecording()
                while (isActive) {
                    if (isMuted || CheetahRuntimeBus.isSttActive.value) {
                        runCatching {
                            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                recorder.stop()
                            }
                        }
                        CheetahRuntimeBus.updateMicLevel(0f)
                        delay(150)
                        continue
                    } else if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        runCatching { recorder.startRecording() }
                    }

                    val read = recorder.read(chunk, 0, chunk.size)
                    if (read <= 0) {
                        delay(40)
                        continue
                    }

                    for (i in 0 until read) {
                        ringBuffer[ringCursor] = chunk[i]
                        ringCursor = (ringCursor + 1) % windowSamples
                    }

                    val rms = AudioFeatureExtractor.computeRms(chunk, read)
                    CheetahRuntimeBus.updateMicLevel((rms * 4.5f).coerceIn(0f, 1f))

                    val settings = settingsRepository.currentSettings()
                    val vadThreshold = (0.048f - (settings.wakeSensitivity * 0.025f))
                        .coerceIn(0.014f, 0.05f)

                    if (rms > vadThreshold) {
                        activeFrames++
                    } else {
                        if (activeFrames in 5..40 && System.currentTimeMillis() > cooldownUntilMs) {
                            // Unroll circular buffer in chronological order
                            val ordered = ShortArray(windowSamples)
                            val tailLen = windowSamples - ringCursor
                            System.arraycopy(ringBuffer, ringCursor, ordered, 0, tailLen)
                            System.arraycopy(ringBuffer, 0, ordered, tailLen, ringCursor)

                            val liveVec = AudioFeatureExtractor.extractSpeakerVector(ordered)
                            val enrolledCentroid = templateStore.getAveragedSpeakerCentroid()

                            if (liveVec != null && enrolledCentroid != null) {
                                val similarity = AudioFeatureExtractor.cosineSimilarity(
                                    liveVec,
                                    enrolledCentroid
                                )
                                val passed = !settings.voiceMatchEnabled ||
                                    similarity >= settings.voiceMatchThreshold

                                if (passed && similarity >= (settings.voiceMatchThreshold - 0.08f)) {
                                    cooldownUntilMs = System.currentTimeMillis() + 3500L
                                    triggerWakeEvent(similarity, passed)
                                }
                            } else if (liveVec != null && enrolledCentroid == null && activeFrames in 7..30) {
                                // Text-typed wake mode without acoustic enrollment:
                                // Trigger AssistActivity to verify typed wake phrase via offline STT.
                                cooldownUntilMs = System.currentTimeMillis() + 4500L
                                triggerWakeEvent(score = null, passed = true)
                            }
                        }
                        activeFrames = 0
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                CheetahRuntimeBus.setServiceRunning(false)
            }
        }
    }

    private fun triggerWakeEvent(score: Float?, passed: Boolean) {
        vibratePulse()
        CheetahRuntimeBus.emitWakeTrigger(
            WakeTriggerEvent(
                timestampMs = System.currentTimeMillis(),
                voiceMatchScore = score,
                passedVoiceMatch = passed,
                source = if (score != null) "FOREGROUND_ACOUSTIC_MATCH" else "FOREGROUND_VAD_TEXT_MODE"
            )
        )
        val assistIntent = Intent(this, CheetahAssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_FROM_WAKE_WORD, true)
            if (score != null) {
                putExtra(EXTRA_VOICE_MATCH_SCORE, score)
            }
        }
        runCatching { startActivity(assistIntent) }
    }

    @Suppress("DEPRECATION")
    private fun vibratePulse() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(45L)
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CheetahWakeWordService::class.java).apply { action = ACTION_TOGGLE_MUTE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, CheetahWakeWordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isMuted) "شيتا في وضع الكتم المؤقت" else getString(R.string.notification_listening_title)
        val body = if (isMuted) {
            "المايك مكتوم مؤقتاً — اضغط لإلغاء الكتم."
        } else {
            getString(R.string.notification_listening_body)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent)
            .addAction(
                0,
                if (isMuted) "إلغاء الكتم" else getString(R.string.notification_action_mute),
                muteIntent
            )
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_wake_word),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_wake_word_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        captureJob?.cancel()
        serviceScope.cancel()
        CheetahRuntimeBus.setServiceRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "cheetah_wake_word_channel"
        private const val NOTIFICATION_ID = 4401
        const val ACTION_START = "com.example.voice.action.START"
        const val ACTION_STOP = "com.example.voice.action.STOP"
        const val ACTION_TOGGLE_MUTE = "com.example.voice.action.TOGGLE_MUTE"
        const val EXTRA_FROM_WAKE_WORD = "extra_from_wake_word"
        const val EXTRA_VOICE_MATCH_SCORE = "extra_voice_match_score"

        fun start(context: Context) {
            val intent = Intent(context, CheetahWakeWordService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CheetahWakeWordService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
