package com.example.voice

import com.example.core.model.AssistantPipelineState
import com.example.core.model.LocalLlmRuntimeStatus
import com.example.core.model.ProposedAction
import com.example.core.model.TransitionLogEntry
import com.example.core.model.WakeDiagnosticSnapshot
import com.example.core.model.WakePipelineState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class WakeTriggerEvent(
    val timestampMs: Long,
    val wakeConfidence: Float,
    val voiceMatchScore: Float?,
    val passedVoiceMatch: Boolean,
    val source: String
)

data class WakeEvalTelemetry(
    val detected: Boolean,
    val confidence: Float,
    val threshold: Float,
    val source: String,
    val timestampMs: Long
)

/**
 * In-process reactive telemetry bus bridging WakeWordDetectionService,
 * LocalLlmManager, AssistantBrain, OnlineSearchManager, VoiceInteractionSession,
 * AssistActivity, and Jetpack Compose Console/Diagnostics UI.
 */
object CheetahRuntimeBus {
    private const val MAX_TRANSITION_LOG = 50

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _isSttActive = MutableStateFlow(false)
    val isSttActive: StateFlow<Boolean> = _isSttActive.asStateFlow()

    private val _liveMicLevel = MutableStateFlow(0f)
    val liveMicLevel: StateFlow<Float> = _liveMicLevel.asStateFlow()

    private val _lastVoiceMatchScore = MutableStateFlow<Float?>(null)
    val lastVoiceMatchScore: StateFlow<Float?> = _lastVoiceMatchScore.asStateFlow()

    private val _pipelineState = MutableStateFlow(AssistantPipelineState.IDLE)
    val pipelineState: StateFlow<AssistantPipelineState> = _pipelineState.asStateFlow()

    private val _wakePipelineState = MutableStateFlow(WakePipelineState.IDLE_WAKE_LISTENING)
    val wakePipelineState: StateFlow<WakePipelineState> = _wakePipelineState.asStateFlow()

    private val _transitionLog = MutableStateFlow<List<TransitionLogEntry>>(emptyList())
    val transitionLog: StateFlow<List<TransitionLogEntry>> = _transitionLog.asStateFlow()

    private val _diagnostics = MutableStateFlow(WakeDiagnosticSnapshot())
    val diagnostics: StateFlow<WakeDiagnosticSnapshot> = _diagnostics.asStateFlow()

    private val _wakeEvents = MutableSharedFlow<WakeTriggerEvent>(extraBufferCapacity = 8)
    val wakeEvents: SharedFlow<WakeTriggerEvent> = _wakeEvents.asSharedFlow()

    private val _wakeEvalEvents = MutableSharedFlow<WakeEvalTelemetry>(extraBufferCapacity = 16)
    val wakeEvalEvents: SharedFlow<WakeEvalTelemetry> = _wakeEvalEvents.asSharedFlow()

    private val _localLlmStatus = MutableStateFlow(LocalLlmRuntimeStatus())
    val localLlmStatus: StateFlow<LocalLlmRuntimeStatus> = _localLlmStatus.asStateFlow()

    private val _streamingReplyText = MutableStateFlow("")
    val streamingReplyText: StateFlow<String> = _streamingReplyText.asStateFlow()

    private val _isNetworkSearching = MutableStateFlow(false)
    val isNetworkSearching: StateFlow<Boolean> = _isNetworkSearching.asStateFlow()

    private val _pendingContinuationText = MutableStateFlow<String?>(null)
    val pendingContinuationText: StateFlow<String?> = _pendingContinuationText.asStateFlow()

    private val _pendingProposedAction = MutableStateFlow<ProposedAction?>(null)
    val pendingProposedAction: StateFlow<ProposedAction?> = _pendingProposedAction.asStateFlow()

    private val _incomingSharedText = MutableStateFlow<String?>(null)
    val incomingSharedText: StateFlow<String?> = _incomingSharedText.asStateFlow()

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
        if (!running) {
            _liveMicLevel.value = 0f
            _wakePipelineState.value = WakePipelineState.IDLE_WAKE_LISTENING
            _diagnostics.update {
                it.copy(
                    audioRecordStatus = "STOPPED",
                    wakeEngineStatus = "IDLE"
                )
            }
            if (_pipelineState.value == AssistantPipelineState.LISTENING_WAKE_WORD) {
                _pipelineState.value = AssistantPipelineState.IDLE
            }
        }
    }

    fun setSttActive(active: Boolean) {
        _isSttActive.value = active
    }

    fun updateMicLevel(level: Float) {
        _liveMicLevel.value = level.coerceIn(0f, 1f)
    }

    fun updatePipelineState(state: AssistantPipelineState) {
        _pipelineState.value = state
    }

    fun recordStateTransition(entry: TransitionLogEntry) {
        _wakePipelineState.value = entry.toState
        val mappedUiState = when (entry.toState) {
            WakePipelineState.IDLE_WAKE_LISTENING ->
                if (_serviceRunning.value) AssistantPipelineState.LISTENING_WAKE_WORD else AssistantPipelineState.IDLE
            WakePipelineState.WAKE_DETECTED -> AssistantPipelineState.WAKE_ACKNOWLEDGED
            WakePipelineState.COMMAND_LISTENING -> AssistantPipelineState.LISTENING_COMMAND
            WakePipelineState.PROCESSING -> AssistantPipelineState.THINKING_LOCAL
            WakePipelineState.SPEAKING -> AssistantPipelineState.SPEAKING
        }
        _pipelineState.value = mappedUiState
        _transitionLog.update { current ->
            (listOf(entry) + current).take(MAX_TRANSITION_LOG)
        }
    }

    fun clearTransitionLog() {
        _transitionLog.value = emptyList()
    }

    fun updateDiagnostics(transform: (WakeDiagnosticSnapshot) -> WakeDiagnosticSnapshot) {
        _diagnostics.update(transform)
    }

    fun incrementFalseActivation() {
        _diagnostics.update {
            it.copy(falseActivationsCount = it.falseActivationsCount + 1)
        }
    }

    fun incrementAudioRecordRecoveries() {
        _diagnostics.update {
            it.copy(audioRecordRecoveriesCount = it.audioRecordRecoveriesCount + 1)
        }
    }

    fun incrementWatchdogTimeouts() {
        _diagnostics.update {
            it.copy(watchdogTimeoutsCount = it.watchdogTimeoutsCount + 1)
        }
    }

    fun resetDiagnosticsCounters() {
        _diagnostics.update {
            it.copy(
                falseActivationsCount = 0,
                audioRecordRecoveriesCount = 0,
                watchdogTimeoutsCount = 0,
                lastError = null
            )
        }
    }

    fun updateLocalLlmStatus(transform: (LocalLlmRuntimeStatus) -> LocalLlmRuntimeStatus) {
        _localLlmStatus.update(transform)
    }

    fun setStreamingReplyText(text: String) {
        _streamingReplyText.value = text
    }

    fun setNetworkSearching(active: Boolean) {
        _isNetworkSearching.value = active
    }

    fun setPendingContinuationText(text: String?) {
        _pendingContinuationText.value = text?.takeIf { it.isNotBlank() }
    }

    fun setPendingProposedAction(action: ProposedAction?) {
        _pendingProposedAction.value = action
    }

    fun setIncomingSharedText(text: String?) {
        _incomingSharedText.value = text?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun emitWakeTrigger(event: WakeTriggerEvent) {
        _lastVoiceMatchScore.value = event.voiceMatchScore
        _diagnostics.update {
            it.copy(
                lastConfidence = event.wakeConfidence,
                lastVoiceMatchScore = event.voiceMatchScore,
                lastVoiceMatchResult = when {
                    event.voiceMatchScore == null -> "معطّل / غير متاح"
                    event.passedVoiceMatch -> "تطابق ناجح (${(event.voiceMatchScore * 100).toInt()}%)"
                    else -> "تطابق منخفض — بديل المالك (${(event.voiceMatchScore * 100).toInt()}%)"
                },
                lastWakeTimestampMs = event.timestampMs
            )
        }
        _wakeEvents.tryEmit(event)
    }

    fun emitWakeEval(event: WakeEvalTelemetry) {
        _wakeEvalEvents.tryEmit(event)
    }
}
