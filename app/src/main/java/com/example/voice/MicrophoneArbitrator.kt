package com.example.voice

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MicOwner {
    NONE,
    WAKE_AUDIO_RECORD,
    WAKE_TEXT_STT,
    WAKE_COMMAND_STT,
    UI_PTT_STT,
    UI_ENROLLMENT,
    ASSIST_SESSION_STT
}

/**
 * Process-wide single-owner microphone coordinator.
 * Prevents WakeWordDetectionService AudioRecord from colliding with Vosk STT
 * or WakeWordEnrollmentRecorder on Android hardware that supports only one active capture client.
 */
object MicrophoneArbitrator {

    private val mutex = Mutex()
    private val _currentOwner = MutableStateFlow(MicOwner.NONE)
    val currentOwner: StateFlow<MicOwner> = _currentOwner.asStateFlow()

    private val _yieldWakeForForeground = MutableStateFlow(false)
    val yieldWakeForForeground: StateFlow<Boolean> = _yieldWakeForForeground.asStateFlow()

    suspend fun <T> withMicAccess(
        owner: MicOwner,
        settleDelayMs: Long = 160L,
        block: suspend () -> T
    ): T {
        val isForegroundDemand = owner == MicOwner.UI_PTT_STT ||
            owner == MicOwner.UI_ENROLLMENT ||
            owner == MicOwner.ASSIST_SESSION_STT

        if (isForegroundDemand) {
            _yieldWakeForForeground.value = true
        }

        return try {
            mutex.withLock {
                _currentOwner.value = owner
                if (settleDelayMs > 0L) {
                    delay(settleDelayMs)
                }
                try {
                    block()
                } finally {
                    _currentOwner.value = MicOwner.NONE
                }
            }
        } finally {
            if (isForegroundDemand) {
                _yieldWakeForForeground.value = false
            }
        }
    }

    fun tryMarkWakeAudioRecordActive(): Boolean {
        if (_yieldWakeForForeground.value) return false
        if (!mutex.tryLock()) return false
        _currentOwner.value = MicOwner.WAKE_AUDIO_RECORD
        return true
    }

    fun releaseWakeAudioRecordIfHeld() {
        if (_currentOwner.value == MicOwner.WAKE_AUDIO_RECORD) {
            _currentOwner.value = MicOwner.NONE
            runCatching { mutex.unlock() }
        }
    }
}
