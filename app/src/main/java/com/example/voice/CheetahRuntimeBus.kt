package com.example.voice

import com.example.core.model.AssistantPipelineState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class WakeTriggerEvent(
    val timestampMs: Long,
    val voiceMatchScore: Float?,
    val passedVoiceMatch: Boolean,
    val source: String
)

/**
 * In-process reactive telemetry bus bridging the Foreground WakeWordService,
 * VoiceInteractionSession, and Jetpack Compose Console/Settings UI.
 */
object CheetahRuntimeBus {
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _liveMicLevel = MutableStateFlow(0f)
    val liveMicLevel: StateFlow<Float> = _liveMicLevel.asStateFlow()

    private val _lastVoiceMatchScore = MutableStateFlow<Float?>(null)
    val lastVoiceMatchScore: StateFlow<Float?> = _lastVoiceMatchScore.asStateFlow()

    private val _pipelineState = MutableStateFlow(AssistantPipelineState.IDLE)
    val pipelineState: StateFlow<AssistantPipelineState> = _pipelineState.asStateFlow()

    private val _wakeEvents = MutableSharedFlow<WakeTriggerEvent>(extraBufferCapacity = 8)
    val wakeEvents: SharedFlow<WakeTriggerEvent> = _wakeEvents.asSharedFlow()

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
        if (!running) {
            _liveMicLevel.value = 0f
            if (_pipelineState.value == AssistantPipelineState.LISTENING_WAKE_WORD) {
                _pipelineState.value = AssistantPipelineState.IDLE
            }
        }
    }

    fun updateMicLevel(level: Float) {
        _liveMicLevel.value = level.coerceIn(0f, 1f)
    }

    fun updatePipelineState(state: AssistantPipelineState) {
        _pipelineState.value = state
    }

    fun emitWakeTrigger(event: WakeTriggerEvent) {
        _lastVoiceMatchScore.value = event.voiceMatchScore
        _wakeEvents.tryEmit(event)
    }
}
