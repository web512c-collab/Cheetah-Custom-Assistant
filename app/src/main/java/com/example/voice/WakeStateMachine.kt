package com.example.voice

import com.example.core.model.TransitionLogEntry
import com.example.core.model.WakePipelineState
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic 5-state machine for the wake-word -> command -> processing -> speaking cycle.
 * Pure Kotlin (JVM unit-testable) with strict transition validation, watchdog timeouts,
 * and automatic safe fallback to IDLE_WAKE_LISTENING.
 */
class WakeStateMachine(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val onTransition: (TransitionLogEntry) -> Unit = {}
) {
    @Volatile
    var currentState: WakePipelineState = WakePipelineState.IDLE_WAKE_LISTENING
        private set

    @Volatile
    var stateEnteredAtMs: Long = clockMs()
        private set

    private val seq = AtomicLong(0L)
    private val lock = Any()

    fun transition(
        to: WakePipelineState,
        reason: String,
        isError: Boolean = false
    ): Boolean = synchronized(lock) {
        val from = currentState
        if (from == to) return@synchronized true

        if (!isValidTransition(from, to)) {
            val forcedReason = "INVALID($from->$to): $reason — forcing IDLE_WAKE_LISTENING"
            currentState = WakePipelineState.IDLE_WAKE_LISTENING
            stateEnteredAtMs = clockMs()
            onTransition(
                TransitionLogEntry(
                    id = seq.incrementAndGet(),
                    timestampMs = stateEnteredAtMs,
                    fromState = from,
                    toState = WakePipelineState.IDLE_WAKE_LISTENING,
                    reason = forcedReason,
                    isError = true
                )
            )
            return@synchronized false
        }

        currentState = to
        stateEnteredAtMs = clockMs()
        onTransition(
            TransitionLogEntry(
                id = seq.incrementAndGet(),
                timestampMs = stateEnteredAtMs,
                fromState = from,
                toState = to,
                reason = reason,
                isError = isError
            )
        )
        return@synchronized true
    }

    fun forceResetToIdle(reason: String, isError: Boolean = true) = synchronized(lock) {
        val from = currentState
        currentState = WakePipelineState.IDLE_WAKE_LISTENING
        stateEnteredAtMs = clockMs()
        onTransition(
            TransitionLogEntry(
                id = seq.incrementAndGet(),
                timestampMs = stateEnteredAtMs,
                fromState = from,
                toState = WakePipelineState.IDLE_WAKE_LISTENING,
                reason = reason,
                isError = isError
            )
        )
    }

    /**
     * Checks if the current non-idle state has exceeded its watchdog budget.
     * Returns true and resets to IDLE_WAKE_LISTENING if timed out.
     */
    fun checkWatchdog(llmActive: Boolean = false): Boolean = synchronized(lock) {
        val st = currentState
        if (st == WakePipelineState.IDLE_WAKE_LISTENING) return@synchronized false
        val budgetMs = timeoutForStateMs(st, llmActive)
        val elapsed = clockMs() - stateEnteredAtMs
        if (elapsed > budgetMs) {
            forceResetToIdle("watchdog_timeout_${st.name}_after_${elapsed}ms", isError = true)
            return@synchronized true
        }
        return@synchronized false
    }

    companion object {
        const val TIMEOUT_WAKE_DETECTED_MS = 5_000L
        const val TIMEOUT_COMMAND_LISTENING_MS = 12_500L
        const val TIMEOUT_PROCESSING_BASE_MS = 22_000L
        const val TIMEOUT_PROCESSING_LLM_MS = 65_000L
        const val TIMEOUT_SPEAKING_MS = 20_000L

        fun timeoutForStateMs(state: WakePipelineState, llmActive: Boolean = false): Long = when (state) {
            WakePipelineState.IDLE_WAKE_LISTENING -> Long.MAX_VALUE
            WakePipelineState.WAKE_DETECTED -> TIMEOUT_WAKE_DETECTED_MS
            WakePipelineState.COMMAND_LISTENING -> TIMEOUT_COMMAND_LISTENING_MS
            WakePipelineState.PROCESSING -> if (llmActive) TIMEOUT_PROCESSING_LLM_MS else TIMEOUT_PROCESSING_BASE_MS
            WakePipelineState.SPEAKING -> TIMEOUT_SPEAKING_MS
        }

        fun isValidTransition(from: WakePipelineState, to: WakePipelineState): Boolean {
            return when (from) {
                WakePipelineState.IDLE_WAKE_LISTENING ->
                    to == WakePipelineState.WAKE_DETECTED
                WakePipelineState.WAKE_DETECTED ->
                    to == WakePipelineState.COMMAND_LISTENING ||
                        to == WakePipelineState.PROCESSING || // direct inline command after wake word
                        to == WakePipelineState.IDLE_WAKE_LISTENING
                WakePipelineState.COMMAND_LISTENING ->
                    to == WakePipelineState.PROCESSING ||
                        to == WakePipelineState.SPEAKING || // spoken STT error prompt
                        to == WakePipelineState.IDLE_WAKE_LISTENING
                WakePipelineState.PROCESSING ->
                    to == WakePipelineState.SPEAKING ||
                        to == WakePipelineState.IDLE_WAKE_LISTENING
                WakePipelineState.SPEAKING ->
                    to == WakePipelineState.IDLE_WAKE_LISTENING
            }
        }
    }
}
