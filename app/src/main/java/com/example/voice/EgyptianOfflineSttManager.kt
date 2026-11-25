package com.example.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * Local/On-Device Speech-to-Text manager configured for Egyptian Colloquial Arabic (ar-EG).
 * Uses createOnDeviceSpeechRecognizer on Android 12+ (API 31+) when supported,
 * and falls back to SpeechRecognizer with EXTRA_PREFER_OFFLINE = true on Android 7.0+.
 */
class EgyptianOfflineSttManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun isOnDeviceSupported(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } else {
            SpeechRecognizer.isRecognitionAvailable(context)
        }
    }

    fun startListening(
        onRmsDb: (Float) -> Unit,
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("لازم تدي صلاحية المايكروفون عشان أسمعك.")
            return
        }

        stopAndRelease()

        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            onError("محرك التعرف على الكلام المحلي مش متوفر على الجهاز ده. تقدر تكتب الأمر مباشرة.")
            return
        }

        CheetahRuntimeBus.setSttActive(true)
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                onRmsDb(normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                onRmsDb(0f)
            }

            override fun onError(error: Int) {
                CheetahRuntimeBus.setSttActive(false)
                onRmsDb(0f)
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "مافهمتش الكلام كويس، ممكن تعيده بالعامية المصرية؟"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ماسمعتش صوتك يا فندم، جرب تاني."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "محتاج صلاحية المايكروفون."
                    SpeechRecognizer.ERROR_AUDIO -> "مشكلة في تسجيل الصوت من المايكروفون."
                    else -> "حصل خطأ في التعرف المحلي على الصوت (كود $error). جرب كتابة الأمر أو نزل حزمة ar-EG أوفلاين."
                }
                onError(msg)
            }

            override fun onResults(results: Bundle?) {
                CheetahRuntimeBus.setSttActive(false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = matches?.firstOrNull()?.trim().orEmpty()
                if (best.isEmpty()) {
                    onError("ماسمعتش أمر واضح.")
                } else {
                    onFinalResult(best)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotEmpty()) {
                    onPartialResult(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-EG")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ar-EG")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        runCatching {
            created.startListening(intent)
        }.onFailure {
            CheetahRuntimeBus.setSttActive(false)
            onError("تعذر تشغيل المايكروفون للتعرف على الكلام.")
        }
    }

    fun stopAndRelease() {
        CheetahRuntimeBus.setSttActive(false)
        runCatching {
            recognizer?.stopListening()
            recognizer?.destroy()
        }
        recognizer = null
    }
}
