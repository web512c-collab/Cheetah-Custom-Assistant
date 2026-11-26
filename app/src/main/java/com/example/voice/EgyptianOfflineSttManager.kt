package com.example.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

data class OfflineSttStatus(
    val isRecognizerAvailable: Boolean,
    val isOnDeviceApiAvailable: Boolean,
    val engineModeLabelAr: String,
    val statusDetailAr: String
)

/**
 * Local/On-Device Speech-to-Text manager configured for Egyptian Colloquial Arabic (ar-EG).
 * Hardened against OEM SpeechRecognizer hangs & AudioRecord hardware contention:
 * - Dispatches all SpeechRecognizer operations onto Main Looper
 * - Guards callbacks with sessionId + AtomicBoolean so stale callbacks never corrupt state
 * - Isolates stopListening/cancel/destroy calls so OEM DeadObjectException never leaks recognizer
 * - Always clears CheetahRuntimeBus.isSttActive on completion, error, timeout, or cancellation
 */
class EgyptianOfflineSttManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var timeoutRunnable: Runnable? = null
    private val sessionSeq = AtomicLong(0L)

    fun isOnDeviceSupported(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        } else {
            SpeechRecognizer.isRecognitionAvailable(appContext)
        }
    }

    fun inspectCapability(): OfflineSttStatus {
        val standardAvail = SpeechRecognizer.isRecognitionAvailable(appContext)
        val onDeviceAvail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        } else {
            false
        }
        return when {
            onDeviceAvail -> OfflineSttStatus(
                isRecognizerAvailable = true,
                isOnDeviceApiAvailable = true,
                engineModeLabelAr = "On-Device STT (Android 12+)",
                statusDetailAr = "التعرف المحلي المباشر (On-Device) متاح. تأكد من تنزيل حزمة العربية (مصر) ar-EG للعمل بدون نت."
            )
            standardAvail -> OfflineSttStatus(
                isRecognizerAvailable = true,
                isOnDeviceApiAvailable = false,
                engineModeLabelAr = "System SpeechRecognizer (Prefer Offline)",
                statusDetailAr = "يستخدم محرك النظام مع طلب الوضع الأوفلاين (EXTRA_PREFER_OFFLINE). إذا لم تتوفر حزمة ar-EG محلياً قد يطلب تنزيلها."
            )
            else -> OfflineSttStatus(
                isRecognizerAvailable = false,
                isOnDeviceApiAvailable = false,
                engineModeLabelAr = "غير متوفر على النظام",
                statusDetailAr = "لا يوجد مزود SpeechRecognizer على الجهاز. يمكنك استخدام الإدخال النصي أو تثبيت حزمة Speech Services."
            )
        }
    }

    fun startListening(
        timeoutMs: Long = 10_000L,
        onRmsDb: (Float) -> Unit,
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("لازم تدي صلاحية المايكروفون عشان أسمعك.")
            return
        }

        val sid = sessionSeq.incrementAndGet()
        mainHandler.post {
            startListeningOnMainThread(sid, timeoutMs, onRmsDb, onPartialResult, onFinalResult, onError)
        }
    }

    suspend fun listenOnce(
        timeoutMs: Long = 9_500L,
        onRmsDb: (Float) -> Unit = {},
        onPartialResult: (String) -> Unit = {}
    ): Result<String> {
        val result = suspendCancellableCoroutine { cont ->
            val completed = AtomicBoolean(false)
            cont.invokeOnCancellation {
                stopAndRelease()
            }
            startListening(
                timeoutMs = timeoutMs,
                onRmsDb = onRmsDb,
                onPartialResult = onPartialResult,
                onFinalResult = { text ->
                    if (completed.compareAndSet(false, true) && cont.isActive) {
                        cont.resume(Result.success(text))
                    }
                },
                onError = { err ->
                    if (completed.compareAndSet(false, true) && cont.isActive) {
                        cont.resume(Result.failure(IllegalStateException(err)))
                    }
                }
            )
        }
        // Brief hardware release settle window before AudioRecord resumes on OEM devices
        delay(200L)
        return result
    }

    private fun startListeningOnMainThread(
        sid: Long,
        timeoutMs: Long,
        onRmsDb: (Float) -> Unit,
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (sid != sessionSeq.get()) return
        destroyRecognizerOnMainThread()

        val created = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            } else {
                null
            }
        }.getOrNull()

        if (created == null) {
            CheetahRuntimeBus.setSttActive(false)
            onError("محرك التعرف على الكلام المحلي مش متوفر على الجهاز ده. تقدر تكتب الأمر مباشرة.")
            return
        }

        val delivered = AtomicBoolean(false)
        val timeoutTask = Runnable {
            if (sid == sessionSeq.get() && delivered.compareAndSet(false, true)) {
                destroyRecognizerOnMainThread()
                onRmsDb(0f)
                onError("انتهت مهلة الاستماع بدون سماع أمر واضح.")
            }
        }
        timeoutRunnable = timeoutTask
        mainHandler.postDelayed(timeoutTask, timeoutMs)

        CheetahRuntimeBus.setSttActive(true)
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                if (sid != sessionSeq.get()) return
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                onRmsDb(normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (sid == sessionSeq.get()) onRmsDb(0f)
            }

            override fun onError(error: Int) {
                if (sid != sessionSeq.get() || !delivered.compareAndSet(false, true)) return
                cancelTimeoutOnMainThread()
                CheetahRuntimeBus.setSttActive(false)
                onRmsDb(0f)
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        "مافهمتش الكلام كويس، ممكن تعيده بالعامية المصرية؟"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "ماسمعتش صوتك يا فندم، جرب تاني."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "محتاج صلاحية المايكروفون."
                    SpeechRecognizer.ERROR_AUDIO ->
                        "مشكلة في تسجيل الصوت من المايكروفون."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        "محرك التعرف مشغول لحظياً، جرب تاني."
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER ->
                        "حزمة التعرف الأوفلاين للعامية المصرية (ar-EG) مش محمّلة على الجهاز. نزل حزمة اللغة أوفلاين من إعدادات النظام أو اكتب الأمر."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "لغة ar-EG مش متوفرة أوفلاين حالياً على الجهاز. تقدر تنزلها من إعدادات الصوت."
                    else ->
                        "حصل خطأ في التعرف المحلي على الصوت (كود $error)."
                }
                destroyRecognizerOnMainThread()
                onError(msg)
            }

            override fun onResults(results: Bundle?) {
                if (sid != sessionSeq.get() || !delivered.compareAndSet(false, true)) return
                cancelTimeoutOnMainThread()
                CheetahRuntimeBus.setSttActive(false)
                onRmsDb(0f)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = matches?.firstOrNull()?.trim().orEmpty()
                destroyRecognizerOnMainThread()
                if (best.isEmpty()) {
                    onError("ماسمعتش أمر واضح.")
                } else {
                    onFinalResult(best)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (sid != sessionSeq.get()) return
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L)
        }

        runCatching {
            created.startListening(intent)
        }.onFailure {
            if (sid == sessionSeq.get() && delivered.compareAndSet(false, true)) {
                cancelTimeoutOnMainThread()
                CheetahRuntimeBus.setSttActive(false)
                destroyRecognizerOnMainThread()
                onError("تعذر تشغيل المايكروفون للتعرف على الكلام.")
            }
        }
    }

    fun stopAndRelease() {
        sessionSeq.incrementAndGet()
        CheetahRuntimeBus.setSttActive(false)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyRecognizerOnMainThread()
        } else {
            mainHandler.post { destroyRecognizerOnMainThread() }
        }
    }

    private fun cancelTimeoutOnMainThread() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun destroyRecognizerOnMainThread() {
        cancelTimeoutOnMainThread()
        CheetahRuntimeBus.setSttActive(false)
        val rec = recognizer
        recognizer = null
        if (rec != null) {
            runCatching { rec.stopListening() }
            runCatching { rec.cancel() }
            runCatching { rec.destroy() }
        }
    }
}
