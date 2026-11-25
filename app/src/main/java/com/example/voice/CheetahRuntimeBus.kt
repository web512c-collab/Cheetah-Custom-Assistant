package com.example.voice

import com.example.core.model.AssistantPipelineState
import com.example.core.model.WakeDiagnosticSnapshot
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

/**
 * In-process reactive telemetry bus bridging WakeWordDetectionService,
 * VoiceInteractionSession, AssistActivity, and Jetpack Compose Console/Diagnostics UI.
 */
object CheetahRuntimeBus {
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

    private val _diagnostics = MutableStateFlow(WakeDiagnosticSnapshot())
    val diagnostics: StateFlow<WakeDiagnosticSnapshot> = _diagnostics.asStateFlow()

    private val _wakeEvents = MutableSharedFlow<WakeTriggerEvent>(extraBufferCapacity = 8)
    val wakeEvents: SharedFlow<WakeTriggerEvent> = _wakeEvents.asSharedFlow()

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
        if (!running) {
            _liveMicLevel.value = 0f
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

    fun updateDiagnostics(transform: (WakeDiagnosticSnapshot) -> WakeDiagnosticSnapshot) {
        _diagnostics.update(transform)
    }

    fun incrementFalseActivation() {
        _diagnostics.update {
            it.copy(falseActivationsCount = it.falseActivationsCount + 1)
        }
    }

    fun resetDiagnosticsCounters() {
        _diagnostics.update {
            it.copy(
                falseActivationsCount = 0,
                lastError = null
            )
        }
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
}
