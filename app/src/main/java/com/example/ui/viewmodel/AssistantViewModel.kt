package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.controller.DeviceActionExecutor
import com.example.controller.PermissionHealthChecker
import com.example.core.model.AssistantPipelineState
import com.example.core.model.AssistantSettings
import com.example.core.model.EndLineRule
import com.example.core.model.GuardrailVerdict
import com.example.core.model.InteractionTrace
import com.example.core.model.PermissionHealthState
import com.example.core.model.PersonaTone
import com.example.core.model.VoiceTemplateSample
import com.example.core.model.WakeWordSetupMode
import com.example.data.SettingsRepository
import com.example.data.VoiceTemplateStore
import com.example.guardrails.CommandGuardrailValidator
import com.example.intelligence.EgyptianColloquialNluParser
import com.example.intelligence.JarvisEgyptianResponder
import com.example.voice.CheetahRuntimeBus
import com.example.voice.CheetahWakeWordService
import com.example.voice.EgyptianOfflineSttManager
import com.example.voice.EgyptianTtsManager
import com.example.voice.EnrollmentCaptureResult
import com.example.voice.TtsStatus
import com.example.voice.WakeWordEnrollmentRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val settingsRepo = SettingsRepository(appContext)
    private val templateStore = VoiceTemplateStore(appContext)
    private val enrollmentRecorder = WakeWordEnrollmentRecorder(appContext)
    private val sttManager = EgyptianOfflineSttManager(appContext)
    private val ttsManager = EgyptianTtsManager(appContext)

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

    val ttsStatus: StateFlow<TtsStatus> = ttsManager.status
    val serviceRunning: StateFlow<Boolean> = CheetahRuntimeBus.serviceRunning
    val liveMicLevel: StateFlow<Float> = CheetahRuntimeBus.liveMicLevel
    val pipelineState: StateFlow<AssistantPipelineState> = CheetahRuntimeBus.pipelineState
    val lastVoiceMatchScore: StateFlow<Float?> = CheetahRuntimeBus.lastVoiceMatchScore

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

    val isOnDeviceSttSupported: Boolean
        get() = sttManager.isOnDeviceSupported()

    fun refreshPermissions() {
        _permissionHealth.value = PermissionHealthChecker.inspect(appContext)
    }

    fun startVoiceCommand() {
        refreshPermissions()
        if (!_permissionHealth.value.hasMicPermission) {
            CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.ERROR)
            _partialTranscript.value = "لازم تمنح صلاحية المايكروفون الأول من مركز الصلاحيات."
            return
        }

        CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.LISTENING_COMMAND)
        _partialTranscript.value = "سامعك بالعامية المصرية..."
        sttManager.startListening(
            onRmsDb = { level -> CheetahRuntimeBus.updateMicLevel(level) },
            onPartialResult = { partial -> _partialTranscript.value = partial },
            onFinalResult = { finalText ->
                _partialTranscript.value = finalText
                processCommand(finalText, voiceMatchPassed = true)
            },
            onError = { errMsg ->
                _partialTranscript.value = errMsg
                CheetahRuntimeBus.updatePipelineState(
                    if (serviceRunning.value) AssistantPipelineState.LISTENING_WAKE_WORD
                    else AssistantPipelineState.IDLE
                )
            }
        )
    }

    fun submitTextCommand(rawCommand: String) {
        val trimmed = rawCommand.trim()
        if (trimmed.isEmpty()) return
        _partialTranscript.value = trimmed
        processCommand(trimmed, voiceMatchPassed = true)
    }

    private fun processCommand(rawText: String, voiceMatchPassed: Boolean) {
        viewModelScope.launch {
            CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.THINKING_LOCAL)
            val currentSettings = settingsRepo.currentSettings()
            val currentPerms = PermissionHealthChecker.inspect(appContext)
            _permissionHealth.value = currentPerms

            val (normalizedPhonetic, parsedIntent) = EgyptianColloquialNluParser.parse(
                rawInput = rawText,
                configuredWakePhrase = currentSettings.wakePhrase
            )

            val verdict = CommandGuardrailValidator.validate(
                intent = parsedIntent,
                settings = currentSettings,
                permissions = currentPerms,
                voiceMatchPassed = voiceMatchPassed
            )

            val timeLabel = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

            when (verdict) {
                is GuardrailVerdict.Denied -> {
                    CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.BLOCKED_BY_GUARDRAIL)
                    val reply = JarvisEgyptianResponder.formatGuardrailBlockReply(
                        verdict.reasonAr,
                        currentSettings.personaTone
                    )
                    prependTrace(
                        InteractionTrace(
                            rawUtterance = rawText,
                            normalizedPhonetics = normalizedPhonetic,
                            intentLabel = parsedIntent.categoryName,
                            guardrailStatus = "BLOCKED (${verdict.ruleId})",
                            isBlocked = true,
                            assistantReplyAr = reply,
                            voiceMatchScore = lastVoiceMatchScore.value,
                            timestampFormatted = timeLabel
                        )
                    )
                    speakReply(reply, currentSettings)
                }

                is GuardrailVerdict.Allowed -> {
                    CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.EXECUTING_ACTION)
                    val result = DeviceActionExecutor.execute(
                        appContext,
                        verdict.intent,
                        currentSettings.personaTone
                    )
                    prependTrace(
                        InteractionTrace(
                            rawUtterance = rawText,
                            normalizedPhonetics = normalizedPhonetic,
                            intentLabel = verdict.intent.categoryName,
                            guardrailStatus = "ALLOWLIST_VERIFIED",
                            isBlocked = false,
                            assistantReplyAr = result.replyAr,
                            voiceMatchScore = lastVoiceMatchScore.value,
                            timestampFormatted = timeLabel
                        )
                    )
                    speakReply(result.replyAr, currentSettings)
                }
            }
        }
    }

    private fun speakReply(text: String, s: AssistantSettings) {
        CheetahRuntimeBus.updatePipelineState(AssistantPipelineState.SPEAKING)
        ttsManager.speak(text, s.ttsSpeechRate, s.ttsPitch) {
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
            settingsRepo.setContinuousListening(enable)
            if (enable) {
                CheetahWakeWordService.start(appContext)
            } else {
                CheetahWakeWordService.stop(appContext)
            }
        }
    }

    fun toggleMute() {
        viewModelScope.launch {
            val next = !settings.value.isListeningMuted
            settingsRepo.setListeningMuted(next)
            if (serviceRunning.value) {
                appContext.startService(
                    android.content.Intent(appContext, CheetahWakeWordService::class.java).apply {
                        action = CheetahWakeWordService.ACTION_TOGGLE_MUTE
                    }
                )
            }
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
            _enrollmentFeedbackAr.value = "تم حفظ اسم التنبيه النصي: «${phrase.trim()}»"
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
                    val count = templateStore.samples.value.size
                    _enrollmentFeedbackAr.value =
                        "تم حفظ البصمة الصوتية ($count/${VoiceTemplateStore.MAX_SAMPLES}) لاسم «$targetWakePhrase»."
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
            _enrollmentFeedbackAr.value = "تم مسح بصمة الصوت المحلية."
        }
    }

    fun updateWakeSensitivity(value: Float) {
        viewModelScope.launch { settingsRepo.updateWakeSensitivity(value) }
    }

    fun updateVoiceMatch(enabled: Boolean, threshold: Float? = null) {
        viewModelScope.launch { settingsRepo.updateVoiceMatch(enabled, threshold) }
    }

    fun updatePersonaTone(tone: PersonaTone) {
        viewModelScope.launch { settingsRepo.updatePersonaTone(tone) }
    }

    fun updateTtsTuning(rate: Float, pitch: Float) {
        viewModelScope.launch { settingsRepo.updateTtsTuning(rate, pitch) }
    }

    fun testEgyptianTts() {
        val s = settings.value
        val sample = when (s.personaTone) {
            PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                "أهلاً بحضرتك يا فندم. أنظمة شيتا المحلية شغالة بالكامل بالعامية المصرية بدون أي سيرفر خارجي."
            PersonaTone.FRIENDLY_EGYPTIAN ->
                "أهلاً يا بطل! صوت شيتا المصري جاهز وتحت أمرك في أي وقت."
            PersonaTone.CONCISE_TACTICAL ->
                "نظام شيتا جاهز. الصوت المصري يعمل بكفاءة."
        }
        ttsManager.speak(sample, s.ttsSpeechRate, s.ttsPitch)
    }

    fun toggleRule(ruleId: String, enabled: Boolean) {
        viewModelScope.launch { settingsRepo.toggleConfigurableRule(ruleId, enabled) }
    }

    override fun onCleared() {
        sttManager.stopAndRelease()
        ttsManager.release()
        super.onCleared()
    }
}
