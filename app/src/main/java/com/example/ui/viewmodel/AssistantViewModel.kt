package com.example.ui.viewmodel

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.controller.DeviceActionExecutor
import com.example.controller.PermissionHealthChecker
import com.example.core.model.AiLanguagePreference
import com.example.core.model.AiMode
import com.example.core.model.AiPreset
import com.example.core.model.AssistantGender
import com.example.core.model.AssistantPipelineState
import com.example.core.model.AssistantSettings
import com.example.core.model.ChatTemplateFamily
import com.example.core.model.ChatTemplateOverride
import com.example.core.model.ConcreteSearchEngine
import com.example.core.model.ConversationContextState
import com.example.core.model.EndLineRule
import com.example.core.model.GgufModelMetadata
import com.example.core.model.GuardrailVerdict
import com.example.core.model.InteractionTrace
import com.example.core.model.LocalLlmConfig
import com.example.core.model.LocalLlmRuntimeStatus
import com.example.core.model.LocalLlmStorageMode
import com.example.core.model.PerformanceProfile
import com.example.core.model.PermissionHealthState
import com.example.core.model.PersonaTone
import com.example.core.model.ProposedAction
import com.example.core.model.ResponseStyle
import com.example.core.model.SearchEngineMode
import com.example.core.model.SearchEngineTestResult
import com.example.core.model.TransitionLogEntry
import com.example.core.model.VoiceTemplateSample
import com.example.core.model.WakeDiagnosticSnapshot
import com.example.core.model.WakeWordSetupMode
import com.example.data.ConversationMemoryStore
import com.example.data.SettingsRepository
import com.example.data.VoiceTemplateStore
import com.example.guardrails.CommandGuardrailValidator
import com.example.intelligence.AssistantCommandProcessor
import com.example.intelligence.ReasoningOutputParser
import com.example.llm.LocalLlmManager
import com.example.search.OnlineSearchManager
import com.example.voice.BundledYaSheetaAcousticModel
import com.example.voice.CheetahRuntimeBus
import com.example.voice.EgyptianOfflineSttManager
import com.example.voice.EgyptianTtsManager
import com.example.voice.EnrollmentCaptureResult
import com.example.voice.MappedVoiceError
import com.example.voice.MicOwner
import com.example.voice.MicrophoneArbitrator
import com.example.voice.OfflineSttStatus
import com.example.voice.TtsStatus
import com.example.voice.VoiceErrorMapper
import com.example.voice.WakeCalibrationResult
import com.example.voice.WakeThresholdCalibrator
import com.example.voice.WakeWordDetectionService
import com.example.voice.WakeWordEnrollmentRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val settingsRepo = SettingsRepository(appContext)
    private val templateStore = VoiceTemplateStore(appContext)
    private val memoryStore = ConversationMemoryStore(appContext)
    private val enrollmentRecorder = WakeWordEnrollmentRecorder(appContext)
    private val sttManager = EgyptianOfflineSttManager(appContext)
    private val ttsManager = EgyptianTtsManager(appContext)
    private val localLlmManager = LocalLlmManager(appContext, settingsRepo)
    private val commandProcessor = AssistantCommandProcessor(appContext, memoryStore, localLlmManager)

    private var activeCommandJob: Job? = null
    private var activeVoiceJob: Job? = null

    val settings: StateFlow<AssistantSettings> = settingsRepo.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000L),
        AssistantSettings()
    )

    val rules: StateFlow<List<EndLineRule>> = settingsRepo.rulesFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000L),
        EndLineRule.defaultRules()
    )

    val enrolledSamples: StateFlow<List<VoiceTemplateSample>> = templateStore.samples
    val memoryState: StateFlow<ConversationContextState> = memoryStore.state

    val ttsStatus: StateFlow<TtsStatus> = ttsManager.status
    val serviceRunning: StateFlow<Boolean> = CheetahRuntimeBus.serviceRunning
    val liveMicLevel: StateFlow<Float> = CheetahRuntimeBus.liveMicLevel
    val pipelineState: StateFlow<AssistantPipelineState> = CheetahRuntimeBus.pipelineState
    val lastVoiceMatchScore: StateFlow<Float?> = CheetahRuntimeBus.lastVoiceMatchScore
    val diagnostics: StateFlow<WakeDiagnosticSnapshot> = CheetahRuntimeBus.diagnostics
    val transitionLog: StateFlow<List<TransitionLogEntry>> = CheetahRuntimeBus.transitionLog
    val localLlmStatus: StateFlow<LocalLlmRuntimeStatus> = CheetahRuntimeBus.localLlmStatus
    val streamingReplyText: StateFlow<String> = CheetahRuntimeBus.streamingReplyText
    val isNetworkSearching: StateFlow<Boolean> = CheetahRuntimeBus.isNetworkSearching
    val pendingContinuationText: StateFlow<String?> = CheetahRuntimeBus.pendingContinuationText
    val pendingProposedAction: StateFlow<ProposedAction?> = CheetahRuntimeBus.pendingProposedAction
    val incomingSharedText: StateFlow<String?> = CheetahRuntimeBus.incomingSharedText

    private val _isBenchmarkRunning = MutableStateFlow(false)
    val isBenchmarkRunning: StateFlow<Boolean> = _isBenchmarkRunning.asStateFlow()

    private val _isTestingSearch = MutableStateFlow(false)
    val isTestingSearch: StateFlow<Boolean> = _isTestingSearch.asStateFlow()

    private val _searchTestResult = MutableStateFlow<SearchEngineTestResult?>(null)
    val searchTestResult: StateFlow<SearchEngineTestResult?> = _searchTestResult.asStateFlow()

    private val _voiceErrorBanner = MutableStateFlow<MappedVoiceError?>(null)
    val voiceErrorBanner: StateFlow<MappedVoiceError?> = _voiceErrorBanner.asStateFlow()

    private val _isImportingSttModel = MutableStateFlow(false)
    val isImportingSttModel: StateFlow<Boolean> = _isImportingSttModel.asStateFlow()

    private val _sttImportFeedbackAr = MutableStateFlow<String?>(null)
    val sttImportFeedbackAr: StateFlow<String?> = _sttImportFeedbackAr.asStateFlow()

    val wakeCalibration: StateFlow<WakeCalibrationResult> = combine(
        enrolledSamples,
        settingsRepo.settingsFlow
    ) { samples, s ->
        WakeThresholdCalibrator.calibrate(samples, s.wakeSensitivity)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000L),
        WakeThresholdCalibrator.calibrate(templateStore.samples.value, AssistantSettings().wakeSensitivity)
    )

    private val _wakeTestState = MutableStateFlow(WakeTestSessionState())
    val wakeTestState: StateFlow<WakeTestSessionState> = _wakeTestState.asStateFlow()

    private val _selfTestState = MutableStateFlow(DiagnosticSelfTestState())
    val selfTestState: StateFlow<DiagnosticSelfTestState> = _selfTestState.asStateFlow()

    private var wakeTestStartedService = false

    private val _offlineSttStatus = MutableStateFlow(sttManager.inspectCapability())
    val offlineSttStatus: StateFlow<OfflineSttStatus> = _offlineSttStatus.asStateFlow()

    private val _permissionHealth = MutableStateFlow(PermissionHealthChecker.inspect(appContext))
    val permissionHealth: StateFlow<PermissionHealthState> = _permissionHealth.asStateFlow()

    private val _isRecordingEnrollment = MutableStateFlow(false)
    val isRecordingEnrollment: StateFlow<Boolean> = _isRecordingEnrollment.asStateFlow()

    private val _enrollmentAmplitude = MutableStateFlow(0f)
    val enrollmentAmplitude: StateFlow<Float> = _enrollmentAmplitude.asStateFlow()

    private val _enrollmentFeedbackAr = MutableStateFlow<String?>(null)
    val enrollmentFeedbackAr: StateFlow<String?> = _enrollmentFeedbackAr.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _traces = MutableStateFlow<List<InteractionTrace>>(emptyList())
    val traces: StateFlow<List<InteractionTrace>> = _traces.asStateFlow()

    private val _pendingGgufImport = MutableStateFlow<Pair<Uri, GgufModelMetadata>?>(null)
    val pendingGgufImport: StateFlow<Pair<Uri, GgufModelMetadata>?> = _pendingGgufImport.asStateFlow()

    init {
        viewModelScope.launch {
            val bundledRes = BundledYaSheetaAcousticModel.loadFromAssets(appContext)
            val bundled = bundledRes.getOrNull()
            val mfccCount = templateStore.samples.value.count { it.mfccSequence.isNotEmpty() }
            CheetahRuntimeBus.updateDiagnostics { snap ->
                snap.copy(
                    modelStatus = if (bundled != null) {
                        "$mfccCount/${VoiceTemplateStore.MIN_REQUIRED_SAMPLES} MFCC-DTW + CHKW-v1 مساعد (≤10%)"
                    } else {
                        "$mfccCount/${VoiceTemplateStore.MIN_REQUIRED_SAMPLES} MFCC-DTW"
                    },
                    lastError = bundledRes.exceptionOrNull()?.let { "تعذر تحميل النموذج المدمج: ${it.message}" } ?: snap.lastError
                )
            }
            bundled?.close()
            val initialSettings = settingsRepo.currentSettings()
            localLlmManager.refreshStorageAndMemoryTelemetry(initialSettings.localLlmConfig)
            _offlineSttStatus.value = sttManager.inspectCapability(
                configuredDir = initialSettings.sttModelDir,
                configuredName = initialSettings.sttModelName,
                configuredSizeBytes = initialSettings.sttModelSizeBytes,
                configuredLang = initialSettings.sttModelLanguage
            )
        }

        // Sync TTS voice selection, STT model status, and LLM telemetry whenever settings change
        viewModelScope.launch {
            settingsRepo.settingsFlow
                .distinctUntilChanged()
                .collect { s ->
                    ttsManager.applyVoicePreferences(s.voiceGender, s.selectedTtsVoiceName)
                    localLlmManager.refreshStorageAndMemoryTelemetry(s.localLlmConfig)
                    _offlineSttStatus.value = sttManager.inspectCapability(
                        configuredDir = s.sttModelDir,
                        configuredName = s.sttModelName,
                        configuredSizeBytes = s.sttModelSizeBytes,
                        configuredLang = s.sttModelLanguage
                    )
                }
        }

        // Track 10-say wake word live test session attempts
        viewModelScope.launch {
            CheetahRuntimeBus.wakeEvalEvents.collect { eval ->
                val cur = _wakeTestState.value
                if (!cur.isActive) return@collect
                val nextAttempts = cur.attempts + 1
                val nextHits = cur.hits + if (eval.detected) 1 else 0
                val finished = nextAttempts >= cur.targetCount
                val summary = if (finished) {
                    val threshPct = (eval.threshold * 100).toInt()
                    if (nextHits >= 8) {
                        "نتيجة ممتازة ($nextHits/${cur.targetCount}) على عتبة $threshPct%."
                    } else {
                        "تم رصد $nextHits من ${cur.targetCount} محاولات على عتبة $threshPct% — جرّب رفع الحساسية أو إعادة تسجيل العينات الضعيفة."
                    }
                } else null

                _wakeTestState.value = cur.copy(
                    isActive = !finished,
                    attempts = nextAttempts,
                    hits = nextHits,
                    lastConfidence = eval.confidence,
                    lastThreshold = eval.threshold,
                    lastSource = eval.source,
                    summaryAr = summary
                )
                if (finished) {
                    WakeWordDetectionService.cancelDiagnosticTest(appContext)
                    if (wakeTestStartedService && !settings.value.continuousListeningEnabled) {
                        WakeWordDetectionService.stop(appContext)
                    }
                    wakeTestStartedService = false
                }
            }
        }
    }

    fun refreshPermissions() {
        val inspected = PermissionHealthChecker.inspect(appContext)
        _permissionHealth.value = inspected
        val s = settings.value
        _offlineSttStatus.value = sttManager.inspectCapability(
            configuredDir = s.sttModelDir,
            configuredName = s.sttModelName,
            configuredSizeBytes = s.sttModelSizeBytes,
            configuredLang = s.sttModelLanguage
        )
        localLlmManager.refreshStorageAndMemoryTelemetry(s.localLlmConfig)
        if (!inspected.hasMicPermission && serviceRunning.value) {
            WakeWordDetectionService.stop(appContext)
            viewModelScope.launch { settingsRepo.setContinuousListening(false) }
        }
    }

    fun dismissVoiceError() {
        _voiceErrorBanner.value = null
    }

    fun startVoiceCommand() {
        refreshPermissions()
        val hasMic = _permissionHealth.value.hasMicPermission
        val hasModel = sttManager.isOnDeviceSupported()
        if (!hasMic) {
            val mapped = VoiceErrorMapper.mapFailure(
                rawReason = "MIC_PERMISSION_DENIED",
                hasMicPermission = false,
                hasLocalSttModel = hasModel
            )
            _voiceErrorBanner.value = mapped
            CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.ERROR)
            _partialTranscript.value = mapped.messageAr
            return
        }
        if (!hasModel) {
            val mapped = VoiceErrorMapper.mapFailure(
                rawReason = "NO_STT_MODEL",
                hasMicPermission = true,
                hasLocalSttModel = false
            )
            _voiceErrorBanner.value = mapped
            _partialTranscript.value = mapped.messageAr
            return
        }
        if (activeVoiceJob?.isActive == true) return

        _voiceErrorBanner.value = null
        activeVoiceJob = viewModelScope.launch {
            CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.LISTENING_COMMAND)
            _partialTranscript.value = "سامعك بالعامية المصرية (Vosk أوفلاين)..."
            val sttResult = MicrophoneArbitrator.withMicAccess(MicOwner.UI_PTT_STT) {
                sttManager.listenOnce(
                    timeoutMs = 10_000L,
                    onRmsDb = { level -> CheetahRuntimeBus.updateMicLevel(level) },
                    onPartialResult = { partial -> _partialTranscript.value = partial }
                )
            }
            sttResult.fold(
                onSuccess = { finalText ->
                    _voiceErrorBanner.value = null
                    _partialTranscript.value = finalText
                    processCommand(finalText, voiceMatchPassed = true)
                },
                onFailure = { err ->
                    val mapped = VoiceErrorMapper.mapFailure(
                        rawReason = err.message,
                        hasMicPermission = _permissionHealth.value.hasMicPermission,
                        hasLocalSttModel = sttManager.isOnDeviceSupported()
                    )
                    _voiceErrorBanner.value = mapped
                    _partialTranscript.value = mapped.messageAr
                    CheetahRuntimeBus.updatePipelineState(
                        if (serviceRunning.value) AssistantPipelineState.LISTENING_WAKE_WORD
                        else AssistantPipelineState.IDLE
                    )
                }
            )
        }
    }

    fun submitTextCommand(rawCommand: String) {
        submitTextWithAttachment(rawCommand, attachedText = null)
    }

    fun submitTextWithAttachment(rawCommand: String, attachedText: String?) {
        val trimmed = rawCommand.trim()
        if (trimmed.isEmpty()) return
        _partialTranscript.value = trimmed
        processCommand(trimmed, voiceMatchPassed = true, attachedText = attachedText)
    }

    fun readClipboardText(): String? {
        return runCatching {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun consumeIncomingSharedText() {
        CheetahRuntimeBus.setIncomingSharedText(null)
    }

    private fun processCommand(
        rawText: String,
        voiceMatchPassed: Boolean,
        attachedText: String? = null
    ) {
        activeCommandJob?.cancel()
        localLlmManager.cancelGeneration()

        activeCommandJob = viewModelScope.launch {
            CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.THINKING_LOCAL)
            val currentSettings = settingsRepo.currentSettings()
            var streamedSentences = 0
            var streamedChars = 0
            val outcome = commandProcessor.process(
                rawInput = rawText,
                settings = currentSettings,
                voiceMatchPassed = voiceMatchPassed,
                voiceMatchScore = lastVoiceMatchScore.value,
                attachedText = attachedText,
                clipboardTextProvider = { readClipboardText() },
                onStopSpeech = { ttsManager.stop() },
                onStreamingSentence = { sentence, isFirst ->
                    if (streamedSentences < 3 && streamedChars < 240) {
                        streamedSentences++
                        streamedChars += sentence.length
                        ttsManager.enqueueStreamingSentence(
                            sentence = sentence,
                            rate = currentSettings.ttsSpeechRate,
                            pitch = currentSettings.ttsPitch,
                            voiceGender = currentSettings.voiceGender,
                            selectedVoiceName = currentSettings.selectedTtsVoiceName,
                            isFirstChunk = isFirst
                        )
                    }
                }
            )

            prependTrace(outcome.trace)
            if (outcome.isBlocked) {
                CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.BLOCKED_BY_GUARDRAIL)
            }

            if (outcome.wasInterrupted) {
                speakReply(outcome.spokenReplyAr, currentSettings)
            } else if (!outcome.streamedViaTts) {
                speakReply(outcome.spokenReplyAr, currentSettings)
            } else {
                if (outcome.hasContinuation) {
                    ttsManager.enqueueStreamingSentence(
                        sentence = "تحب أكمل الباقي؟",
                        rate = currentSettings.ttsSpeechRate,
                        pitch = currentSettings.ttsPitch,
                        voiceGender = currentSettings.voiceGender,
                        selectedVoiceName = currentSettings.selectedTtsVoiceName,
                        isFirstChunk = false
                    )
                }
                CheetahRuntimeBus.updatePipelineState(
                    if (serviceRunning.value) AssistantPipelineState.LISTENING_WAKE_WORD
                    else AssistantPipelineState.IDLE
                )
            }
        }
    }

    fun continueSpeakingLongReply() {
        val remaining = CheetahRuntimeBus.pendingContinuationText.value ?: return
        val speech = ReasoningOutputParser.buildConciseSpeechVersion(remaining)
        CheetahRuntimeBus.setPendingContinuationText(if (speech.hasContinuation) speech.remainingText else null)
        speakReply(speech.spokenText, settings.value)
    }

    fun confirmProposedAction(action: ProposedAction) {
        CheetahRuntimeBus.setPendingProposedAction(null)
        viewModelScope.launch {
            val s = settingsRepo.currentSettings()
            val perms = PermissionHealthChecker.inspect(appContext)
            val verdict = CommandGuardrailValidator.validate(
                intent = action.intent,
                settings = s,
                permissions = perms,
                voiceMatchPassed = true
            )
            val timeLabel = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            when (verdict) {
                is GuardrailVerdict.Denied -> {
                    prependTrace(
                        InteractionTrace(
                            rawUtterance = action.descriptionAr,
                            normalizedPhonetics = "PROPOSED_ACTION_CONFIRMED",
                            intentLabel = action.intent.categoryName,
                            guardrailStatus = "BLOCKED (${verdict.ruleId})",
                            isBlocked = true,
                            assistantReplyAr = verdict.reasonAr,
                            timestampFormatted = timeLabel
                        )
                    )
                    speakReply(verdict.reasonAr, s)
                }
                is GuardrailVerdict.Allowed -> {
                    val execRes = DeviceActionExecutor.execute(
                        context = appContext,
                        intent = verdict.intent,
                        tone = s.personaTone,
                        memoryState = memoryStore.current(),
                        assistantName = s.assistantName,
                        assistantGender = s.assistantGender
                    )
                    memoryStore.recordTurn(
                        userText = "تأكيد إجراء مقترح: ${action.descriptionAr}",
                        assistantReply = execRes.replyAr,
                        intentCategory = verdict.intent.categoryName,
                        newAppContext = execRes.newAppContext,
                        newSearchQuery = execRes.newSearchQuery,
                        newOpenedApp = execRes.newOpenedApp
                    )
                    prependTrace(
                        InteractionTrace(
                            rawUtterance = "تأكيد: ${action.descriptionAr}",
                            normalizedPhonetics = "PROPOSED_ACTION_CONFIRMED",
                            intentLabel = verdict.intent.categoryName,
                            guardrailStatus = "CONFIRMED_ALLOWLIST",
                            isBlocked = false,
                            assistantReplyAr = execRes.replyAr,
                            timestampFormatted = timeLabel
                        )
                    )
                    speakReply(execRes.replyAr, s)
                }
            }
        }
    }

    fun dismissProposedAction() {
        CheetahRuntimeBus.setPendingProposedAction(null)
    }

    fun cancelLlmGeneration() {
        activeCommandJob?.cancel()
        localLlmManager.cancelGeneration()
        CheetahRuntimeBus.setStreamingReplyText("")
        ttsManager.stop()
        CheetahRuntimeBus.updatePipelineState(
            if (serviceRunning.value) AssistantPipelineState.LISTENING_WAKE_WORD
            else AssistantPipelineState.IDLE
        )
    }

    private fun speakReply(text: String, s: AssistantSettings) {
        CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.SPEAKING)
        ttsManager.speak(
            text = text,
            rate = s.ttsSpeechRate,
            pitch = s.ttsPitch,
            voiceGender = s.voiceGender,
            selectedVoiceName = s.selectedTtsVoiceName
        ) {
            CheetahRuntimeBus.updatePipelineState(
                if (serviceRunning.value) AssistantPipelineState.LISTENING_WAKE_WORD
                else AssistantPipelineState.IDLE
            )
        }
    }

    private fun prependTrace(trace: InteractionTrace) {
        _traces.value = (listOf(trace) + _traces.value).take(25)
    }

    fun toggleContinuousListening(enable: Boolean) {
        viewModelScope.launch {
            refreshPermissions()
            if (enable && !_permissionHealth.value.hasMicPermission) {
                _enrollmentFeedbackAr.value = "مطلوب صلاحية المايكروفون لتشغيل الاستماع المستمر."
                return@launch
            }
            if (enable) {
                val samplesCount = templateStore.samples.value.count { it.mfccSequence.isNotEmpty() }
                if (settings.value.wakeSetupMode != WakeWordSetupMode.TEXT_TYPED &&
                    samplesCount < VoiceTemplateStore.MIN_REQUIRED_SAMPLES
                ) {
                    _enrollmentFeedbackAr.value = "تنبيه: مسجل حالياً $samplesCount/${VoiceTemplateStore.MIN_REQUIRED_SAMPLES} عينات — يُفضل إكمال ${VoiceTemplateStore.MIN_REQUIRED_SAMPLES} عينات لثبات الكشف."
                }
                val started = WakeWordDetectionService.start(appContext)
                if (started) {
                    settingsRepo.setContinuousListening(true)
                } else {
                    settingsRepo.setContinuousListening(false)
                    _enrollmentFeedbackAr.value = "نظام أندرويد منع بدء خدمة الخلفية حالياً — تأكد من استثناء البطارية أو افتح الشاشة وأعد المحاولة."
                }
            } else {
                settingsRepo.setContinuousListening(false)
                WakeWordDetectionService.stop(appContext)
            }
        }
    }

    fun toggleMute() {
        viewModelScope.launch {
            val next = !settings.value.isListeningMuted
            settingsRepo.setListeningMuted(next)
            if (serviceRunning.value) {
                WakeWordDetectionService.toggleMute(appContext)
            }
        }
    }

    fun markMicPermissionRequestedOnce() {
        viewModelScope.launch {
            settingsRepo.setPermissionAskedOnce(micAskedOnce = true)
        }
    }

    fun markNotifPermissionRequestedOnce() {
        viewModelScope.launch {
            settingsRepo.setPermissionAskedOnce(notifAskedOnce = true)
        }
    }

    fun dismissAutoRevokeTip() {
        viewModelScope.launch {
            settingsRepo.dismissAutoRevokeTip()
        }
    }

    fun importSttModelZip(uri: Uri) {
        if (_isImportingSttModel.value) return
        viewModelScope.launch {
            _isImportingSttModel.value = true
            _sttImportFeedbackAr.value = "جاري فك ضغط والتحقق من نموذج الكلام (Vosk)..."
            try {
                val res = sttManager.importModelFromUri(uri)
                res.fold(
                    onSuccess = { imported ->
                        settingsRepo.updateLocalSttModel(
                            modelDir = imported.modelDir,
                            displayName = imported.displayName,
                            sizeBytes = imported.sizeBytes,
                            languageTag = imported.languageTag
                        )
                        _offlineSttStatus.value = sttManager.inspectCapability(
                            configuredDir = imported.modelDir,
                            configuredName = imported.displayName,
                            configuredSizeBytes = imported.sizeBytes,
                            configuredLang = imported.languageTag
                        )
                        _sttImportFeedbackAr.value = "تم تثبيت نموذج «${imported.displayName}» بنجاح للعمل أوفلاين بدون جوجل."
                        _voiceErrorBanner.value = null
                    },
                    onFailure = { err ->
                        _sttImportFeedbackAr.value = err.message ?: "تعذر استيراد ملف النموذج."
                    }
                )
            } finally {
                _isImportingSttModel.value = false
            }
        }
    }

    fun removeSttModel() {
        viewModelScope.launch {
            sttManager.deleteInstalledModels()
            settingsRepo.clearLocalSttModel()
            _offlineSttStatus.value = sttManager.inspectCapability()
            _sttImportFeedbackAr.value = "تم حذف نموذج الكلام المحلي من ذاكرة التطبيق."
        }
    }

    // Part C: Assistant Name, Grammatical Gender, and Voice Gender
    fun updateIdentityAndGender(
        assistantName: String? = null,
        assistantGender: AssistantGender? = null,
        voiceGender: AssistantGender? = null,
        selectedTtsVoiceName: String? = null
    ) {
        viewModelScope.launch {
            settingsRepo.updateIdentityAndGender(
                assistantName = assistantName,
                assistantGender = assistantGender,
                voiceGender = voiceGender,
                selectedTtsVoiceName = selectedTtsVoiceName
            )
            val s = settingsRepo.currentSettings()
            ttsManager.applyVoicePreferences(s.voiceGender, s.selectedTtsVoiceName)
        }
    }

    fun saveTypedWakePhrase(phrase: String) {
        viewModelScope.launch {
            val mode = if (enrolledSamples.value.isNotEmpty()) {
                WakeWordSetupMode.HYBRID
            } else {
                WakeWordSetupMode.TEXT_TYPED
            }
            settingsRepo.updateWakePhrase(phrase, mode)
            _enrollmentFeedbackAr.value = if (mode == WakeWordSetupMode.TEXT_TYPED) {
                "تم حفظ اسم التنبيه النصي: «${phrase.trim()}» (يتحقق عبر STT عند السمع)."
            } else {
                "تم حفظ اسم التنبيه المدمج: «${phrase.trim()}»."
            }
        }
    }

    fun recordVoiceWakeSample(targetWakePhrase: String) {
        if (_isRecordingEnrollment.value) return
        viewModelScope.launch {
            _isRecordingEnrollment.value = true
            _enrollmentFeedbackAr.value = "اتكلم دلوقتي وقول «$targetWakePhrase» بوضوح..."
            val result = enrollmentRecorder.recordSample(targetWakePhrase) { amp ->
                _enrollmentAmplitude.value = amp
            }
            when (result) {
                is EnrollmentCaptureResult.Success -> {
                    templateStore.addSample(result.sample)
                    settingsRepo.updateWakePhrase(
                        targetWakePhrase,
                        WakeWordSetupMode.VOICE_RECORDED
                    )
                    if (serviceRunning.value) {
                        WakeWordDetectionService.reloadTemplates(appContext)
                    }
                    val count = templateStore.samples.value.size
                    val cal = templateStore.getCalibratedThreshold(settings.value.wakeSensitivity)
                    _enrollmentFeedbackAr.value = if (count < VoiceTemplateStore.MIN_REQUIRED_SAMPLES) {
                        val remaining = VoiceTemplateStore.MIN_REQUIRED_SAMPLES - count
                        "تم حفظ العينة $count/${VoiceTemplateStore.MIN_REQUIRED_SAMPLES} لـ «$targetWakePhrase» — تبقى $remaining لتثبيت المعايرة."
                    } else {
                        "تمت المعايرة! ($count/${VoiceTemplateStore.MAX_SAMPLES} عينات • عتبة DTW ${(cal.threshold * 100).toInt()}%) لاسم «$targetWakePhrase»."
                    }
                }
                is EnrollmentCaptureResult.Error -> {
                    _enrollmentFeedbackAr.value = result.messageAr
                }
            }
            _isRecordingEnrollment.value = false
        }
    }

    fun clearVoiceSamples() {
        viewModelScope.launch {
            templateStore.clearAll()
            settingsRepo.updateWakeMode(WakeWordSetupMode.TEXT_TYPED)
            if (serviceRunning.value) {
                WakeWordDetectionService.reloadTemplates(appContext)
            }
            _enrollmentFeedbackAr.value = "تم مسح البصمات الصوتية المحلية."
        }
    }

    fun updateWakeSensitivity(value: Float) {
        viewModelScope.launch { settingsRepo.updateWakeSensitivity(value) }
    }

    fun updateVoiceMatch(
        enabled: Boolean,
        threshold: Float? = null,
        allowFallback: Boolean? = null
    ) {
        viewModelScope.launch { settingsRepo.updateVoiceMatch(enabled, threshold, allowFallback) }
    }

    fun updateWakeAckPhrase(phrase: String) {
        viewModelScope.launch { settingsRepo.updateWakeAckPhrase(phrase) }
    }

    fun updatePersonaTone(tone: PersonaTone) {
        viewModelScope.launch { settingsRepo.updatePersonaTone(tone) }
    }

    fun updateTtsTuning(rate: Float, pitch: Float) {
        viewModelScope.launch { settingsRepo.updateTtsTuning(rate, pitch) }
    }

    fun testEgyptianTts() {
        val s = settings.value
        val name = s.assistantName.ifBlank { "شيتا" }
        val isFem = s.assistantGender == AssistantGender.FEMALE
        val ready = if (isFem) "جاهزة" else "جاهز"
        val sample = when (s.personaTone) {
            PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                "أهلاً بحضرتك يا فندم. أنا $name، أنظمتي المحلية $ready وتحت أمرك بالعامية المصرية."
            PersonaTone.FRIENDLY_EGYPTIAN ->
                "أهلاً يا بطل! أنا $name وصوتي المصري $ready وتحت أمرك في أي وقت."
            PersonaTone.CONCISE_TACTICAL ->
                "النظام $name $ready. الصوت المصري يعمل بكفاءة."
        }
        ttsManager.speak(
            text = sample,
            rate = s.ttsSpeechRate,
            pitch = s.ttsPitch,
            voiceGender = s.voiceGender,
            selectedVoiceName = s.selectedTtsVoiceName
        )
    }

    // Part B: Local Unbundled GGUF LLM Controls
    fun inspectPickedGgufUri(uri: Uri) {
        viewModelScope.launch {
            localLlmManager.refreshStorageAndMemoryTelemetry(settingsRepo.currentSettings().localLlmConfig)
            val res = localLlmManager.inspectSelectedUri(uri)
            res.fold(
                onSuccess = { meta ->
                    _pendingGgufImport.value = uri to meta
                },
                onFailure = { err ->
                    CheetahRuntimeBus.updateLocalLlmStatus {
                        it.copy(lastErrorAr = err.message ?: "تعذر قراءة ملف GGUF")
                    }
                }
            )
        }
    }

    fun confirmImportGgufModel(storageMode: LocalLlmStorageMode) {
        val pending = _pendingGgufImport.value ?: return
        _pendingGgufImport.value = null
        viewModelScope.launch {
            localLlmManager.importAndSelectModel(pending.first, storageMode)
        }
    }

    fun cancelPendingGgufImport() {
        _pendingGgufImport.value = null
    }

    fun loadGgufModel() {
        viewModelScope.launch {
            localLlmManager.loadConfiguredModel()
        }
    }

    fun unloadGgufModel() {
        viewModelScope.launch {
            localLlmManager.unloadModel()
        }
    }

    fun removeGgufModel() {
        viewModelScope.launch {
            localLlmManager.removeModel()
        }
    }

    fun updateLlmConfig(
        contextLength: Int? = null,
        maxTokens: Int? = null,
        temperature: Float? = null,
        cpuThreads: Int? = null
    ) {
        viewModelScope.launch {
            val cur = settingsRepo.currentSettings().localLlmConfig
            val updated = cur.copy(
                contextLength = contextLength ?: cur.contextLength,
                maxTokens = maxTokens ?: cur.maxTokens,
                temperature = temperature ?: cur.temperature,
                cpuThreads = cpuThreads ?: cur.cpuThreads
            )
            settingsRepo.updateLocalLlmConfig(updated)
            localLlmManager.refreshStorageAndMemoryTelemetry(updated)
        }
    }

    fun updateAiBrainConfig(
        useAiForGeneral: Boolean? = null,
        aiMode: AiMode? = null,
        judgeSearchWithAi: Boolean? = null,
        searchEvalCount: Int? = null,
        showReasoning: Boolean? = null,
        showSources: Boolean? = null,
        maxThinkingSeconds: Int? = null,
        performanceProfile: PerformanceProfile? = null,
        autoUnloadMinutes: Int? = null
    ) {
        viewModelScope.launch {
            val cur = settingsRepo.currentSettings().localLlmConfig
            val updated = cur.copy(
                useAiForGeneral = useAiForGeneral ?: cur.useAiForGeneral,
                aiMode = aiMode ?: cur.aiMode,
                judgeSearchWithAi = judgeSearchWithAi ?: cur.judgeSearchWithAi,
                searchEvalCount = (searchEvalCount ?: cur.searchEvalCount).coerceIn(2, 10),
                showReasoning = showReasoning ?: cur.showReasoning,
                showSources = showSources ?: cur.showSources,
                maxThinkingSeconds = (maxThinkingSeconds ?: cur.maxThinkingSeconds).coerceIn(5, 120),
                performanceProfile = performanceProfile ?: cur.performanceProfile,
                autoUnloadMinutes = (autoUnloadMinutes ?: cur.autoUnloadMinutes).coerceIn(0, 120)
            )
            settingsRepo.updateLocalLlmConfig(updated)
            localLlmManager.refreshStorageAndMemoryTelemetry(updated)
        }
    }

    fun applyAiPreset(preset: AiPreset) {
        viewModelScope.launch {
            val cur = settingsRepo.currentSettings().localLlmConfig
            val updated = SettingsRepository.applyPreset(cur, preset)
            settingsRepo.updateLocalLlmConfig(updated)
            localLlmManager.refreshStorageAndMemoryTelemetry(updated)
        }
    }

    fun updateExpandedAiConfig(
        topP: Float? = null,
        topK: Int? = null,
        minP: Float? = null,
        repeatPenalty: Float? = null,
        seed: Int? = null,
        batchSize: Int? = null,
        chatTemplateOverride: ChatTemplateOverride? = null,
        customSystemPrompt: String? = null,
        responseStyle: ResponseStyle? = null,
        spokenMaxChars: Int? = null,
        aiLanguagePreference: AiLanguagePreference? = null,
        stopSequences: List<String>? = null,
        stripReasoningTags: Boolean? = null
    ) {
        viewModelScope.launch {
            val cur = settingsRepo.currentSettings().localLlmConfig
            val samplingTouched = topP != null || topK != null || minP != null || repeatPenalty != null || batchSize != null
            val updated = SettingsRepository.sanitizeLocalLlmConfig(
                cur.copy(
                    topP = topP ?: cur.topP,
                    topK = topK ?: cur.topK,
                    minP = minP ?: cur.minP,
                    repeatPenalty = repeatPenalty ?: cur.repeatPenalty,
                    seed = seed ?: cur.seed,
                    batchSize = batchSize ?: cur.batchSize,
                    chatTemplateOverride = chatTemplateOverride ?: cur.chatTemplateOverride,
                    customSystemPrompt = customSystemPrompt ?: cur.customSystemPrompt,
                    responseStyle = responseStyle ?: cur.responseStyle,
                    spokenMaxChars = spokenMaxChars ?: cur.spokenMaxChars,
                    aiLanguagePreference = aiLanguagePreference ?: cur.aiLanguagePreference,
                    stopSequences = stopSequences ?: cur.stopSequences,
                    stripReasoningTags = stripReasoningTags ?: cur.stripReasoningTags,
                    activePreset = if (samplingTouched) AiPreset.CUSTOM else cur.activePreset
                )
            )
            settingsRepo.updateLocalLlmConfig(updated)
            localLlmManager.refreshStorageAndMemoryTelemetry(updated)
        }
    }

    fun resetAiDefaults() {
        viewModelScope.launch {
            val cur = settingsRepo.currentSettings().localLlmConfig
            val reset = SettingsRepository.resetAiDefaultsPreservingModel(cur)
            settingsRepo.updateLocalLlmConfig(reset)
            localLlmManager.refreshStorageAndMemoryTelemetry(reset)
        }
    }

    suspend fun exportAiConfigJson(): String {
        val cur = settingsRepo.currentSettings().localLlmConfig
        return SettingsRepository.exportAiConfigToJson(cur)
    }

    suspend fun importAiConfigJson(rawJson: String): Result<Unit> {
        val cur = settingsRepo.currentSettings().localLlmConfig
        val res = SettingsRepository.importAiConfigFromJson(rawJson, cur)
        return res.map { imported ->
            settingsRepo.updateLocalLlmConfig(imported)
            localLlmManager.refreshStorageAndMemoryTelemetry(imported)
        }
    }

    fun updateDomainLists(trustedDomains: List<String>, untrustedDomains: List<String>) {
        viewModelScope.launch {
            settingsRepo.updateDomainLists(trustedDomains, untrustedDomains)
        }
    }

    fun runModelBenchmark() {
        if (_isBenchmarkRunning.value) return
        viewModelScope.launch {
            _isBenchmarkRunning.value = true
            try {
                localLlmManager.runBenchmarkTest(settingsRepo.currentSettings())
            } finally {
                _isBenchmarkRunning.value = false
            }
        }
    }

    // Part D & v0.4.1: Optional Keyless Internet Search & Engine Selector (OFF by default)
    fun setInternetSearch(
        enabled: Boolean,
        customSearxngUrl: String? = null,
        engineMode: SearchEngineMode? = null,
        autoSearchOrder: List<ConcreteSearchEngine>? = null
    ) {
        viewModelScope.launch {
            settingsRepo.setInternetSearch(
                enabled = enabled,
                customSearxngUrl = customSearxngUrl,
                engineMode = engineMode,
                autoSearchOrder = autoSearchOrder
            )
            if (!enabled) {
                _searchTestResult.value = null
            }
        }
    }

    fun selectSearchEngine(mode: SearchEngineMode) {
        viewModelScope.launch {
            val current = settingsRepo.currentSettings()
            settingsRepo.setInternetSearch(
                enabled = current.internetSearchEnabled,
                engineMode = mode
            )
            _searchTestResult.value = null
        }
    }

    fun updateAutoSearchOrder(order: List<ConcreteSearchEngine>) {
        viewModelScope.launch {
            val current = settingsRepo.currentSettings()
            settingsRepo.setInternetSearch(
                enabled = current.internetSearchEnabled,
                autoSearchOrder = order
            )
        }
    }

    fun toggleSearchEngineEnabled(engine: ConcreteSearchEngine, enabled: Boolean) {
        viewModelScope.launch {
            val current = settingsRepo.currentSettings()
            val updated = if (enabled) {
                current.enabledSearchEngines + engine
            } else {
                val filtered = current.enabledSearchEngines - engine
                if (filtered.isEmpty()) setOf(engine) else filtered
            }
            settingsRepo.setInternetSearch(
                enabled = current.internetSearchEnabled,
                enabledSearchEngines = updated
            )
            _searchTestResult.value = null
        }
    }

    fun setFetchPageExcerpts(enabled: Boolean) {
        viewModelScope.launch {
            val current = settingsRepo.currentSettings()
            settingsRepo.setInternetSearch(
                enabled = current.internetSearchEnabled,
                fetchPageExcerpts = enabled
            )
        }
    }

    fun testSearxngConnection(customUrl: String) {
        if (_isTestingSearch.value) return
        viewModelScope.launch {
            _isTestingSearch.value = true
            try {
                val s = settingsRepo.currentSettings()
                _searchTestResult.value = OnlineSearchManager.testSearxngConnection(
                    customSearxngUrl = customUrl,
                    internetSearchEnabled = s.internetSearchEnabled
                )
            } finally {
                _isTestingSearch.value = false
            }
        }
    }

    fun testSearchWithSelectedEngine() {
        if (_isTestingSearch.value) return
        viewModelScope.launch {
            _isTestingSearch.value = true
            try {
                val s = settingsRepo.currentSettings()
                _searchTestResult.value = OnlineSearchManager.testEngineSearch(s)
            } finally {
                _isTestingSearch.value = false
            }
        }
    }

    fun toggleRule(ruleId: String, enabled: Boolean) {
        viewModelScope.launch { settingsRepo.toggleConfigurableRule(ruleId, enabled) }
    }

    fun addPersonalShortcut(trigger: String, command: String) {
        viewModelScope.launch { memoryStore.addShortcut(trigger, command) }
    }

    fun deletePersonalShortcut(id: String) {
        viewModelScope.launch { memoryStore.deleteShortcut(id) }
    }

    fun deleteMemoryEntry(id: Long) {
        viewModelScope.launch { memoryStore.deleteEntry(id) }
    }

    fun clearAllMemory() {
        viewModelScope.launch { memoryStore.clearAllMemory() }
    }

    fun runControlledMicDiagnosticTest() {
        refreshPermissions()
        if (!_permissionHealth.value.hasMicPermission) {
            CheetahRuntimeBus.updateDiagnostics {
                it.copy(lastError = "امنح صلاحية المايكروفون من الإعدادات لتشغيل الاختبار التشخيصي.")
            }
            return
        }
        viewModelScope.launch {
            settingsRepo.setContinuousListening(true)
            WakeWordDetectionService.runDiagnosticTest(appContext)
        }
    }

    fun startWakeTenSayTest() {
        refreshPermissions()
        if (!_permissionHealth.value.hasMicPermission) {
            _enrollmentFeedbackAr.value = "افتح صلاحية المايكروفون من الإعدادات أولاً لبدء اختبار الـ 10 مرات."
            return
        }
        val cal = wakeCalibration.value
        wakeTestStartedService = !serviceRunning.value
        _wakeTestState.value = WakeTestSessionState(
            isActive = true,
            targetCount = 10,
            attempts = 0,
            hits = 0,
            lastConfidence = null,
            lastThreshold = cal.threshold,
            lastSource = "",
            summaryAr = "تحدّث وقل «${settings.value.wakePhrase}» 10 مرات بصوت طبيعي..."
        )
        val started = WakeWordDetectionService.runDiagnosticTest(appContext, durationMs = 55_000L)
        if (!started) {
            _wakeTestState.value = _wakeTestState.value.copy(
                isActive = false,
                summaryAr = "تعذر تشغيل خدمة الخلفية للاختبار — تأكد من استثناء البطارية وأعد المحاولة."
            )
            wakeTestStartedService = false
        }
    }

    fun stopWakeTenSayTest() {
        val cur = _wakeTestState.value
        _wakeTestState.value = cur.copy(
            isActive = false,
            summaryAr = if (cur.attempts > 0) "توقف الاختبار عند ${cur.hits}/${cur.attempts}." else null
        )
        WakeWordDetectionService.cancelDiagnosticTest(appContext)
        if (wakeTestStartedService && !settings.value.continuousListeningEnabled) {
            WakeWordDetectionService.stop(appContext)
        }
        wakeTestStartedService = false
    }

    fun runThreeStageSelfTest() {
        if (_selfTestState.value.isRunning) return
        viewModelScope.launch {
            _selfTestState.value = DiagnosticSelfTestState(
                isRunning = true,
                stage1Label = "جاري فحص NLU والقواعد...",
                stage2Label = "في انتظار المرحلة 2...",
                stage3Label = "في انتظار المرحلة 3..."
            )

            // Stage 1: Typed NLU + Guardrails + Device Status
            val s = settingsRepo.currentSettings()
            val stage1Result = runCatching {
                val out = commandProcessor.process(
                    rawInput = "الساعة كام والبطارية كام؟",
                    settings = s,
                    voiceMatchPassed = true,
                    onStopSpeech = {}
                )
                !out.isBlocked && out.replyAr.isNotBlank()
            }.getOrDefault(false)
            val stage1Text = if (stage1Result) {
                "نجح (تم تحليل وتنفيذ الأمر المحلي بدون إنترنت)"
            } else {
                "فشل تحليل الأمر المحلي"
            }

            // Stage 2: Offline STT (Talk) readiness & live capture test
            refreshPermissions()
            val perms = _permissionHealth.value
            val sttCap = sttManager.inspectCapability(
                configuredDir = s.sttModelDir,
                configuredName = s.sttModelName,
                configuredSizeBytes = s.sttModelSizeBytes,
                configuredLang = s.sttModelLanguage
            )
            val (stage2Pass, stage2Text) = when {
                !perms.hasMicPermission -> false to VoiceErrorMapper.mapFailure(
                    "MIC_PERMISSION",
                    hasMicPermission = false,
                    hasLocalSttModel = sttCap.hasModelConfigured
                ).messageAr
                !sttCap.hasModelConfigured -> false to VoiceErrorMapper.mapFailure(
                    "NO_STT_MODEL",
                    hasMicPermission = true,
                    hasLocalSttModel = false
                ).messageAr
                else -> {
                    _selfTestState.value = _selfTestState.value.copy(
                        stage1Passed = stage1Result,
                        stage1Label = stage1Text,
                        stage2Label = "تحدّث بأي جملة الآن (5 ثوانٍ) لاختبار Vosk STT..."
                    )
                    val res = MicrophoneArbitrator.withMicAccess(MicOwner.UI_PTT_STT) {
                        sttManager.listenOnce(timeoutMs = 5_000L)
                    }
                    res.fold(
                        onSuccess = { heard -> true to "نجح (سمع: «$heard»)" },
                        onFailure = { err ->
                            val mapped = VoiceErrorMapper.mapFailure(
                                rawReason = err.message,
                                hasMicPermission = true,
                                hasLocalSttModel = true
                            )
                            false to mapped.messageAr
                        }
                    )
                }
            }

            // Stage 3: Wake word engine & calibrated template readiness
            val samples = templateStore.samples.value.count { it.mfccSequence.isNotEmpty() }
            val cal = WakeThresholdCalibrator.calibrate(templateStore.samples.value, s.wakeSensitivity)
            val stage3Pass = perms.hasMicPermission && (samples >= VoiceTemplateStore.MIN_REQUIRED_SAMPLES || s.wakeSetupMode == WakeWordSetupMode.TEXT_TYPED)
            val stage3Text = when {
                !perms.hasMicPermission -> "فشل: صلاحية المايكروفون غير مفعلة"
                samples >= VoiceTemplateStore.MIN_REQUIRED_SAMPLES ->
                    "جاهز ($samples عينات MFCC-DTW • عتبة ${(cal.threshold * 100).toInt()}%)"
                s.wakeSetupMode == WakeWordSetupMode.TEXT_TYPED ->
                    "جاهز في الوضع النصي عبر STT المحلي"
                else ->
                    "غير مكتمل ($samples/${VoiceTemplateStore.MIN_REQUIRED_SAMPLES} عينات — أكمل تسجيل 5 عينات أو اختبر 10 مرات)"
            }

            _selfTestState.value = DiagnosticSelfTestState(
                isRunning = false,
                stage1Passed = stage1Result,
                stage1Label = stage1Text,
                stage2Passed = stage2Pass,
                stage2Label = stage2Text,
                stage3Passed = stage3Pass,
                stage3Label = stage3Text
            )
        }
    }

    fun markFalseActivation() {
        CheetahRuntimeBus.incrementFalseActivation()
    }

    fun resetDiagnosticCounters() {
        CheetahRuntimeBus.resetDiagnosticsCounters()
    }

    fun clearTransitionLog() {
        CheetahRuntimeBus.clearTransitionLog()
    }

    override fun onCleared() {
        activeVoiceJob?.cancel()
        activeCommandJob?.cancel()
        localLlmManager.cancelGeneration()
        sttManager.stopAndRelease()
        ttsManager.release()
        super.onCleared()
    }
}

data class WakeTestSessionState(
    val isActive: Boolean = false,
    val targetCount: Int = 10,
    val attempts: Int = 0,
    val hits: Int = 0,
    val lastConfidence: Float? = null,
    val lastThreshold: Float = 0.62f,
    val lastSource: String = "",
    val summaryAr: String? = null
)

data class DiagnosticSelfTestState(
    val isRunning: Boolean = false,
    val stage1Passed: Boolean? = null,
    val stage1Label: String = "1. اختبار كتابة وأمر محلي (NLU)",
    val stage2Passed: Boolean? = null,
    val stage2Label: String = "2. اختبار التحدث المباشر (Vosk Offline STT)",
    val stage3Passed: Boolean? = null,
    val stage3Label: String = "3. اختبار جاهزية كلمة التنبيه (Wake Word)"
)
