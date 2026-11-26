package com.example.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.example.R
import com.example.data.ConversationMemoryStore
import com.example.data.SettingsRepository
import com.example.intelligence.AssistantCommandProcessor
import com.example.llm.LocalLlmManager
import com.example.voice.EgyptianOfflineSttManager
import com.example.voice.EgyptianTtsManager
import com.example.voice.MicOwner
import com.example.voice.MicrophoneArbitrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * System VoiceInteractionSession invoked over any app when the user triggers
 * the device's Assistant gesture / Home long-press.
 */
class CheetahVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settingsRepo = SettingsRepository(context)
    private val memoryStore = ConversationMemoryStore(context)
    private val sttManager = EgyptianOfflineSttManager(context)
    private val ttsManager = EgyptianTtsManager(context)
    private val localLlmManager = LocalLlmManager(context.applicationContext, settingsRepo)
    private val commandProcessor = AssistantCommandProcessor(context.applicationContext, memoryStore, localLlmManager)

    private var statusView: TextView? = null
    private var transcriptView: TextView? = null
    private var inputField: EditText? = null

    override fun onCreateContentView(): View {
        val root = LayoutInflater.from(context).inflate(R.layout.assistant_session_overlay, null)
        statusView = root.findViewById(R.id.session_status_text)
        transcriptView = root.findViewById(R.id.session_transcript_text)
        inputField = root.findViewById(R.id.session_command_input)

        root.findViewById<Button>(R.id.session_close_btn).setOnClickListener {
            hide()
        }
        root.findViewById<Button>(R.id.session_mic_btn).setOnClickListener {
            startVoiceCapture()
        }
        root.findViewById<Button>(R.id.session_send_btn).setOnClickListener {
            val typed = inputField?.text?.toString()?.trim().orEmpty()
            if (typed.isNotEmpty()) {
                inputField?.setText("")
                processCommand(typed)
            }
        }
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        statusView?.text = "تحت أمرك يا فندم... قول أو اكتب أمرك بالعامية المصرية"
        startVoiceCapture()
    }

    private fun startVoiceCapture() {
        scope.launch {
            transcriptView?.text = "جاري الاستماع محلياً (ar-EG)..."
            val res = MicrophoneArbitrator.withMicAccess(MicOwner.ASSIST_SESSION_STT) {
                sttManager.listenOnce(
                    timeoutMs = 10_000L,
                    onPartialResult = { partial ->
                        transcriptView?.text = "سمعت: $partial"
                    }
                )
            }
            res.fold(
                onSuccess = { finalText ->
                    transcriptView?.text = "أمرك: $finalText"
                    processCommand(finalText)
                },
                onFailure = { err ->
                    transcriptView?.text = err.message ?: "تعذر الاستماع"
                }
            )
        }
    }

    private fun processCommand(rawText: String) {
        scope.launch {
            val settings = settingsRepo.currentSettings()
            ttsManager.applyVoicePreferences(settings.voiceGender, settings.selectedTtsVoiceName)
            val outcome = commandProcessor.process(
                rawInput = rawText,
                settings = settings,
                voiceMatchPassed = true,
                onStopSpeech = { ttsManager.stop() }
            )
            statusView?.text = outcome.replyAr
            ttsManager.speak(
                text = outcome.spokenReplyAr,
                rate = settings.ttsSpeechRate,
                pitch = settings.ttsPitch,
                voiceGender = settings.voiceGender,
                selectedVoiceName = settings.selectedTtsVoiceName
            )
        }
    }

    override fun onHide() {
        sttManager.stopAndRelease()
        ttsManager.stop()
        super.onHide()
    }

    override fun onDestroy() {
        sttManager.stopAndRelease()
        ttsManager.release()
        scope.cancel()
        super.onDestroy()
    }
}
