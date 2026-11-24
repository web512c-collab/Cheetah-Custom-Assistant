package com.example.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

data class TtsStatus(
    val isReady: Boolean = false,
    val activeLocaleTag: String = "ar-EG",
    val hasEgyptianVoice: Boolean = false,
    val statusDetailAr: String = "جاري تجهيز محرك الصوت المصري..."
)

/**
 * Local Text-to-Speech manager configured for Egyptian Colloquial Arabic (ar-EG)
 * with graceful fallback to general Arabic (ar) if the device lacks an ar-EG pack.
 */
class EgyptianTtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)

    private val _status = MutableStateFlow(TtsStatus())
    val status: StateFlow<TtsStatus> = _status.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var onSpeechDone: (() -> Unit)? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            _status.value = TtsStatus(
                isReady = false,
                statusDetailAr = "محرك النطق المحلي مش متاح حالياً على الجهاز."
            )
            return
        }

        val engine = tts ?: return
        val egyptLocale = Locale.forLanguageTag("ar-EG")
        val arFallback = Locale.forLanguageTag("ar")

        val egResult = engine.isLanguageAvailable(egyptLocale)
        val hasEgypt = egResult >= TextToSpeech.LANG_AVAILABLE

        val chosenLocale = when {
            hasEgypt -> egyptLocale
            engine.isLanguageAvailable(arFallback) >= TextToSpeech.LANG_AVAILABLE -> arFallback
            else -> Locale.getDefault()
        }

        engine.language = chosenLocale
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
                onSpeechDone?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                onSpeechDone?.invoke()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _isSpeaking.value = false
                onSpeechDone?.invoke()
            }
        })

        _status.value = TtsStatus(
            isReady = true,
            activeLocaleTag = chosenLocale.toLanguageTag(),
            hasEgyptianVoice = hasEgypt,
            statusDetailAr = if (hasEgypt) {
                "صوت العامية المصرية (ar-EG) شغال أوفلاين بنجاح."
            } else {
                "شغال بالصوت العربي المتاح (${chosenLocale.toLanguageTag()}) — تقدر تنزل حزمة ar-EG من إعدادات TTS."
            }
        )
    }

    fun speak(
        text: String,
        rate: Float = 1.0f,
        pitch: Float = 0.96f,
        onComplete: (() -> Unit)? = null
    ) {
        val engine = tts ?: run {
            onComplete?.invoke()
            return
        }
        if (!_status.value.isReady) {
            onComplete?.invoke()
            return
        }
        onSpeechDone = onComplete
        engine.setSpeechRate(rate.coerceIn(0.6f, 1.5f))
        engine.setPitch(pitch.coerceIn(0.6f, 1.4f))
        val utteranceId = UUID.randomUUID().toString()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
