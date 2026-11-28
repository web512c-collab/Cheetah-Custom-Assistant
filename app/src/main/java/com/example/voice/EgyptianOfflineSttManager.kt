package com.example.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.example.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.math.sqrt

data class OfflineSttStatus(
    val isRecognizerAvailable: Boolean,
    val isOnDeviceApiAvailable: Boolean,
    val engineModeLabelAr: String,
    val statusDetailAr: String,
    val hasModelConfigured: Boolean = false,
    val isModelLoaded: Boolean = false,
    val modelName: String = "",
    val modelDir: String = "",
    val modelSizeBytes: Long = 0L,
    val modelLanguage: String = "ar",
    val lastErrorAr: String? = null
)

data class ImportedSttModel(
    val modelDir: String,
    val displayName: String,
    val sizeBytes: Long,
    val languageTag: String
)

/**
 * Pure offline on-device Speech-to-Text manager powered by Vosk Android + AudioRecord 16kHz PCM
 * with energy/hangover VAD endpointing.
 * Zero dependency on android.speech.SpeechRecognizer or Google Speech Services.
 */
class EgyptianOfflineSttManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val settingsRepo by lazy { SettingsRepository(appContext) }
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionSeq = AtomicLong(0L)
    private val modelLock = Any()

    @Volatile
    private var captureJob: Job? = null

    @Volatile
    private var activeModel: Model? = null

    @Volatile
    private var activeModelDir: String = ""

    @Volatile
    private var lastLoadErrorAr: String? = null

    fun isOnDeviceSupported(): Boolean = resolveConfiguredModelDir() != null

    fun resolveConfiguredModelDir(explicitDir: String = ""): File? {
        val candidates = buildList {
            if (explicitDir.isNotBlank()) add(File(explicitDir))
            val modelsBase = File(appContext.filesDir, MODELS_DIR_NAME)
            if (modelsBase.exists() && modelsBase.isDirectory) {
                modelsBase.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".staging_") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { add(it) }
            }
        }
        return candidates.firstOrNull { containsVoskModelFiles(it) }
    }

    fun inspectCapability(
        configuredDir: String = "",
        configuredName: String = "",
        configuredSizeBytes: Long = 0L,
        configuredLang: String = "ar"
    ): OfflineSttStatus {
        val resolvedDir = resolveConfiguredModelDir(configuredDir)
        if (resolvedDir == null) {
            return OfflineSttStatus(
                isRecognizerAvailable = false,
                isOnDeviceApiAvailable = false,
                engineModeLabelAr = "لا يوجد نموذج كلام محلي",
                statusDetailAr = "التعرف أوفلاين بدون جوجل جاهز لاستيراد حزمة Vosk (.zip) من الإعدادات.",
                hasModelConfigured = false,
                isModelLoaded = false,
                modelName = "",
                modelDir = "",
                modelSizeBytes = 0L,
                modelLanguage = "ar",
                lastErrorAr = lastLoadErrorAr
            )
        }

        val name = configuredName.ifBlank { resolvedDir.name }
        val sizeBytes = configuredSizeBytes.takeIf { it > 0L } ?: computeDirectorySize(resolvedDir)
        val sizeMb = String.format(Locale.US, "%.1f", sizeBytes / (1024f * 1024f))
        val lang = configuredLang.ifBlank { inferLanguageFromName(name) }
        val loaded = activeModel != null && activeModelDir == resolvedDir.absolutePath

        return OfflineSttStatus(
            isRecognizerAvailable = true,
            isOnDeviceApiAvailable = true,
            engineModeLabelAr = if (loaded) "Vosk Offline (محمّل)" else "Vosk Offline (جاهز)",
            statusDetailAr = "نموذج محلي أوفلاين 100%: $name ($sizeMb ميجابايت • لغة: $lang).",
            hasModelConfigured = true,
            isModelLoaded = loaded,
            modelName = name,
            modelDir = resolvedDir.absolutePath,
            modelSizeBytes = sizeBytes,
            modelLanguage = lang,
            lastErrorAr = lastLoadErrorAr
        )
    }

    suspend fun importModelFromUri(uri: Uri): Result<ImportedSttModel> = withContext(Dispatchers.IO) {
        val rawName = queryDisplayName(uri) ?: "vosk-model"
        val cleanBaseName = rawName
            .replace(Regex("\\.zip$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "vosk_model_${System.currentTimeMillis()}" }

        val modelsBase = File(appContext.filesDir, MODELS_DIR_NAME).apply { mkdirs() }
        val stagingDir = File(modelsBase, ".staging_${System.currentTimeMillis()}").apply { mkdirs() }

        return@withContext runCatching {
            val stream = appContext.contentResolver.openInputStream(uri)
                ?: error("تعذر فتح ملف نموذج الكلام المختار.")
            var extractedBytes = 0L
            stream.use { input ->
                ZipInputStream(BufferedInputStream(input, 32 * 1024)).use { zis ->
                    val stagingCanonical = stagingDir.canonicalPath + File.separator
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val target = File(stagingDir, entry.name)
                        val targetCanonical = target.canonicalPath
                        if (!targetCanonical.startsWith(stagingCanonical) && targetCanonical != stagingDir.canonicalPath) {
                            error("ملف مضغوط غير آمن (مسار غير صالح).")
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                val buffer = ByteArray(16 * 1024)
                                var read = zis.read(buffer)
                                while (read != -1) {
                                    out.write(buffer, 0, read)
                                    extractedBytes += read
                                    if (extractedBytes > MAX_EXTRACTED_MODEL_BYTES) {
                                        error("حجم النموذج بعد فك الضغط أكبر من الحد الآمن (${MAX_EXTRACTED_MODEL_BYTES / (1024 * 1024)} MB).")
                                    }
                                    read = zis.read(buffer)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            // Flatten single root folder inside zip if present
            val topChildren = stagingDir.listFiles()?.toList().orEmpty()
            val candidateRoot = if (containsVoskModelFiles(stagingDir)) {
                stagingDir
            } else if (topChildren.size == 1 && topChildren.first().isDirectory && containsVoskModelFiles(topChildren.first())) {
                topChildren.first()
            } else {
                topChildren.firstOrNull { it.isDirectory && containsVoskModelFiles(it) }
            }

            if (candidateRoot == null || !containsVoskModelFiles(candidateRoot)) {
                error("الملف المختار ليس نموذج Vosk صالحاً (يجب أن يحتوي على مجلد am/ و conf/ وملف final.mdl).")
            }

            val destDir = File(modelsBase, cleanBaseName)
            if (destDir.exists()) {
                destDir.deleteRecursively()
            }
            if (candidateRoot == stagingDir) {
                if (!stagingDir.renameTo(destDir)) {
                    stagingDir.copyRecursively(destDir, overwrite = true)
                    stagingDir.deleteRecursively()
                }
            } else {
                if (!candidateRoot.renameTo(destDir)) {
                    candidateRoot.copyRecursively(destDir, overwrite = true)
                }
                stagingDir.deleteRecursively()
            }

            // Clear old cached model so the new one loads cleanly
            unloadCachedModel()

            val finalSize = computeDirectorySize(destDir)
            val lang = inferLanguageFromName(cleanBaseName)
            ImportedSttModel(
                modelDir = destDir.absolutePath,
                displayName = cleanBaseName,
                sizeBytes = finalSize,
                languageTag = lang
            )
        }.onFailure {
            stagingDir.deleteRecursively()
        }
    }

    fun deleteInstalledModels() {
        unloadCachedModel()
        val modelsBase = File(appContext.filesDir, MODELS_DIR_NAME)
        if (modelsBase.exists()) {
            modelsBase.deleteRecursively()
        }
        lastLoadErrorAr = null
    }

    fun unloadCachedModel() {
        synchronized(modelLock) {
            runCatching { activeModel?.close() }
            activeModel = null
            activeModelDir = ""
        }
    }

    private fun ensureModelLoaded(resolvedDir: File): Result<Model> {
        synchronized(modelLock) {
            val path = resolvedDir.absolutePath
            val existing = activeModel
            if (existing != null && activeModelDir == path) {
                lastLoadErrorAr = null
                return Result.success(existing)
            }

            // Guard extreme low memory before allocating Vosk native FST/AM graphs
            val availMb = readAvailableRamMb()
            if (availMb in 1..110L) {
                val msg = "الذاكرة المتاحة منخفضة جداً ($availMb ميجا) لتحميل نموذج الكلام."
                lastLoadErrorAr = msg
                return Result.failure(IllegalStateException("STT_MODEL_LOAD_FAILED: $msg"))
            }

            runCatching { existing?.close() }
            activeModel = null
            activeModelDir = ""

            return runCatching {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val loaded = Model(path)
                activeModel = loaded
                activeModelDir = path
                lastLoadErrorAr = null
                loaded
            }.onFailure { err ->
                lastLoadErrorAr = err.message ?: "فشل تحميل نموذج Vosk."
            }
        }
    }

    fun startListening(
        timeoutMs: Long = 9_500L,
        onRmsDb: (Float) -> Unit,
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("MIC_PERMISSION_DENIED: إذن الميكروفون غير متاح.")
            return
        }

        val sid = sessionSeq.incrementAndGet()
        captureJob?.cancel()
        captureJob = captureScope.launch {
            runVoskCaptureSession(
                sid = sid,
                timeoutMs = timeoutMs,
                onRmsDb = onRmsDb,
                onPartialResult = onPartialResult,
                onFinalResult = onFinalResult,
                onError = onError
            )
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
        delay(180L)
        return result
    }

    @SuppressLint("MissingPermission")
    private fun runVoskCaptureSession(
        sid: Long,
        timeoutMs: Long,
        onRmsDb: (Float) -> Unit,
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (sid != sessionSeq.get()) return
        val configuredDir = runCatching {
            kotlinx.coroutines.runBlocking { settingsRepo.currentSettings().sttModelDir }
        }.getOrDefault("")
        val resolvedDir = resolveConfiguredModelDir(configuredDir)
        if (resolvedDir == null) {
            CheetahRuntimeBus.setSttActive(false)
            onError("NO_STT_MODEL: لم يتم تثبيت حزمة نموذج كلام محلي (Vosk).")
            return
        }

        val modelResult = ensureModelLoaded(resolvedDir)
        val model = modelResult.getOrNull()
        if (model == null) {
            CheetahRuntimeBus.setSttActive(false)
            val errDetail = modelResult.exceptionOrNull()?.message.orEmpty()
            onError("STT_MODEL_LOAD_FAILED: $errDetail")
            return
        }

        var audioRecord: AudioRecord? = null
        var recognizer: Recognizer? = null
        val delivered = AtomicBoolean(false)

        try {
            recognizer = Recognizer(model, SAMPLE_RATE_HZ.toFloat())
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                if (delivered.compareAndSet(false, true)) {
                    onError("AUDIORECORD_INIT_FAILED: حجم المخزن الصوتي غير مدعوم.")
                }
                return
            }
            val bufferBytes = maxOf(minBuf * 2, FRAME_SAMPLES * 4)

            audioRecord = openAudioRecord(bufferBytes)
            if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                if (delivered.compareAndSet(false, true)) {
                    onError("AUDIORECORD_INIT_FAILED: تعذر تهيئة الميكروفون.")
                }
                return
            }

            CheetahRuntimeBus.setSttActive(true)
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                if (delivered.compareAndSet(false, true)) {
                    onError("BACKGROUND_MIC_BLOCKED: النظام منع بدء التسجيل الصوتي.")
                }
                return
            }

            val pcmChunk = ShortArray(FRAME_SAMPLES)
            val sessionStart = System.currentTimeMillis()
            var heardSpeech = false
            var silenceMs = 0L
            var noiseBaseline = 0.014f
            var baselineFrames = 0
            var zeroOnlyFrames = 0
            var totalFrames = 0
            var lastPartial = ""

            while (sid == sessionSeq.get() && captureScope.isActive) {
                val now = System.currentTimeMillis()
                if (now - sessionStart >= timeoutMs) break

                val read = audioRecord.read(pcmChunk, 0, pcmChunk.size)
                if (read < 0) {
                    if (delivered.compareAndSet(false, true)) {
                        onError("AUDIORECORD_READ_ERROR: خطأ قراءة المايك ($read).")
                    }
                    return
                }
                if (read == 0) {
                    Thread.sleep(15L)
                    continue
                }

                totalFrames++
                val rms = computeRms(pcmChunk, read)
                if (rms < 1e-5f) {
                    zeroOnlyFrames++
                } else {
                    zeroOnlyFrames = 0
                }
                if (totalFrames >= 14 && zeroOnlyFrames >= 14) {
                    if (delivered.compareAndSet(false, true)) {
                        onError("AUDIO_SILENCED: الميكروفون مكتوم من النظام أثناء التواجد بالخلفية.")
                    }
                    return
                }

                val rmsNorm = ((rms * 7.5f)).coerceIn(0f, 1f)
                onRmsDb(rmsNorm)

                if (baselineFrames < 3) {
                    noiseBaseline = ((noiseBaseline * baselineFrames) + rms) / (baselineFrames + 1)
                    baselineFrames++
                }
                val speechThresh = maxOf(0.018f, noiseBaseline * 2.6f)

                val chunkAccepted = recognizer.acceptWaveForm(pcmChunk, read)
                val chunkPartial = extractJsonText(recognizer.partialResult, "partial")
                if (chunkPartial.isNotEmpty() && chunkPartial != lastPartial) {
                    lastPartial = chunkPartial
                    onPartialResult(chunkPartial)
                }

                val isSpeechFrame = rms >= speechThresh || chunkPartial.isNotEmpty()
                val chunkMs = ((read * 1000L) / SAMPLE_RATE_HZ).coerceAtLeast(20L)

                if (isSpeechFrame) {
                    heardSpeech = true
                    silenceMs = 0L
                } else if (heardSpeech) {
                    silenceMs += chunkMs
                    if (silenceMs >= ENDPOINT_SILENCE_MS || (chunkAccepted && lastPartial.isNotEmpty())) {
                        break
                    }
                }
            }

            if (sid != sessionSeq.get()) return

            val finalText = extractJsonText(recognizer.finalResult, "text").ifBlank { lastPartial }.trim()
            if (delivered.compareAndSet(false, true)) {
                if (finalText.isBlank()) {
                    onError("NO_SPEECH: لم يتم التقاط كلام واضح.")
                } else {
                    onFinalResult(finalText)
                }
            }
        } catch (se: SecurityException) {
            if (delivered.compareAndSet(false, true)) {
                onError("MIC_PERMISSION_DENIED: ${se.message ?: "SecurityException"}")
            }
        } catch (t: Throwable) {
            if (delivered.compareAndSet(false, true)) {
                onError("AUDIORECORD_HARDWARE: ${t.message ?: t.javaClass.simpleName}")
            }
        } finally {
            onRmsDb(0f)
            CheetahRuntimeBus.setSttActive(false)
            runCatching {
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            }
            runCatching { audioRecord?.release() }
            runCatching { recognizer?.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openAudioRecord(bufferBytes: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val candidate = runCatching {
                AudioRecord(
                    source,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
            }.getOrNull()
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            }
            runCatching { candidate?.release() }
        }
        return null
    }

    fun stopAndRelease() {
        sessionSeq.incrementAndGet()
        captureJob?.cancel()
        captureJob = null
        CheetahRuntimeBus.setSttActive(false)
    }

    private fun computeRms(samples: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sumSq = 0.0
        for (i in 0 until count) {
            val norm = samples[i] / 32768.0
            sumSq += norm * norm
        }
        return sqrt(sumSq / count).toFloat()
    }

    private fun extractJsonText(json: String?, key: String): String {
        if (json.isNullOrBlank()) return ""
        return runCatching {
            JSONObject(json).optString(key, "").trim()
        }.getOrDefault("")
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun readAvailableRamMb(): Long {
        return runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return -1L
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.availMem / (1024L * 1024L)
        }.getOrDefault(-1L)
    }

    private fun computeDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        private const val FRAME_SAMPLES = 1600 // 100ms chunks
        private const val ENDPOINT_SILENCE_MS = 1250L
        private const val MODELS_DIR_NAME = "stt_models"
        private const val MAX_EXTRACTED_MODEL_BYTES = 1_500L * 1024L * 1024L // 1.5 GB zip bomb cap

        fun containsVoskModelFiles(dir: File): Boolean {
            if (!dir.exists() || !dir.isDirectory) return false
            val hasAcousticModel = File(dir, "am/final.mdl").isFile || File(dir, "final.mdl").isFile
            val hasSupportDir = File(dir, "conf").isDirectory ||
                File(dir, "graph").isDirectory ||
                File(dir, "ivector").isDirectory
            return hasAcousticModel && hasSupportDir
        }

        fun inferLanguageFromName(name: String): String {
            val lower = name.lowercase(Locale.US)
            return when {
                lower.contains("-ar") || lower.contains("arabic") || lower.contains("_ar") -> "ar"
                lower.contains("-en") || lower.contains("english") || lower.contains("_en") -> "en"
                else -> "multi"
            }
        }
    }
}
