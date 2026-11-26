package com.example.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.core.model.AssistantGender
import com.example.core.model.TtsVoiceCandidate
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
    val activeVoiceGender: AssistantGender? = null,
    val usedPitchFallback: Boolean = false,
    val availableArabicVoices: List<TtsVoiceCandidate> = emptyList(),
    val statusDetailAr: String = "جاري تجهيز محرك الصوت المصري..."
)

/**
 * Local Text-to-Speech manager configured for Egyptian Colloquial Arabic (ar-EG)
 * with:
 * - Safe utteranceId matching & guaranteed focus/callback cleanup on timeout (BUG A2)
 * - Voice enumeration, heuristic Female/Male Arabic voice selection, manual override,
 *   and honest pitch/rate fallback notice when only one voice gender is installed (PART C)
 * - Sentence-by-sentence queued playback (`QUEUE_ADD`) for local LLM streaming replies (PART B)
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

    @Volatile
    private var activeUtteranceId: String? = null

    @Volatile
    private var onSpeechDone: (() -> Unit)? = null

    private var preferredGender: AssistantGender = AssistantGender.MALE
    private var manualVoiceName: String = ""

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

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == null || utteranceId == activeUtteranceId) {
                    finishSpeechCallback()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == null || utteranceId == activeUtteranceId) {
                    finishSpeechCallback()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == null || utteranceId == activeUtteranceId) {
                    finishSpeechCallback()
                }
            }
        })

        applyVoicePreferencesInternal(engine, preferredGender, manualVoiceName)
    }

    fun applyVoicePreferences(voiceGender: AssistantGender, selectedVoiceName: String) {
        preferredGender = voiceGender
        manualVoiceName = selectedVoiceName.trim()
        val engine = tts
        if (engine != null && _status.value.isReady) {
            applyVoicePreferencesInternal(engine, preferredGender, manualVoiceName)
        }
    }

    private fun applyVoicePreferencesInternal(
        engine: TextToSpeech,
        voiceGender: AssistantGender,
        selectedVoiceName: String
    ) {
        val egyptLocale = Locale.forLanguageTag("ar-EG")
        val arFallback = Locale.forLanguageTag("ar")
        val voices = runCatching { engine.voices?.toList().orEmpty() }.getOrDefault(emptyList())

        val arabicVoices = voices.filter { it.locale.language.equals("ar", ignoreCase = true) }
        val candidates = arabicVoices.map { voice ->
            TtsVoiceCandidate(
                name = voice.name,
                localeTag = voice.locale.toLanguageTag(),
                detectedGender = detectVoiceGender(voice),
                isOffline = !voice.isNetworkConnectionRequired,
                qualityScore = voice.quality
            )
        }.sortedWith(
            compareByDescending<TtsVoiceCandidate> { it.localeTag.equals("ar-EG", ignoreCase = true) }
                .thenByDescending { it.isOffline }
                .thenByDescending { it.qualityScore }
                .thenBy { it.name }
        )

        val manualMatch = selectedVoiceName.takeIf { it.isNotBlank() }?.let { wanted ->
            arabicVoices.firstOrNull { it.name == wanted } ?: voices.firstOrNull { it.name == wanted }
        }

        val matchingGenderVoices = arabicVoices.filter { detectVoiceGender(it) == voiceGender }
        val autoVoice = pickBestEgyptianOrArabicVoice(matchingGenderVoices)
            ?: pickBestEgyptianOrArabicVoice(arabicVoices)

        val selectedVoice = manualMatch ?: autoVoice
        val detectedSelectedGender = selectedVoice?.let { detectVoiceGender(it) }
        val matchedRequestedGender = (manualMatch != null) ||
            (detectedSelectedGender == voiceGender)
        val usedPitchFallback = !matchedRequestedGender

        val hasEgypt = arabicVoices.any { isEgyptianVoice(it) } ||
            engine.isLanguageAvailable(egyptLocale) >= TextToSpeech.LANG_AVAILABLE
        val hasOffline = arabicVoices.any { !it.isNetworkConnectionRequired }

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

        val detail = buildString {
            when {
                selectedVoice != null && isEgyptianVoice(selectedVoice) && !selectedVoice.isNetworkConnectionRequired ->
                    append("صوت مصري أوفلاين جاهز (${selectedVoice.name}).")
                hasEgypt ->
                    append("صوت عربي متاح (${chosenLocale.toLanguageTag()}).")
                hasOffline ->
                    append("صوت عربي أوفلاين (${chosenLocale.toLanguageTag()}) — يفضل تنزيل ar-EG من إعدادات TTS.")
                else ->
                    append("صوت النظام (${chosenLocale.toLanguageTag()}) — نزّل حزمة العربية من إعدادات TTS.")
            }
            if (usedPitchFallback) {
                val wantedLabel = if (voiceGender == AssistantGender.FEMALE) "أنثوي" else "رجالي"
                append(" لم يُعثر على صوت عربي $wantedLabel مثبّت، وتم تفعيل تعديل طبقة الصوت (Pitch) كبديل.")
            }
        }

        _status.value = TtsStatus(
            isReady = true,
            activeLocaleTag = chosenLocale.toLanguageTag(),
            hasEgyptianVoice = hasEgypt,
            hasOfflineVoice = hasOffline,
            selectedVoiceName = selectedVoice?.name.orEmpty(),
            activeVoiceGender = detectedSelectedGender,
            usedPitchFallback = usedPitchFallback,
            availableArabicVoices = candidates,
            statusDetailAr = detail
        )
    }

    private fun pickBestEgyptianOrArabicVoice(list: List<Voice>): Voice? {
        if (list.isEmpty()) return null
        return list.firstOrNull { isEgyptianVoice(it) && !it.isNetworkConnectionRequired }
            ?: list.firstOrNull { isEgyptianVoice(it) }
            ?: list.firstOrNull { !it.isNetworkConnectionRequired }
            ?: list.firstOrNull()
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
        voiceGender: AssistantGender = preferredGender,
        selectedVoiceName: String = manualVoiceName,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
        onComplete: (() -> Unit)? = null
    ) {
        val engine = tts
        if (engine == null || !_status.value.isReady || text.isBlank()) {
            onComplete?.invoke()
            return
        }
        if (voiceGender != preferredGender || selectedVoiceName != manualVoiceName) {
            applyVoicePreferences(voiceGender, selectedVoiceName)
        }

        val cleaned = prepareForEgyptianSpeech(text)
        if (cleaned.isEmpty()) {
            onComplete?.invoke()
            return
        }

        val effectivePitch = computeEffectivePitch(
            basePitch = pitch,
            wantedGender = voiceGender,
            usedPitchFallback = _status.value.usedPitchFallback
        )
        val effectiveRate = rate.coerceIn(0.6f, 1.5f)

        val utteranceId = UUID.randomUUID().toString()
        activeUtteranceId = utteranceId
        onSpeechDone = onComplete
        requestAssistantAudioFocus()
        engine.setSpeechRate(effectiveRate)
        engine.setPitch(effectivePitch)

        val res = runCatching {
            engine.speak(cleaned, queueMode, null, utteranceId)
        }.getOrElse { TextToSpeech.ERROR }

        if (res != TextToSpeech.SUCCESS) {
            finishSpeechCallback()
        }
    }

    suspend fun speakAndAwait(
        text: String,
        rate: Float = 1.0f,
        pitch: Float = 0.96f,
        voiceGender: AssistantGender = preferredGender,
        selectedVoiceName: String = manualVoiceName,
        timeoutMs: Long = 14_000L
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val resumed = AtomicBoolean(false)
        val timeoutTask = Runnable {
            if (resumed.compareAndSet(false, true)) {
                stop()
                if (cont.isActive) {
                    cont.resume(Unit)
                }
            }
        }
        mainHandler.postDelayed(timeoutTask, timeoutMs)

        cont.invokeOnCancellation {
            mainHandler.removeCallbacks(timeoutTask)
            stop()
        }

        speak(
            text = text,
            rate = rate,
            pitch = pitch,
            voiceGender = voiceGender,
            selectedVoiceName = selectedVoiceName,
            queueMode = TextToSpeech.QUEUE_FLUSH
        ) {
            mainHandler.removeCallbacks(timeoutTask)
            if (resumed.compareAndSet(false, true) && cont.isActive) {
                cont.resume(Unit)
            }
        }
    }

    /**
     * Enqueues a sentence for low-latency streaming TTS playback during LLM generation.
     */
    fun enqueueStreamingSentence(
        sentence: String,
        rate: Float,
        pitch: Float,
        voiceGender: AssistantGender,
        selectedVoiceName: String,
        isFirstChunk: Boolean
    ) {
        val mode = if (isFirstChunk) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        speak(
            text = sentence,
            rate = rate,
            pitch = pitch,
            voiceGender = voiceGender,
            selectedVoiceName = selectedVoiceName,
            queueMode = mode,
            onComplete = null
        )
    }

    fun stop() {
        activeUtteranceId = null
        runCatching { tts?.stop() }
        _isSpeaking.value = false
        onSpeechDone = null
        abandonAssistantAudioFocus()
    }

    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun finishSpeechCallback() {
        activeUtteranceId = null
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
        private val FEMALE_HINTS = listOf(
            "female", "woman", "girl", "sara", "salma", "laila", "layla", "mariam",
            "nadia", "mona", "nour", "amira", "yasmin", "zeinab", "rana", "hala",
            "ar-xa-x-arz", "ar-eg-x-ard", "ar-eg-x-egf", "ar-xa-x-ard",
            "#female", "-f-", "_f_"
        )
        private val MALE_HINTS = listOf(
            "male", "man", "boy", "tarek", "omar", "ahmed", "karim", "hamed", "maged",
            "rami", "youssef", "naim", "ar-xa-x-arc", "ar-xa-x-are", "ar-eg-x-egm",
            "#male", "-m-", "_m_"
        )

        /**
         * Heuristic voice gender detection using Android Voice metadata:
         * 1. Voice.features tags (e.g. "gender=female" / "gender=male" exposed by some OEMs)
         * 2. Known Google/Samsung/RHVoice/eSpeak voice identifier & name tokens
         * Returns null when the engine gives no reliable gender clue.
         */
        fun detectVoiceGender(voice: Voice): AssistantGender? {
            val featureText = runCatching { voice.features?.joinToString(" ").orEmpty() }
                .getOrDefault("")
            return detectVoiceGenderFromMetadata(voice.name, featureText)
        }

        fun detectVoiceGenderFromMetadata(voiceName: String, featuresText: String = ""): AssistantGender? {
            val hay = "${voiceName.lowercase(Locale.US)} ${featuresText.lowercase(Locale.US)}"
            if (FEMALE_HINTS.any { hay.contains(it) }) return AssistantGender.FEMALE
            if (MALE_HINTS.any { hay.contains(it) }) return AssistantGender.MALE
            return null
        }

        fun computeEffectivePitch(
            basePitch: Float,
            wantedGender: AssistantGender,
            usedPitchFallback: Boolean
        ): Float {
            val adjusted = if (!usedPitchFallback) {
                basePitch
            } else {
                when (wantedGender) {
                    AssistantGender.FEMALE -> (basePitch * 1.18f).coerceAtLeast(1.10f)
                    AssistantGender.MALE -> (basePitch * 0.88f).coerceAtMost(0.92f)
                }
            }
            return adjusted.coerceIn(0.6f, 1.4f)
        }

        fun buildTtsSystemSettingsIntent(): Intent {
            return Intent("com.android.settings.TTS_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        fun prepareForEgyptianSpeech(input: String): String {
            return input
                .replace("«", " ")
                .replace("»", " ")
                .replace("|", "، ")
                .replace("•", "، ")
                .replace("ar-EG", "العربية المصرية")
                .replace("J.A.R.V.I.S.", "جارفيس")
                .replace(Regex("\\*+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
