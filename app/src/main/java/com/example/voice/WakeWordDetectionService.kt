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
import com.example.controller.DeviceActionExecutor
import com.example.controller.PermissionHealthChecker
import com.example.core.model.AssistantPipelineState
import com.example.core.model.AssistantSettings
import com.example.core.model.GuardrailVerdict
import com.example.core.model.WakeWordSetupMode
import com.example.data.ConversationMemoryStore
import com.example.data.SettingsRepository
import com.example.data.VoiceTemplateStore
import com.example.guardrails.CommandGuardrailValidator
import com.example.intelligence.EgyptianColloquialNluParser
import com.example.intelligence.JarvisEgyptianResponder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Local Foreground Service (FOREGROUND_SERVICE_TYPE_MICROPHONE) that monitors 16kHz PCM
 * microphone audio continuously for the Egyptian Arabic wake phrase 'يا شيتا' using:
 * 1) The bundled offline CHKW v1 Int8 binary acoustic model (assets/models/cheetah_ya_sheeta_kws.bin)
 * 2) Optional user-enrolled MFCC-DTW templates & Voice Match speaker verification (VoiceTemplateStore)
 *
 * Operates 100% locally on-device with zero external network calls or API keys, and executes
 * the full hands-free post-wake STT -> NLU -> Guardrails -> Action -> TTS loop even when the
 * screen is off or UI is backgrounded.
 */
class WakeWordDetectionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var templateStore: VoiceTemplateStore
    private lateinit var memoryStore: ConversationMemoryStore
    private var bundledModel: BundledYaSheetaAcousticModel? = null
    private lateinit var wakeWordEngine: WakeWordEngine
    private lateinit var sttManager: EgyptianOfflineSttManager
    private lateinit var ttsManager: EgyptianTtsManager

    @Volatile
    private var isMuted = false

    @Volatile
    private var diagnosticTestUntilMs = 0L

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        templateStore = VoiceTemplateStore(applicationContext)
        memoryStore = ConversationMemoryStore(applicationContext)

        val modelResult = BundledYaSheetaAcousticModel.loadFromAssets(applicationContext)
        bundledModel = modelResult.getOrNull()
        wakeWordEngine = WakeWordEngine(templateStore, bundledModel)

        sttManager = EgyptianOfflineSttManager(applicationContext)
        ttsManager = EgyptianTtsManager(applicationContext)
        createNotificationChannel()

        if (modelResult.isFailure) {
            CheetahRuntimeBus.updateDiagnostics {
                it.copy(
                    lastError = "تعذر تحميل النموذج المدمج: ${modelResult.exceptionOrNull()?.message ?: "unknown"}"
                )
            }
        }
        updateModelDiagnostics(settingsRepository)
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
                CheetahRuntimeBus.updateDiagnostics {
                    it.copy(
                        wakeEngineStatus = if (isMuted) "MUTED" else activeEngineLabel(settingsRepository),
                        lastAudioEvent = if (isMuted) "تم كتم المايك مؤقتاً" else "تم استئناف الاستماع لكلمة 'يا شيتا'"
                    )
                }
                updateNotification()
                return START_STICKY
            }

            ACTION_RELOAD_TEMPLATES -> {
                templateStore.reload()
                updateModelDiagnostics(settingsRepository)
                return START_STICKY
            }

            ACTION_RUN_DIAGNOSTIC_TEST -> {
                diagnosticTestUntilMs = System.currentTimeMillis() + 5_500L
                CheetahRuntimeBus.updateDiagnostics {
                    it.copy(lastAudioEvent = "وضع الاختبار التشخيصي نشط لمدة 5 ثوانٍ — قل 'يا شيتا' الآن")
                }
                if (captureJob?.isActive == true) {
                    return START_STICKY
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            CheetahRuntimeBus.updateDiagnostics {
                it.copy(
                    audioRecordStatus = "PERMISSION_DENIED",
                    lastError = "صلاحية المايكروفون غير ممنوحة"
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            isMuted = settingsRepository.currentSettings().isListeningMuted
            updateNotification()
        }

        startForegroundCompat()
        CheetahRuntimeBus.setServiceRunning(true)
        CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.LISTENING_WAKE_WORD)
        startAcousticMonitorLoop()
        return START_STICKY
    }

    private fun buildModelStatusLabel(): String {
        val mfccCount = templateStore.samples.value.count { it.mfccSequence.isNotEmpty() }
        val bin = bundledModel
        return if (bin != null) {
            "CHKW-v1 ('يا شيتا', ${bin.modelSizeBytes}B) + $mfccCount/${VoiceTemplateStore.MAX_SAMPLES} MFCC"
        } else {
            "$mfccCount/${VoiceTemplateStore.MAX_SAMPLES} MFCC templates"
        }
    }

    private fun activeEngineLabel(settings: AssistantSettings): String {
        val mfccCount = templateStore.samples.value.count { it.mfccSequence.isNotEmpty() }
        val usingBundled = bundledModel != null && wakeWordEngine.isUsingDefaultYaSheetaPhrase(settings)
        return when {
            usingBundled && mfccCount > 0 -> "BUNDLED_BIN+USER_DTW"
            usingBundled -> "BUNDLED_CHKW_BIN ('يا شيتا')"
            mfccCount > 0 -> "USER_MFCC_DTW"
            else -> "TEXT_STT_GATE"
        }
    }

    private fun activeEngineLabel(repo: SettingsRepository): String {
        val mfccCount = templateStore.samples.value.count { it.mfccSequence.isNotEmpty() }
        return if (bundledModel != null) {
            if (mfccCount > 0) "BUNDLED_BIN+USER_DTW" else "BUNDLED_CHKW_BIN ('يا شيتا')"
        } else if (mfccCount > 0) {
            "USER_MFCC_DTW"
        } else {
            "TEXT_STT_GATE"
        }
    }

    private fun updateModelDiagnostics(repo: SettingsRepository) {
        serviceScope.launch {
            val s = repo.currentSettings()
            CheetahRuntimeBus.updateDiagnostics {
                it.copy(
                    modelStatus = buildModelStatusLabel(),
                    wakeEngineStatus = if (isMuted) "MUTED" else activeEngineLabel(s),
                    detectionThreshold = wakeWordEngine.sensitivityToDtwThreshold(s.wakeSensitivity)
                )
            }
        }
    }

    private fun openAudioRecord(): Pair<AudioRecord?, String> {
        val sampleRate = AudioFeatureExtractor.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val candidates = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.MIC to "MIC"
        )
        for ((source, label) in candidates) {
            val rec = runCatching {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 3
                )
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                return rec to label
            }
            rec?.release()
        }
        return null to "NONE"
    }

    private fun startAcousticMonitorLoop() {
        if (captureJob?.isActive == true) return
        captureJob = serviceScope.launch {
            val sampleRate = AudioFeatureExtractor.SAMPLE_RATE
            val chunkSamples = 640 // 40ms chunks at 16kHz
            val maxRingSamples = (sampleRate * 2.5f).toInt()
            val ringBuffer = ShortArray(maxRingSamples)
            var ringCursor = 0
            val chunk = ShortArray(chunkSamples)

            var recorder: AudioRecord? = null
            var audioSourceLabel = "NONE"

            var noiseFloorRms = 0.012f
            var calibrationFrames = 0
            var inSpeech = false
            var speechStartCursor = 0
            var speechSamplesCount = 0
            var silenceFrames = 0
            var peakUtteranceRms = 0f
            var cooldownUntilMs = 0L

            try {
                while (isActive) {
                    val settings = settingsRepository.currentSettings()
                    isMuted = settings.isListeningMuted

                    if (isMuted || CheetahRuntimeBus.isSttActive.value) {
                        recorder?.let { rec ->
                            runCatching { rec.stop() }
                            rec.release()
                        }
                        recorder = null
                        CheetahRuntimeBus.updateMicLevel(0f)
                        CheetahRuntimeBus.updateDiagnostics {
                            it.copy(
                                audioRecordStatus = if (isMuted) "MUTED" else "PAUSED_FOR_STT",
                                wakeEngineStatus = if (isMuted) "MUTED" else "HANDOFF_STT"
                            )
                        }
                        delay(160)
                        continue
                    }

                    if (recorder == null) {
                        val (newRec, label) = openAudioRecord()
                        if (newRec == null) {
                            CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.ERROR)
                            CheetahRuntimeBus.updateDiagnostics {
                                it.copy(
                                    audioRecordStatus = "INIT_FAILED",
                                    audioSourceUsed = "NONE",
                                    lastError = "تعذر تهيئة AudioRecord (قد يكون المايك مستخدماً من تطبيق آخر)"
                                )
                            }
                            delay(2000)
                            continue
                        }
                        recorder = newRec
                        audioSourceLabel = label
                        runCatching { newRec.startRecording() }
                        CheetahRuntimeBus.updateDiagnostics {
                            it.copy(
                                audioRecordStatus = "RECORDING",
                                audioSourceUsed = audioSourceLabel,
                                sampleRateHz = sampleRate,
                                wakeEngineStatus = activeEngineLabel(settings),
                                modelStatus = buildModelStatusLabel(),
                                detectionThreshold = wakeWordEngine.sensitivityToDtwThreshold(settings.wakeSensitivity),
                                lastError = null
                            )
                        }
                    }

                    val rec = recorder!!
                    val read = rec.read(chunk, 0, chunk.size)
                    if (read <= 0) {
                        delay(30)
                        continue
                    }

                    for (i in 0 until read) {
                        ringBuffer[ringCursor] = chunk[i]
                        ringCursor = (ringCursor + 1) % maxRingSamples
                    }

                    val rms = AudioFeatureExtractor.computeRms(chunk, read)
                    CheetahRuntimeBus.updateMicLevel((rms * 5.0f).coerceIn(0f, 1f))

                    if (calibrationFrames < 12) {
                        noiseFloorRms = if (calibrationFrames == 0) rms else (noiseFloorRms * 0.7f + rms * 0.3f)
                        calibrationFrames++
                        continue
                    }

                    val sensitivityScale = (1.30f - settings.wakeSensitivity.coerceIn(0.2f, 0.95f) * 0.50f)
                    val vadThreshold = (max(0.014f, noiseFloorRms * 2.25f) * sensitivityScale)
                        .coerceIn(0.012f, 0.085f)

                    if (!inSpeech) {
                        if (rms > vadThreshold) {
                            inSpeech = true
                            val preRoll = 3200 // ~200ms pre-roll
                            speechStartCursor = (ringCursor - read - preRoll + maxRingSamples) % maxRingSamples
                            speechSamplesCount = preRoll + read
                            silenceFrames = 0
                            peakUtteranceRms = rms
                        } else {
                            noiseFloorRms = (noiseFloorRms * 0.96f) + (rms * 0.04f)
                            CheetahRuntimeBus.updateDiagnostics {
                                it.copy(
                                    ambientNoiseRms = noiseFloorRms,
                                    detectionThreshold = wakeWordEngine.sensitivityToDtwThreshold(settings.wakeSensitivity)
                                )
                            }
                        }
                    } else {
                        speechSamplesCount += read
                        if (rms > peakUtteranceRms) peakUtteranceRms = rms

                        if (rms <= vadThreshold * 0.85f) {
                            silenceFrames++
                        } else {
                            silenceFrames = 0
                        }

                        val maxUtteranceSamples = (sampleRate * 2.2f).toInt()
                        if (silenceFrames >= 7 || speechSamplesCount >= maxUtteranceSamples) {
                            val capturedSamples = speechSamplesCount.coerceAtMost(maxRingSamples)
                            inSpeech = false
                            silenceFrames = 0
                            speechSamplesCount = 0

                            if (capturedSamples >= (sampleRate * 0.24f).toInt() &&
                                System.currentTimeMillis() > cooldownUntilMs
                            ) {
                                val utterance = ShortArray(capturedSamples)
                                for (i in 0 until capturedSamples) {
                                    utterance[i] = ringBuffer[(speechStartCursor + i) % maxRingSamples]
                                }

                                val isDiagnosticRun = System.currentTimeMillis() <= diagnosticTestUntilMs
                                val mfccCount = templateStore.samples.value.count { it.mfccSequence.isNotEmpty() }
                                val canRunAcousticModel = mfccCount > 0 ||
                                    (bundledModel != null && wakeWordEngine.isUsingDefaultYaSheetaPhrase(settings))

                                if (canRunAcousticModel) {
                                    val evalResult = wakeWordEngine.evaluate(
                                        pcmUtterance = utterance,
                                        noiseFloorRms = noiseFloorRms,
                                        settings = settings
                                    )
                                    CheetahRuntimeBus.updateDiagnostics {
                                        it.copy(
                                            lastConfidence = evalResult.wakeConfidence,
                                            lastVoiceMatchScore = evalResult.voiceMatchScore,
                                            lastVoiceMatchResult = when {
                                                evalResult.voiceMatchScore == null -> "غير متاح"
                                                evalResult.voiceMatchPassed -> "مطابق"
                                                evalResult.usedLowConfidenceFallback -> "بديل المالك الآمن"
                                                else -> "مرفوض"
                                            },
                                            lastAudioEvent = evalResult.diagnosticSummary,
                                            detectionThreshold = evalResult.thresholdUsed,
                                            ambientNoiseRms = noiseFloorRms,
                                            wakeEngineStatus = activeEngineLabel(settings)
                                        )
                                    }

                                    if (evalResult.detected && !isDiagnosticRun) {
                                        cooldownUntilMs = System.currentTimeMillis() + 3000L
                                        runCatching { rec.stop() }
                                        rec.release()
                                        recorder = null

                                        handleWakeAndCommandCycle(
                                            settings = settings,
                                            wakeConfidence = evalResult.wakeConfidence,
                                            voiceMatchScore = evalResult.voiceMatchScore,
                                            voiceMatchPassed = evalResult.voiceMatchPassed || evalResult.usedLowConfidenceFallback,
                                            sourceTag = evalResult.detectionSource,
                                            directCommandText = null
                                        )
                                        calibrationFrames = 0
                                    }
                                } else if (settings.wakeSetupMode == WakeWordSetupMode.TEXT_TYPED &&
                                    peakUtteranceRms > max(0.028f, noiseFloorRms * 3.0f) &&
                                    capturedSamples >= (sampleRate * 0.45f).toInt() &&
                                    !isDiagnosticRun
                                ) {
                                    cooldownUntilMs = System.currentTimeMillis() + 4500L
                                    runCatching { rec.stop() }
                                    rec.release()
                                    recorder = null

                                    verifyTextWakeViaStt(settings)
                                    calibrationFrames = 0
                                } else {
                                    CheetahRuntimeBus.updateDiagnostics {
                                        it.copy(
                                            lastAudioEvent = "سجل بصمة صوتية لكلمة التنبيه أو استخدم 'يا شيتا' للنموذج المدمج",
                                            ambientNoiseRms = noiseFloorRms
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                runCatching { recorder?.stop() }
                recorder?.release()
                CheetahRuntimeBus.setServiceRunning(false)
            }
        }
    }

    private suspend fun verifyTextWakeViaStt(settings: AssistantSettings) {
        CheetahRuntimeBus.updateDiagnostics {
            it.copy(
                audioRecordStatus = "PAUSED_FOR_STT",
                wakeEngineStatus = "VERIFYING_TEXT_WAKE",
                lastAudioEvent = "التحقق النصي من كلمة التنبيه..."
            )
        }
        val sttResult = sttManager.listenOnce(timeoutMs = 5_000L)
        val transcript = sttResult.getOrNull()?.trim().orEmpty()
        if (transcript.isEmpty()) {
            CheetahRuntimeBus.updateDiagnostics {
                it.copy(
                    lastAudioEvent = sttResult.exceptionOrNull()?.message ?: "لم يتم التعرف على كلمة التنبيه"
                )
            }
            return
        }

        if (!EgyptianPhoneticNormalizer.matchesWakePhrase(transcript, settings.wakePhrase, settings.wakeSensitivity)) {
            CheetahRuntimeBus.incrementFalseActivation()
            CheetahRuntimeBus.updateDiagnostics {
                it.copy(lastAudioEvent = "كلام عابر لم يطابق اسم التنبيه (${settings.wakePhrase})")
            }
            return
        }

        val trailingCommand = EgyptianPhoneticNormalizer.stripWakePhrase(transcript, settings.wakePhrase)
        val hasDirectCommand = trailingCommand.isNotBlank() &&
            EgyptianPhoneticNormalizer.normalizeForNlu(trailingCommand) !=
            EgyptianPhoneticNormalizer.normalizeForNlu(transcript)

        handleWakeAndCommandCycle(
            settings = settings,
            wakeConfidence = 0.85f,
            voiceMatchScore = null,
            voiceMatchPassed = true,
            sourceTag = "FOREGROUND_TEXT_STT_MATCH",
            directCommandText = if (hasDirectCommand) trailingCommand else null
        )
    }

    private suspend fun handleWakeAndCommandCycle(
        settings: AssistantSettings,
        wakeConfidence: Float,
        voiceMatchScore: Float?,
        voiceMatchPassed: Boolean,
        sourceTag: String,
        directCommandText: String?
    ) {
        vibratePulse()
        CheetahRuntimeBus.emitWakeTrigger(
            WakeTriggerEvent(
                timestampMs = System.currentTimeMillis(),
                wakeConfidence = wakeConfidence,
                voiceMatchScore = voiceMatchScore,
                passedVoiceMatch = voiceMatchPassed,
                source = sourceTag
            )
        )

        val commandText = if (!directCommandText.isNullOrBlank()) {
            directCommandText
        } else {
            CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.WAKE_ACKNOWLEDGED)
            ttsManager.speakAndAwait(
                text = settings.wakeAckPhrase,
                rate = settings.ttsSpeechRate,
                pitch = settings.ttsPitch,
                timeoutMs = 3_500L
            )
            delay(160L)

            CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.LISTENING_COMMAND)
            val sttResult = sttManager.listenOnce(
                timeoutMs = 9_500L,
                onRmsDb = { CheetahRuntimeBus.updateMicLevel(it) }
            )
            sttResult.getOrElse { err ->
                CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.ERROR)
                val errMsg = err.message ?: "ماسمعتش أمر واضح يا فندم."
                CheetahRuntimeBus.updateDiagnostics {
                    it.copy(lastError = errMsg)
                }
                ttsManager.speakAndAwait(
                    text = errMsg,
                    rate = settings.ttsSpeechRate,
                    pitch = settings.ttsPitch,
                    timeoutMs = 5_000L
                )
                delay(250L)
                CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.LISTENING_WAKE_WORD)
                return
            }
        }

        CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.THINKING_LOCAL)
        val perms = PermissionHealthChecker.inspect(applicationContext)
        val contextState = memoryStore.current()
        val (_, parsedIntent) = EgyptianColloquialNluParser.parse(
            rawInput = commandText,
            configuredWakePhrase = settings.wakePhrase,
            contextState = contextState
        )

        val verdict = CommandGuardrailValidator.validate(
            intent = parsedIntent,
            settings = settings,
            permissions = perms,
            voiceMatchPassed = voiceMatchPassed
        )

        val replyText: String = when (verdict) {
            is GuardrailVerdict.Denied -> {
                CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.BLOCKED_BY_GUARDRAIL)
                val reply = JarvisEgyptianResponder.formatGuardrailBlockReply(
                    verdict.reasonAr,
                    settings.personaTone
                )
                memoryStore.recordTurn(
                    userText = commandText,
                    assistantReply = reply,
                    intentCategory = parsedIntent.categoryName
                )
                reply
            }

            is GuardrailVerdict.Allowed -> {
                CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.EXECUTING_ACTION)
                val execResult = DeviceActionExecutor.execute(
                    context = applicationContext,
                    intent = verdict.intent,
                    tone = settings.personaTone,
                    memoryState = contextState
                )
                memoryStore.recordTurn(
                    userText = commandText,
                    assistantReply = execResult.replyAr,
                    intentCategory = verdict.intent.categoryName,
                    newAppContext = execResult.newAppContext,
                    newSearchQuery = execResult.newSearchQuery,
                    newOpenedApp = execResult.newOpenedApp
                )
                execResult.replyAr
            }
        }

        CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.SPEAKING)
        ttsManager.speakAndAwait(
            text = replyText,
            rate = settings.ttsSpeechRate,
            pitch = settings.ttsPitch,
            timeoutMs = 8_500L
        )
        delay(250L)
        CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.LISTENING_WAKE_WORD)
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
            Intent(this, WakeWordDetectionService::class.java).apply { action = ACTION_TOGGLE_MUTE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, WakeWordDetectionService::class.java).apply { action = ACTION_STOP },
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
        sttManager.stopAndRelease()
        ttsManager.release()
        bundledModel?.close()
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
        const val ACTION_RELOAD_TEMPLATES = "com.example.voice.action.RELOAD_TEMPLATES"
        const val ACTION_RUN_DIAGNOSTIC_TEST = "com.example.voice.action.RUN_DIAGNOSTIC_TEST"
        const val EXTRA_FROM_WAKE_WORD = "extra_from_wake_word"
        const val EXTRA_VOICE_MATCH_SCORE = "extra_voice_match_score"

        fun start(context: Context) {
            val intent = Intent(context, WakeWordDetectionService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WakeWordDetectionService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }

        fun toggleMute(context: Context) {
            val intent = Intent(context, WakeWordDetectionService::class.java).apply {
                action = ACTION_TOGGLE_MUTE
            }
            runCatching { context.startService(intent) }
        }

        fun reloadTemplates(context: Context) {
            val intent = Intent(context, WakeWordDetectionService::class.java).apply {
                action = ACTION_RELOAD_TEMPLATES
            }
            runCatching { context.startService(intent) }
        }

        fun runDiagnosticTest(context: Context) {
            val intent = Intent(context, WakeWordDetectionService::class.java).apply {
                action = ACTION_RUN_DIAGNOSTIC_TEST
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
