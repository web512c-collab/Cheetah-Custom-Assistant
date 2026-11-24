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
import com.example.controller.DeviceActionExecutor
import com.example.controller.PermissionHealthChecker
import com.example.core.model.GuardrailVerdict
import com.example.data.SettingsRepository
import com.example.guardrails.CommandGuardrailValidator
import com.example.intelligence.EgyptianColloquialNluParser
import com.example.intelligence.JarvisEgyptianResponder
import com.example.voice.EgyptianOfflineSttManager
import com.example.voice.EgyptianTtsManager
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
    private val sttManager = EgyptianOfflineSttManager(context)
    private val ttsManager = EgyptianTtsManager(context)

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
        transcriptView?.text = "جاري الاستماع محلياً (ar-EG)..."
        sttManager.startListening(
            onRmsDb = {},
            onPartialResult = { partial ->
                transcriptView?.text = "سمعت: $partial"
            },
            onFinalResult = { finalText ->
                transcriptView?.text = "أمرك: $finalText"
                processCommand(finalText)
            },
            onError = { errMsg ->
                transcriptView?.text = errMsg
            }
        )
    }

    private fun processCommand(rawText: String) {
        scope.launch {
            val settings = settingsRepo.currentSettings()
            val permissions = PermissionHealthChecker.inspect(context)
            val (_, intent) = EgyptianColloquialNluParser.parse(rawText, settings.wakePhrase)

            when (val verdict = CommandGuardrailValidator.validate(
                intent = intent,
                settings = settings,
                permissions = permissions,
                voiceMatchPassed = true
            )) {
                is GuardrailVerdict.Denied -> {
                    val reply = JarvisEgyptianResponder.formatGuardrailBlockReply(
                        verdict.reasonAr,
                        settings.personaTone
                    )
                    statusView?.text = reply
                    ttsManager.speak(reply, settings.ttsSpeechRate, settings.ttsPitch)
                }

                is GuardrailVerdict.Allowed -> {
                    val result = DeviceActionExecutor.execute(
                        context,
                        verdict.intent,
                        settings.personaTone
                    )
                    statusView?.text = result.replyAr
                    ttsManager.speak(result.replyAr, settings.ttsSpeechRate, settings.ttsPitch)
                }
            }
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
