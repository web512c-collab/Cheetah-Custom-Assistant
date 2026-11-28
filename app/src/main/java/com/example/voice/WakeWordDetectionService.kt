package com.example.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
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
import com.example.core.model.AssistantGender
import com.example.core.model.AssistantPipelineState
import com.example.core.model.AssistantSettings
import com.example.core.model.WakePipelineState
import com.example.core.model.WakeWordSetupMode
import com.example.data.ConversationMemoryStore
import com.example.data.SettingsRepository
import com.example.data.VoiceTemplateStore
import com.example.intelligence.AssistantCommandProcessor
import com.example.intelligence.JarvisEgyptianResponder
import com.example.llm.LocalLlmManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.max

/**
 * Local Foreground Service (FOREGROUND_SERVICE_TYPE_MICROPHONE) that continuously monitors
 * 16kHz PCM microphone audio for the wake phrase even when minimized, swiped from recents,
 * or with screen off.
 *
 * Reliability architecture (BUG A1 & BUG A2):
 * - Deterministic 5-state machine (WakeStateMachine) logged to CheetahRuntimeBus.
 * - Per-state watchdog timer + try/finally cycle cleanup guarantees automatic return to IDLE_WAKE_LISTENING.
 * - Single-owner MicrophoneArbitrator prevents AudioRecord vs SpeechRecognizer hardware collisions.
 * - AudioRecord dead-object/zero-read recovery counter + supervisor loop restart + AlarmManager watchdog.
 */
class WakeWordDetectionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureSupervisorJob: Job? = null
    private var watchdogJob: Job? = null
    private var settingsCollectorJob: Job? = null

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var templateStore: VoiceTemplateStore
    private lateinit var memoryStore: ConversationMemoryStore
    private var bundledModel: BundledYaSheetaAcousticModel? = null
    private lateinit var wakeWordEngine: WakeWordEngine
    private lateinit var sttManager: EgyptianOfflineSttManager
    private lateinit var ttsManager: EgyptianTtsManager
    private lateinit var localLlmManager: LocalLlmManager
    private lateinit var commandProcessor: AssistantCommandProcessor

    private val stateMachine = WakeStateMachine { entry ->
        CheetahRuntimeBus.recordStateTransition(entry)
    }

    @Volatile
    private var cachedSettings: AssistantSettings = AssistantSettings()

    @Volatile
    private var lastAppliedTtsKey: Pair<AssistantGender, String>? = null

    @Volatile
    private var isMuted = false

    @Volatile
    private var diagnosticTestUntilMs = 0L

    @Volatile
    private var activeCycleJob: Job? = null

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
        localLlmManager = LocalLlmManager(applicationContext, settingsRepository)
        commandProcessor = AssistantCommandProcessor(applicationContext, memoryStore, localLlmManager)

        createNotificationChannel()

        settingsCollectorJob = serviceScope.launch {
            settingsRepository.settingsFlow
                .distinctUntilChanged()
                .collect { s ->
                    cachedSettings = s
                    isMuted = s.isListeningMuted
                    val voiceKey = s.voiceGender to s.selectedTtsVoiceName
                    if (voiceKey != lastAppliedTtsKey && !ttsManager.isSpeaking.value) {
                        lastAppliedTtsKey = voiceKey
                        ttsManager.applyVoicePreferences(s.voiceGender, s.selectedTtsVoiceName)
                    }
                    if (CheetahRuntimeBus.serviceRunning.value) {
                        runCatching { updateNotification(s) }
                    }
                    updateModelDiagnostics(s)
                }
        }

        if (modelResult.isFailure) {
            CheetahRuntimeBus.updateDiagnostics {
                it.copy(
                    lastError = "تعذر تحميل النموذج المدمج المساعد: ${modelResult.exceptionOrNull()?.message ?: "unknown"}"
                )
            }
        }
        updateModelDiagnostics(cachedSettings)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Call startForeground immediately on every start command entry so Android 12–16 never throws
        // ForegroundServiceDidNotStartInTimeException regardless of action or early bailout.
        val startedFg = runCatching {
            startForegroundCompat(cachedSettings)
            true
        }.getOrElse { err ->
            CheetahRuntimeBus.updateDiagnostics {
                it.copy(
                    audioRecordStatus = "FGS_RESTRICTED",
                    lastError = "تعذر تثبيت خدمة الخلفية من النظام: ${err.message ?: err.javaClass.simpleName}"
                )
            }
            false
        }

        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    settingsRepository.setContinuousListening(false)
                    WakeWatchdogReceiver.cancelWatchdog(applicationContext)
                    stopForegroundCompat()
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_MUTE -> {
                isMuted = !isMuted
                serviceScope.launch {
                    settingsRepository.setListeningMuted(isMuted)
                    val s = cachedSettings.copy(isListeningMuted = isMuted)
                    cachedSettings = s
                    updateNotification(s)
                }
                CheetahRuntimeBus.updateDiagnostics {
                    it.copy(
                        wakeEngineStatus = if (isMuted) "MUTED" else activeEngineLabel(cachedSettings),
                        lastAudioEvent = if (isMuted) "تم كتم المايك مؤقتاً" else "تم استئناف الاستماع لكلمة التنبيه"
                    )
                }
                return START_STICKY
            }

            ACTION_RELOAD_TEMPLATES -> {
                templateStore.reload()
                updateModelDiagnostics(cachedSettings)
                return START_STICKY
            }

            ACTION_RUN_DIAGNOSTIC_TEST -> {
                val windowMs = intent.getLongExtra(EXTRA_DIAGNOSTIC_DURATION_MS, 12_000L).coerceIn(5_000L, 60_000L)
                diagnosticTestUntilMs = System.currentTimeMillis() + windowMs
                CheetahRuntimeBus.updateDiagnostics {
                    it.copy(lastAudioEvent = "وضع الاختبار المباشر لكلمة التنبيه نشط — قل كلمة التنبيه الآن")
                }
                if (captureSupervisorJob?.isActive == true) {
                    return START_STICKY
                }
            }

            ACTION_CANCEL_DIAGNOSTIC_TEST -> {
                diagnosticTestUntilMs = 0L
                return START_STICKY
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            CheetahRuntimeBus.updateDiagnostics {
                it.copy(
                    audioRecordStatus = "PERMISSION_DENIED",
                    lastError = VoiceErrorMapper.mapFailure(
                        rawReason = "MIC_PERMISSION_DENIED",
                        hasMicPermission = false,
                        hasLocalSttModel = sttManager.isOnDeviceSupported()
                    ).messageAr
                )
            }
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startedFg) {
            WakeWatchdogReceiver.postRecoveryNotification(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }

        CheetahRuntimeBus.setServiceRunning(true)
        stateMachine.forceResetToIdle("service_started", isError = false)
        WakeWatchdogReceiver.schedulePeriodicWatchdog(applicationContext)
        startWatchdogLoop()
        startAcousticSupervisorLoop()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val appCtx = applicationContext
        serviceScope.launch {
            val s = settingsRepository.currentSettings()
            if (s.continuousListeningEnabled && !s.isListeningMuted) {
                WakeWatchdogReceiver.scheduleImmediateRestart(appCtx, delayMs = 1_200L)
            }
        }
    }

    private fun startWatchdogLoop() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(1_000L)
                val timedOut = stateMachine.checkWatchdog(llmActive = localLlmManager.isModelLoaded())
                if (timedOut) {
                    CheetahRuntimeBus.incrementWatchdogTimeouts()
                    CheetahRuntimeBus.updateDiagnostics {
                        it.copy(lastError = "Watchdog استعاد حالة الاستماع تلقائياً بعد انتهاء المهلة")
                    }
                    runCatching { sttManager.stopAndRelease() }
                    runCatching { ttsManager.stop() }
                    runCatching { localLlmManager.cancelGeneration() }
                    activeCycleJob?.cancel(CancellationException("State machine watchdog timeout"))
                }
            }
        }
    }

    private fun startAcousticSupervisorLoop() {
        if (captureSupervisorJob?.isActive == true) return
        captureSupervisorJob = serviceScope.launch {
            var crashRestarts = 0
            try {
                while (isActive) {
                    try {
                        runAcousticMonitorSession()
                        if (!isActive) break
                        delay(500L)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        crashRestarts++
                        CheetahRuntimeBus.incrementAudioRecordRecoveries()
                        CheetahRuntimeBus.updateDiagnostics {
                            it.copy(
                                audioRecordStatus = "RECOVERING",
                                lastError = "استعادة تلقائية بعد خطأ في حلقة الصوت (#$crashRestarts): ${t.message ?: "error"}"
                            )
                        }
                        stateMachine.forceResetToIdle("capture_supervisor_recover:$crashRestarts", isError = true)
                        delay((500L * crashRestarts.coerceAtMost(6)).coerceAtMost(3_000L))
                    }
                }
            } finally {
                MicrophoneArbitrator.releaseWakeAudioRecordIfHeld()
                CheetahRuntimeBus.setServiceRunning(false)
            }
        }
    }

    private suspend fun runAcousticMonitorSession() {
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
        var consecutiveReadErrors = 0
        var initFailCount = 0

        var consecutiveZeroRms = 0

        try {
            while (serviceScope.isActive) {
                val settings = cachedSettings
                isMuted = settings.isListeningMuted

                val shouldYield = isMuted ||
                    CheetahRuntimeBus.isSttActive.value ||
                    MicrophoneArbitrator.yieldWakeForForeground.value ||
                    stateMachine.currentState != WakePipelineState.IDLE_WAKE_LISTENING

                if (shouldYield) {
                    if (recorder != null) {
                        releaseRecorderSafely(recorder)
                        recorder = null
                    }
                    MicrophoneArbitrator.releaseWakeAudioRecordIfHeld()
                    CheetahRuntimeBus.updateMicLevel(0f)
                    CheetahRuntimeBus.updateDiagnostics {
                        it.copy(
                            audioRecordStatus = if (isMuted) "MUTED" else "YIELDED",
                            wakeEngineStatus = if (isMuted) "MUTED" else "HANDOFF"
                        )
                    }
                    delay(150L)
                    continue
                }

                if (recorder == null) {
                    if (!MicrophoneArbitrator.tryMarkWakeAudioRecordActive()) {
                        delay(120L)
                        continue
                    }
                    val (newRec, label) = openAudioRecord()
                    if (newRec == null) {
                        MicrophoneArbitrator.releaseWakeAudioRecordIfHeld()
                        initFailCount++
                        CheetahRuntimeBus.incrementAudioRecordRecoveries()
                        CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.ERROR)
                        CheetahRuntimeBus.updateDiagnostics {
                            it.copy(
                                audioRecordStatus = "INIT_FAILED",
                                audioSourceUsed = "NONE",
                                lastError = "تعذر تهيئة AudioRecord (محاولة #$initFailCount — قد يكون المايك مستخدماً)"
                            )
                        }
                        delay((1_000L * initFailCount.coerceAtMost(5)).coerceAtMost(5_000L))
                        continue
                    }

                    val startedOk = runCatching {
                        newRec.startRecording()
                        newRec.recordingState == AudioRecord.RECORDSTATE_RECORDING
                    }.getOrDefault(false)

                    if (!startedOk) {
                        releaseRecorderSafely(newRec)
                        MicrophoneArbitrator.releaseWakeAudioRecordIfHeld()
                        CheetahRuntimeBus.incrementAudioRecordRecoveries()
                        delay(800L)
                        continue
                    }

                    initFailCount = 0
                    consecutiveReadErrors = 0
                    recorder = newRec
                    audioSourceLabel = label
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

                val rec = recorder
                val read = runCatching { rec.read(chunk, 0, chunk.size) }.getOrElse { -1 }
                if (read <= 0) {
                    consecutiveReadErrors++
                    if (consecutiveReadErrors >= 4 || read == AudioRecord.ERROR_DEAD_OBJECT) {
                        releaseRecorderSafely(rec)
                        recorder = null
                        MicrophoneArbitrator.releaseWakeAudioRecordIfHeld()
                        CheetahRuntimeBus.incrementAudioRecordRecoveries()
                        CheetahRuntimeBus.updateDiagnostics {
                            it.copy(
                                audioRecordStatus = "RECOVERING",
                                lastAudioEvent = "استعادة AudioRecord بعد انقطاع القراءة (code=$read)"
                            )
                        }
                        calibrationFrames = 0
                        inSpeech = false
                        delay(350L)
                    } else {
                        delay(35L)
                    }
                    continue
                }
                consecutiveReadErrors = 0

                for (i in 0 until read) {
                    ringBuffer[ringCursor] = chunk[i]
                    ringCursor = (ringCursor + 1) % maxRingSamples
                }

                val rms = AudioFeatureExtractor.computeRms(chunk, read)
                CheetahRuntimeBus.updateMicLevel((rms * 5.0f).coerceIn(0f, 1f))

                if (rms < 1e-5f) {
                    consecutiveZeroRms++
                    if (consecutiveZeroRms == 40) {
                        CheetahRuntimeBus.updateDiagnostics {
                            it.copy(
                                audioRecordStatus = "BACKGROUND_MIC_BLOCKED",
                                lastError = VoiceErrorMapper.mapFailure(
                                    rawReason = "AUDIO_SILENCED",
                                    hasMicPermission = true,
                                    hasLocalSttModel = sttManager.isOnDeviceSupported(),
                                    isBackgroundContext = true
                                ).messageAr
                            )
                        }
                    }
                } else {
                    consecutiveZeroRms = 0
                }

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
                        val preRoll = 3200
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
                            val canRunAcousticModel = mfccCount > 0 || isDiagnosticRun

                            if (canRunAcousticModel) {
                                val evalResult = wakeWordEngine.evaluate(
                                    pcmUtterance = utterance,
                                    noiseFloorRms = noiseFloorRms,
                                    settings = settings
                                )
                                CheetahRuntimeBus.emitWakeEval(
                                    WakeEvalTelemetry(
                                        detected = evalResult.detected,
                                        confidence = evalResult.wakeConfidence,
                                        threshold = evalResult.thresholdUsed,
                                        source = evalResult.detectionSource,
                                        timestampMs = System.currentTimeMillis()
                                    )
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
                                    cooldownUntilMs = System.currentTimeMillis() + 2_500L
                                    releaseRecorderSafely(rec)
                                    recorder = null
                                    MicrophoneArbitrator.releaseWakeAudioRecordIfHeld()

                                    runGuardedWakeCycle(
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
                                cooldownUntilMs = System.currentTimeMillis() + 4_000L
                                releaseRecorderSafely(rec)
                                recorder = null
                                MicrophoneArbitrator.releaseWakeAudioRecordIfHeld()

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
            releaseRecorderSafely(recorder)
            MicrophoneArbitrator.releaseWakeAudioRecordIfHeld()
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
        val sttResult = MicrophoneArbitrator.withMicAccess(MicOwner.WAKE_TEXT_STT) {
            sttManager.listenOnce(timeoutMs = 5_000L)
        }
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

        runGuardedWakeCycle(
            settings = settings,
            wakeConfidence = 0.85f,
            voiceMatchScore = null,
            voiceMatchPassed = true,
            sourceTag = "FOREGROUND_TEXT_STT_MATCH",
            directCommandText = if (hasDirectCommand) trailingCommand else null
        )
    }

    /**
     * Executes a complete wake -> ack -> STT -> NLU/Guardrail/LLM/Search -> TTS cycle
     * wrapped in a hard timeout + try/finally cleanup so the service can NEVER freeze after one reply.
     */
    private suspend fun runGuardedWakeCycle(
        settings: AssistantSettings,
        wakeConfidence: Float,
        voiceMatchScore: Float?,
        voiceMatchPassed: Boolean,
        sourceTag: String,
        directCommandText: String?
    ) {
        val hasModelConfigured = localLlmManager.isModelLoaded() ||
            (settings.localLlmConfig.useAiForGeneral && localLlmManager.hasConfiguredModel(settings.localLlmConfig))
        val cycleBudgetMs = if (hasModelConfigured) {
            ((settings.localLlmConfig.maxThinkingSeconds.coerceIn(5, 120) + 28) * 1000L)
        } else {
            38_000L
        }
        val job = serviceScope.launch {
            try {
                withTimeout(cycleBudgetMs) {
                    executeWakeAndCommandCycle(
                        settings = settings,
                        wakeConfidence = wakeConfidence,
                        voiceMatchScore = voiceMatchScore,
                        voiceMatchPassed = voiceMatchPassed,
                        sourceTag = sourceTag,
                        directCommandText = directCommandText
                    )
                }
            } catch (ce: CancellationException) {
                CheetahRuntimeBus.updateDiagnostics {
                    it.copy(lastAudioEvent = "تم إلغاء دورة الأمر أو انتهت المهلة")
                }
                if (ce !is kotlinx.coroutines.TimeoutCancellationException && !serviceScope.isActive) {
                    throw ce
                }
            } catch (t: Throwable) {
                CheetahRuntimeBus.updateDiagnostics {
                    it.copy(lastError = "خطأ أثناء معالجة الأمر: ${t.message ?: "error"}")
                }
            } finally {
                runCatching { sttManager.stopAndRelease() }
                runCatching { ttsManager.stop() }
                CheetahRuntimeBus.setStreamingReplyText("")
                CheetahRuntimeBus.updateMicLevel(0f)
                stateMachine.forceResetToIdle("cycle_completed_cleanup", isError = false)
            }
        }
        activeCycleJob = job
        job.join()
        activeCycleJob = null
    }

    private suspend fun executeWakeAndCommandCycle(
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

        stateMachine.transition(WakePipelineState.WAKE_DETECTED, "wake_triggered:$sourceTag")

        val commandText: String = if (!directCommandText.isNullOrBlank()) {
            directCommandText
        } else {
            val ackText = JarvisEgyptianResponder.formatWakeAck(settings)
            ttsManager.speakAndAwait(
                text = ackText,
                rate = settings.ttsSpeechRate,
                pitch = settings.ttsPitch,
                voiceGender = settings.voiceGender,
                selectedVoiceName = settings.selectedTtsVoiceName,
                timeoutMs = 3_800L
            )
            delay(150L)

            stateMachine.transition(WakePipelineState.COMMAND_LISTENING, "listening_for_command_stt")
            val sttResult = MicrophoneArbitrator.withMicAccess(MicOwner.WAKE_COMMAND_STT) {
                sttManager.listenOnce(
                    timeoutMs = 10_000L,
                    onRmsDb = { CheetahRuntimeBus.updateMicLevel(it) }
                )
            }

            val captured = sttResult.getOrNull()?.trim()
            if (captured.isNullOrEmpty()) {
                val rawErr = sttResult.exceptionOrNull()?.message
                val mapped = VoiceErrorMapper.mapFailure(
                    rawReason = rawErr,
                    hasMicPermission = ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED,
                    hasLocalSttModel = sttManager.isOnDeviceSupported(),
                    isBackgroundContext = true
                )
                CheetahRuntimeBus.updateDiagnostics { it.copy(lastError = mapped.messageAr) }
                stateMachine.transition(WakePipelineState.SPEAKING, "speak_stt_retry_prompt", isError = true)
                ttsManager.speakAndAwait(
                    text = mapped.messageAr,
                    rate = settings.ttsSpeechRate,
                    pitch = settings.ttsPitch,
                    voiceGender = settings.voiceGender,
                    selectedVoiceName = settings.selectedTtsVoiceName,
                    timeoutMs = 5_000L
                )
                return
            }
            captured
        }

        stateMachine.transition(WakePipelineState.PROCESSING, "process_command")
        var streamedChars = 0
        var streamedSentences = 0
        val outcome = commandProcessor.process(
            rawInput = commandText,
            settings = settings,
            voiceMatchPassed = voiceMatchPassed,
            voiceMatchScore = voiceMatchScore,
            clipboardTextProvider = {
                runCatching {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.primaryClip?.getItemAt(0)?.coerceToText(applicationContext)?.toString()
                }.getOrNull()
            },
            onStopSpeech = {
                runCatching { ttsManager.stop() }
            },
            onStreamingSentence = { sentence, isFirst ->
                if (streamedSentences < 3 && streamedChars < 240) {
                    streamedSentences++
                    streamedChars += sentence.length
                    ttsManager.enqueueStreamingSentence(
                        sentence = sentence,
                        rate = settings.ttsSpeechRate,
                        pitch = settings.ttsPitch,
                        voiceGender = settings.voiceGender,
                        selectedVoiceName = settings.selectedTtsVoiceName,
                        isFirstChunk = isFirst
                    )
                }
            }
        )

        stateMachine.transition(WakePipelineState.SPEAKING, "speak_reply:${outcome.intentCategory}")
        if (outcome.wasInterrupted) {
            ttsManager.speakAndAwait(
                text = outcome.spokenReplyAr,
                rate = settings.ttsSpeechRate,
                pitch = settings.ttsPitch,
                voiceGender = settings.voiceGender,
                selectedVoiceName = settings.selectedTtsVoiceName,
                timeoutMs = 3_500L
            )
        } else if (outcome.streamedViaTts) {
            if (outcome.hasContinuation) {
                ttsManager.enqueueStreamingSentence(
                    sentence = "تحب أكمل الباقي؟",
                    rate = settings.ttsSpeechRate,
                    pitch = settings.ttsPitch,
                    voiceGender = settings.voiceGender,
                    selectedVoiceName = settings.selectedTtsVoiceName,
                    isFirstChunk = false
                )
            }
            val waitDeadline = System.currentTimeMillis() + 12_000L
            while (ttsManager.isSpeaking.value && System.currentTimeMillis() < waitDeadline) {
                delay(180L)
            }
        } else {
            ttsManager.speakAndAwait(
                text = outcome.spokenReplyAr,
                rate = settings.ttsSpeechRate,
                pitch = settings.ttsPitch,
                voiceGender = settings.voiceGender,
                selectedVoiceName = settings.selectedTtsVoiceName,
                timeoutMs = 12_000L
            )
        }
        delay(220L)
    }

    private fun releaseRecorderSafely(recorder: AudioRecord?) {
        if (recorder == null) return
        runCatching {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        }
        runCatching { recorder.release() }
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

    private fun buildModelStatusLabel(): String {
        val mfccCount = templateStore.samples.value.count { it.mfccSequence.isNotEmpty() }
        val priorTag = if (bundledModel != null) " + CHKW-v1 مساعد (≤10%)" else ""
        return "$mfccCount/${VoiceTemplateStore.MIN_REQUIRED_SAMPLES} عينات MFCC-DTW$priorTag"
    }

    private fun activeEngineLabel(settings: AssistantSettings): String {
        val mfccCount = templateStore.samples.value.count { it.mfccSequence.isNotEmpty() }
        val usingPrior = bundledModel != null && wakeWordEngine.isUsingDefaultYaSheetaPhrase(settings)
        return when {
            mfccCount >= VoiceTemplateStore.MIN_REQUIRED_SAMPLES && usingPrior -> "MFCC-DTW (أساسي) + CHKW"
            mfccCount > 0 -> "MFCC-DTW ($mfccCount/${VoiceTemplateStore.MIN_REQUIRED_SAMPLES})"
            settings.wakeSetupMode == WakeWordSetupMode.TEXT_TYPED -> "TEXT_STT_GATE"
            else -> "NEEDS_5_SAMPLES"
        }
    }

    private fun updateModelDiagnostics(settings: AssistantSettings) {
        CheetahRuntimeBus.updateDiagnostics {
            it.copy(
                modelStatus = buildModelStatusLabel(),
                wakeEngineStatus = if (isMuted) "MUTED" else activeEngineLabel(settings),
                detectionThreshold = wakeWordEngine.sensitivityToDtwThreshold(settings.wakeSensitivity)
            )
        }
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

    private fun startForegroundCompat(settings: AssistantSettings) {
        val notification = buildNotification(settings)
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

    private fun updateNotification(settings: AssistantSettings = AssistantSettings()) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(settings))
    }

    private fun buildNotification(settings: AssistantSettings): Notification {
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

        val name = settings.assistantName.ifBlank { "شيتا" }
        val listeningVerb = if (settings.assistantGender == AssistantGender.FEMALE) "مستعدة وبتسمع" else "مستعد وبيسمع"
        val title = if (isMuted) "$name في وضع الكتم المؤقت" else "$name $listeningVerb محلياً"
        val body = if (isMuted) {
            "المايك مكتوم مؤقتاً — اضغط لإلغاء الكتم."
        } else {
            "الاستماع أوفلاين لكلمة «${settings.wakePhrase}» في الخلفية."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sheeta)
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
        activeCycleJob?.cancel()
        watchdogJob?.cancel()
        captureSupervisorJob?.cancel()
        settingsCollectorJob?.cancel()
        MicrophoneArbitrator.releaseWakeAudioRecordIfHeld()
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
        const val ACTION_CANCEL_DIAGNOSTIC_TEST = "com.example.voice.action.CANCEL_DIAGNOSTIC_TEST"
        const val EXTRA_DIAGNOSTIC_DURATION_MS = "extra_diagnostic_duration_ms"
        const val EXTRA_FROM_WAKE_WORD = "extra_from_wake_word"
        const val EXTRA_VOICE_MATCH_SCORE = "extra_voice_match_score"

        fun start(context: Context): Boolean {
            val intent = Intent(context, WakeWordDetectionService::class.java).apply {
                action = ACTION_START
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                CheetahRuntimeBus.updateDiagnostics {
                    it.copy(
                        audioRecordStatus = "FGS_RESTRICTED",
                        lastError = "ForegroundServiceStartNotAllowedException: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
                WakeWatchdogReceiver.postRecoveryNotification(context.applicationContext)
                false
            }
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

        fun runDiagnosticTest(context: Context, durationMs: Long = 12_000L): Boolean {
            val intent = Intent(context, WakeWordDetectionService::class.java).apply {
                action = ACTION_RUN_DIAGNOSTIC_TEST
                putExtra(EXTRA_DIAGNOSTIC_DURATION_MS, durationMs)
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                CheetahRuntimeBus.updateDiagnostics {
                    it.copy(lastError = "تعذر تشغيل اختبار الخلفية: ${e.message ?: e.javaClass.simpleName}")
                }
                false
            }
        }

        fun cancelDiagnosticTest(context: Context) {
            val intent = Intent(context, WakeWordDetectionService::class.java).apply {
                action = ACTION_CANCEL_DIAGNOSTIC_TEST
            }
            runCatching { context.startService(intent) }
        }
    }
}
