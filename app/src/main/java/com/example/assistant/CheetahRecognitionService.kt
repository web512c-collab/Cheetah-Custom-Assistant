package com.example.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.example.voice.EgyptianOfflineSttManager

/**
 * Local RecognitionService required by Android's <voice-interaction-service> contract.
 * Bridges system recognition requests to EgyptianOfflineSttManager without any cloud endpoint.
 */
class CheetahRecognitionService : RecognitionService() {

    private var sttManager: EgyptianOfflineSttManager? = null

    override fun onCreate() {
        super.onCreate()
        sttManager = EgyptianOfflineSttManager(applicationContext)
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val manager = sttManager ?: run {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        manager.startListening(
            onRmsDb = { level ->
                runCatching { listener?.rmsChanged(level * 10f) }
            },
            onPartialResult = { partial ->
                val b = Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(partial))
                }
                runCatching { listener?.partialResults(b) }
            },
            onFinalResult = { finalText ->
                val b = Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(finalText))
                }
                runCatching { listener?.results(b) }
            },
            onError = {
                runCatching { listener?.error(SpeechRecognizer.ERROR_NO_MATCH) }
            }
        )
    }

    override fun onCancel(listener: Callback?) {
        sttManager?.stopAndRelease()
    }

    override fun onStopListening(listener: Callback?) {
        sttManager?.stopAndRelease()
    }

    override fun onDestroy() {
        sttManager?.stopAndRelease()
        sttManager = null
        super.onDestroy()
    }
}
