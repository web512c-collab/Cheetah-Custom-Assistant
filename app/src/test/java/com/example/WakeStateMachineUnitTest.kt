package com.example

import com.example.core.model.TransitionLogEntry
import com.example.core.model.WakePipelineState
import com.example.voice.MicOwner
import com.example.voice.MicrophoneArbitrator
import com.example.voice.WakeStateMachine
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class WakeStateMachineUnitTest {

    @Test
    fun `normal 5-state wake cycle returns cleanly to IDLE_WAKE_LISTENING`() {
        val now = AtomicLong(1_000L)
        val logs = mutableListOf<TransitionLogEntry>()
        val sm = WakeStateMachine(clockMs = { now.get() }, onTransition = { logs.add(it) })

        assertEquals(WakePipelineState.IDLE_WAKE_LISTENING, sm.currentState)
        assertTrue(sm.transition(WakePipelineState.WAKE_DETECTED, "wake_ok"))
        now.addAndGet(400L)
        assertTrue(sm.transition(WakePipelineState.COMMAND_LISTENING, "stt_open"))
        now.addAndGet(2_000L)
        assertTrue(sm.transition(WakePipelineState.PROCESSING, "nlu_exec"))
        now.addAndGet(500L)
        assertTrue(sm.transition(WakePipelineState.SPEAKING, "tts_reply"))
        now.addAndGet(1_200L)
        assertTrue(sm.transition(WakePipelineState.IDLE_WAKE_LISTENING, "done"))

        assertEquals(WakePipelineState.IDLE_WAKE_LISTENING, sm.currentState)
        assertEquals(5, logs.size)
        assertTrue(logs.none { it.isError })
    }

    @Test
    fun `invalid transition forces recovery back to IDLE_WAKE_LISTENING`() {
        val logs = mutableListOf<TransitionLogEntry>()
        val sm = WakeStateMachine(onTransition = { logs.add(it) })

        // Cannot jump straight from IDLE_WAKE_LISTENING to SPEAKING
        val accepted = sm.transition(WakePipelineState.SPEAKING, "bad_jump")
        assertFalse(accepted)
        assertEquals(WakePipelineState.IDLE_WAKE_LISTENING, sm.currentState)
        assertTrue(logs.last().isError)
    }

    @Test
    fun `watchdog timeout fires across WAKE_DETECTED, COMMAND_LISTENING, PROCESSING, SPEAKING`() {
        val now = AtomicLong(10_000L)
        val logs = mutableListOf<TransitionLogEntry>()
        val sm = WakeStateMachine(clockMs = { now.get() }, onTransition = { logs.add(it) })

        // 1. WAKE_DETECTED timeout
        sm.transition(WakePipelineState.WAKE_DETECTED, "w1")
        now.addAndGet(WakeStateMachine.TIMEOUT_WAKE_DETECTED_MS + 100L)
        assertTrue(sm.checkWatchdog())
        assertEquals(WakePipelineState.IDLE_WAKE_LISTENING, sm.currentState)

        // 2. COMMAND_LISTENING timeout
        sm.transition(WakePipelineState.WAKE_DETECTED, "w2")
        sm.transition(WakePipelineState.COMMAND_LISTENING, "c2")
        now.addAndGet(WakeStateMachine.TIMEOUT_COMMAND_LISTENING_MS + 100L)
        assertTrue(sm.checkWatchdog())
        assertEquals(WakePipelineState.IDLE_WAKE_LISTENING, sm.currentState)

        // 3. PROCESSING base vs LLM timeout
        sm.transition(WakePipelineState.WAKE_DETECTED, "w3")
        sm.transition(WakePipelineState.PROCESSING, "p3")
        now.addAndGet(WakeStateMachine.TIMEOUT_PROCESSING_BASE_MS + 100L)
        // With llmActive=true it should NOT timeout yet (65s budget)
        assertFalse(sm.checkWatchdog(llmActive = true))
        // With llmActive=false it SHOULD timeout
        assertTrue(sm.checkWatchdog(llmActive = false))
        assertEquals(WakePipelineState.IDLE_WAKE_LISTENING, sm.currentState)

        // 4. SPEAKING timeout
        sm.transition(WakePipelineState.WAKE_DETECTED, "w4")
        sm.transition(WakePipelineState.PROCESSING, "p4")
        sm.transition(WakePipelineState.SPEAKING, "s4")
        now.addAndGet(WakeStateMachine.TIMEOUT_SPEAKING_MS + 100L)
        assertTrue(sm.checkWatchdog())
        assertEquals(WakePipelineState.IDLE_WAKE_LISTENING, sm.currentState)
    }

    @Test
    fun `forceResetToIdle recovers even if try-finally catches unexpected failure`() {
        val sm = WakeStateMachine()
        sm.transition(WakePipelineState.WAKE_DETECTED, "wake")
        sm.transition(WakePipelineState.COMMAND_LISTENING, "cmd")

        try {
            throw IllegalStateException("simulated STT crash")
        } catch (_: Throwable) {
            // handled
        } finally {
            sm.forceResetToIdle("cycle_cleanup", isError = true)
        }
        assertEquals(WakePipelineState.IDLE_WAKE_LISTENING, sm.currentState)
    }

    @Test
    fun `microphone arbitrator enforces single owner mutual exclusion`() = runTest {
        MicrophoneArbitrator.releaseWakeAudioRecordIfHeld()
        assertTrue(MicrophoneArbitrator.tryMarkWakeAudioRecordActive())
        assertEquals(MicOwner.WAKE_AUDIO_RECORD, MicrophoneArbitrator.currentOwner.value)

        // Foreground requester signals yieldWakeForForeground
        val foregroundJob = async {
            MicrophoneArbitrator.withMicAccess(MicOwner.UI_PTT_STT, settleDelayMs = 0L) {
                MicrophoneArbitrator.currentOwner.value
            }
        }
        delay(20L)
        assertTrue(MicrophoneArbitrator.yieldWakeForForeground.value)
        MicrophoneArbitrator.releaseWakeAudioRecordIfHeld()

        val observedOwner = foregroundJob.await()
        assertEquals(MicOwner.UI_PTT_STT, observedOwner)
        assertFalse(MicrophoneArbitrator.yieldWakeForForeground.value)
        assertEquals(MicOwner.NONE, MicrophoneArbitrator.currentOwner.value)
    }
}
