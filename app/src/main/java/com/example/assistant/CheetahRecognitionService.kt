package com.example.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Local RecognitionService required by Android's <voice-interaction-service> contract.
 * Reports recognition readiness locally without any cloud endpoint.
 */
class CheetahRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // Primary STT is driven directly via EgyptianOfflineSttManager in Session/Activity.
        listener?.error(SpeechRecognizer.ERROR_NO_MATCH)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
