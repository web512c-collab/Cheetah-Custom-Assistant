package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class TtsStatus(
    val isReady: Boolean = false,
    val activeLocaleTag: String = "ar-EG",
    val hasEgyptianVoice: Boolean = false,
    val hasOfflineVoice: Boolean = false,
    val selectedVoiceName: String = "",
    val statusDetailAr: String = "جاري تجهيز محرك الصوت المصري..."
)

/**
 * Local Text-to-Speech manager configured for Egyptian Colloquial Arabic (ar-EG)
 * with explicit offline voice selection, AudioAttributes (USAGE_ASSISTANT), and AudioFocus management.
 */
class EgyptianTtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var focusRequest: AudioFocusRequest? = null

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
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        val egyptLocale = Locale.forLanguageTag("ar-EG")
        val arFallback = Locale.forLanguageTag("ar")

        val voices = runCatching { engine.voices?.toList().orEmpty() }.getOrDefault(emptyList())
        val offlineEgyptVoice = voices.firstOrNull { isEgyptianVoice(it) && !it.isNetworkConnectionRequired }
        val anyEgyptVoice = offlineEgyptVoice ?: voices.firstOrNull { isEgyptianVoice(it) }
        val offlineArabicVoice = voices.firstOrNull { it.locale.language == "ar" && !it.isNetworkConnectionRequired }
        val anyArabicVoice = offlineArabicVoice ?: voices.firstOrNull { it.locale.language == "ar" }

        val selectedVoice = anyEgyptVoice ?: anyArabicVoice
        val hasEgypt = anyEgyptVoice != null ||
            engine.isLanguageAvailable(egyptLocale) >= TextToSpeech.LANG_AVAILABLE
        val hasOffline = offlineEgyptVoice != null || offlineArabicVoice != null

        val chosenLocale = when {
            selectedVoice != null -> selectedVoice.locale
            hasEgypt -> egyptLocale
            engine.isLanguageAvailable(arFallback) >= TextToSpeech.LANG_AVAILABLE -> arFallback
            else -> Locale.getDefault()
        }

        engine.language = chosenLocale
        if (selectedVoice != null) {
            runCatching { engine.voice = selectedVoice }
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                finishSpeechCallback()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finishSpeechCallback()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finishSpeechCallback()
            }
        })

        _status.value = TtsStatus(
            isReady = true,
            activeLocaleTag = chosenLocale.toLanguageTag(),
            hasEgyptianVoice = hasEgypt,
            hasOfflineVoice = hasOffline,
            selectedVoiceName = selectedVoice?.name.orEmpty(),
            statusDetailAr = when {
                offlineEgyptVoice != null ->
                    "صوت العامية المصرية (ar-EG) الأوفلاين مثبت وجاهز."
                hasEgypt ->
                    "صوت ar-EG متاح (تحقق من تنزيل الصوت المحلي بدون نت في إعدادات TTS)."
                offlineArabicVoice != null ->
                    "شغال بصوت عربي أوفلاين (${chosenLocale.toLanguageTag()}) — لتعزيز اللكنة المصرية نزل حزمة ar-EG."
                else ->
                    "شغال بالصوت المتاح (${chosenLocale.toLanguageTag()}) — يفضل تنزيل حزمة العربية المصرية أوفلاين."
            }
        )
    }

    private fun isEgyptianVoice(voice: Voice): Boolean {
        val loc = voice.locale
        return loc.language.equals("ar", ignoreCase = true) &&
            loc.country.equals("EG", ignoreCase = true)
    }

    fun speak(
        text: String,
        rate: Float = 1.0f,
        pitch: Float = 0.96f,
        onComplete: (() -> Unit)? = null
    ) {
        val engine = tts
        if (engine == null || !_status.value.isReady || text.isBlank()) {
            onComplete?.invoke()
            return
        }
        val cleaned = prepareForEgyptianSpeech(text)
        onSpeechDone = onComplete
        requestAssistantAudioFocus()
        engine.setSpeechRate(rate.coerceIn(0.6f, 1.5f))
        engine.setPitch(pitch.coerceIn(0.6f, 1.4f))
        val utteranceId = UUID.randomUUID().toString()
        val res = engine.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (res != TextToSpeech.SUCCESS) {
            finishSpeechCallback()
        }
    }

    suspend fun speakAndAwait(
        text: String,
        rate: Float = 1.0f,
        pitch: Float = 0.96f,
        timeoutMs: Long = 8_500L
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val resumed = AtomicBoolean(false)
        val timeoutTask = Runnable {
            if (resumed.compareAndSet(false, true) && cont.isActive) {
                abandonAssistantAudioFocus()
                cont.resume(Unit)
            }
        }
        mainHandler.postDelayed(timeoutTask, timeoutMs)

        cont.invokeOnCancellation {
            mainHandler.removeCallbacks(timeoutTask)
            stop()
        }

        speak(text, rate, pitch) {
            mainHandler.removeCallbacks(timeoutTask)
            if (resumed.compareAndSet(false, true) && cont.isActive) {
                cont.resume(Unit)
            }
        }
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
        onSpeechDone = null
        abandonAssistantAudioFocus()
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun finishSpeechCallback() {
        _isSpeaking.value = false
        abandonAssistantAudioFocus()
        val cb = onSpeechDone
        onSpeechDone = null
        cb?.invoke()
    }

    private fun requestAssistantAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .build()
                .also { focusRequest = it }
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAssistantAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    companion object {
        fun prepareForEgyptianSpeech(input: String): String {
            return input
                .replace("«", " ")
                .replace("»", " ")
                .replace("|", "، ")
                .replace("•", "، ")
                .replace("ar-EG", "العربية المصرية")
                .replace("J.A.R.V.I.S.", "جارفيس")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
