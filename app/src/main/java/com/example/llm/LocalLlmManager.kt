package com.example.llm

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.StatFs
import android.provider.OpenableColumns
import com.example.core.model.AiMode
import com.example.core.model.AssistantSettings
import com.example.core.model.ChatTemplateFamily
import com.example.core.model.ConversationTurnMessage
import com.example.core.model.GgufModelMetadata
import com.example.core.model.LocalLlmConfig
import com.example.core.model.LocalLlmLoadState
import com.example.core.model.LocalLlmStorageMode
import com.example.core.model.ModelBenchmarkResult
import com.example.core.model.PerformanceProfile
import com.example.data.SettingsRepository
import com.example.intelligence.JarvisEgyptianResponder
import com.example.intelligence.ReasoningOutputParser
import com.example.voice.CheetahRuntimeBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class LlmGenerationResult(
    val rawText: String,
    val visibleAnswer: String,
    val reasoningText: String,
    val elapsedMs: Long,
    val tokensPerSec: Float,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false
)

/**
 * Manages optional, unbundled user-provided .gguf models via SAF + llama.cpp JNI.
 * Features:
 * - Zero models bundled in APK; SAF FD or app-private copy.
 * - Pre-load RAM safety + thermal/battery scaling via DeviceHealthMonitor.
 * - Serialized 1-at-a-time inference queue, deadline cancellation, & auto-unload timer + on-demand reload.
 * - Chat template detection & multi-turn KV-prefix reuse.
 * - GBNF grammar-constrained JSON generation & benchmark test (tok/s).
 */
class LocalLlmManager(
    context: Context,
    private val settingsRepo: SettingsRepository = SettingsRepository(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private val modelsDir = File(appContext.filesDir, "llm_models").apply { mkdirs() }
    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var openPfd: ParcelFileDescriptor? = null
    private var inactivityUnloadJob: Job? = null

    @Volatile
    private var cancelledByUser = false
    @Volatile
    private var loadedCtxLen = 0
    @Volatile
    private var loadedThreads = 0
    @Volatile
    private var loadedBatchSize = 0

    fun refreshStorageAndMemoryTelemetry(config: LocalLlmConfig? = null) {
        val stat = StatFs(appContext.filesDir.absolutePath)
        val freeBytes = stat.availableBytes
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024L * 1024L)
        val totalMb = memInfo.totalMem / (1024L * 1024L)
        val health = DeviceHealthMonitor.inspect(appContext)

        val effectiveCtx = config?.let { resolveEffectiveContextLength(it, health.isThermalThrottled, health.isBatteryLow) } ?: 1024
        val estimatedRamMb = if (config != null && config.fileSizeBytes > 0L) {
            val weightsMb = config.fileSizeBytes / (1024L * 1024L)
            val kvEstimateMb = ((effectiveCtx * 128L) / 1024L) + 128L
            weightsMb + kvEstimateMb
        } else 0L
        val ramWarning = if (config != null && config.fileSizeBytes > 0L) {
            GgufHeaderParser.evaluateRamSafety(
                fileSizeBytes = config.fileSizeBytes,
                availableRamMb = availMb,
                totalRamMb = totalMb,
                contextLength = effectiveCtx
            ).warningMessageAr
        } else null

        val tmplWarning = if (config != null && (config.displayName.isNotBlank() || config.modelUri.isNotBlank())) {
            ChatTemplateFormatter.buildTemplateWarning(
                rawTemplate = config.chatTemplateRaw,
                family = config.chatTemplateFamily,
                override = config.chatTemplateOverride
            )
        } else null

        CheetahRuntimeBus.updateLocalLlmStatus { cur ->
            cur.copy(
                nativeBackendAvailable = LlamaNativeBridge.isNativeAvailable,
                freeInternalBytes = freeBytes,
                availableRamMb = availMb,
                totalRamMb = totalMb,
                estimatedRamMb = estimatedRamMb,
                loadedContextLen = loadedCtxLen,
                ramWarningAr = ramWarning,
                templateWarningAr = tmplWarning,
                deviceHealth = health
            )
        }
    }

    suspend fun inspectSelectedUri(uri: Uri): Result<GgufModelMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
            var fileSize = 0L
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        displayName = cursor.getString(nameIdx) ?: displayName
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        fileSize = cursor.getLong(sizeIdx)
                    }
                }
            }

            require(displayName.lowercase().endsWith(".gguf")) {
                "الملف المختار ($displayName) لازم يكون بامتداد .gguf"
            }

            val parsed = appContext.contentResolver.openInputStream(uri)?.use { stream ->
                GgufHeaderParser.parseHeader(stream, displayName).getOrThrow()
            } ?: error("تعذر قراءة الملف المختار.")

            val warning = ChatTemplateFormatter.buildTemplateWarning(parsed.chatTemplateRaw, parsed.chatTemplateFamily)
            GgufModelMetadata(
                displayName = displayName,
                fileSizeBytes = fileSize,
                architecture = parsed.architecture,
                quantization = parsed.quantizationGuess,
                sourceUri = uri.toString(),
                chatTemplateRaw = parsed.chatTemplateRaw,
                chatTemplateFamily = parsed.chatTemplateFamily,
                templateWarningAr = warning
            )
        }
    }

    suspend fun importAndSelectModel(
        uri: Uri,
        storageMode: LocalLlmStorageMode
    ): Result<LocalLlmConfig> = withContext(Dispatchers.IO) {
        val inspected = inspectSelectedUri(uri).getOrElse {
            CheetahRuntimeBus.updateLocalLlmStatus { s ->
                s.copy(loadState = LocalLlmLoadState.ERROR, lastErrorAr = it.message)
            }
            return@withContext Result.failure(it)
        }

        val currentCfg = settingsRepo.currentSettings().localLlmConfig
        var internalPath = ""

        if (storageMode == LocalLlmStorageMode.COPIED_INTERNAL) {
            val freeBytes = StatFs(modelsDir.absolutePath).availableBytes
            if (inspected.fileSizeBytes > 0L && freeBytes < inspected.fileSizeBytes + 64L * 1024L * 1024L) {
                val err = IllegalStateException("المساحة الفاضية غير كافية لنسخ النموذج.")
                CheetahRuntimeBus.updateLocalLlmStatus {
                    it.copy(loadState = LocalLlmLoadState.ERROR, lastErrorAr = err.message)
                }
                return@withContext Result.failure(err)
            }
            modelsDir.listFiles()?.forEach { old -> runCatching { old.delete() } }
            val safeName = inspected.displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destFile = File(modelsDir, safeName)
            val copyResult = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buf = ByteArray(1024 * 64)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                        }
                    }
                } ?: error("تعذر فتح ملف GGUF للنسخ.")
            }
            if (copyResult.isFailure) {
                runCatching { destFile.delete() }
                val err = copyResult.exceptionOrNull()!!
                CheetahRuntimeBus.updateLocalLlmStatus {
                    it.copy(loadState = LocalLlmLoadState.ERROR, lastErrorAr = err.message)
                }
                return@withContext Result.failure(err)
            }
            internalPath = destFile.absolutePath
        }

        val nextConfig = currentCfg.copy(
            modelUri = uri.toString(),
            internalPath = internalPath,
            displayName = inspected.displayName,
            fileSizeBytes = inspected.fileSizeBytes,
            quantization = inspected.quantization,
            architecture = inspected.architecture,
            storageMode = storageMode,
            chatTemplateRaw = inspected.chatTemplateRaw,
            chatTemplateFamily = inspected.chatTemplateFamily
        )
        settingsRepo.updateLocalLlmConfig(nextConfig)
        refreshStorageAndMemoryTelemetry(nextConfig)
        CheetahRuntimeBus.updateLocalLlmStatus {
            it.copy(
                loadState = LocalLlmLoadState.UNLOADED,
                metadata = inspected.copy(internalPath = internalPath, storageMode = storageMode),
                templateWarningAr = inspected.templateWarningAr,
                lastErrorAr = null
            )
        }
        Result.success(nextConfig)
    }

    fun hasConfiguredModel(cfg: LocalLlmConfig): Boolean {
        return cfg.displayName.isNotBlank() || cfg.modelUri.isNotBlank() || cfg.internalPath.isNotBlank()
    }

    suspend fun ensureModelLoadedIfConfigured(): Result<Unit> {
        val cfg = settingsRepo.currentSettings().localLlmConfig
        if (!hasConfiguredModel(cfg)) {
            return Result.failure(IllegalStateException("لم يتم اختيار ملف GGUF."))
        }
        val health = DeviceHealthMonitor.inspect(appContext)
        val effCtx = resolveEffectiveContextLength(cfg, health.isThermalThrottled, health.isBatteryLow)
        val effThreads = resolveEffectiveThreads(cfg, health.isThermalThrottled, health.isBatteryLow)
        val effBatch = cfg.batchSize.coerceIn(32, minOf(effCtx, 1024))
        if (isModelLoaded() &&
            loadedCtxLen == effCtx &&
            loadedThreads == effThreads &&
            loadedBatchSize == effBatch
        ) {
            return Result.success(Unit)
        }
        return loadConfiguredModel()
    }

    suspend fun loadConfiguredModel(): Result<Unit> = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            if (!LlamaNativeBridge.isNativeAvailable) {
                val err = IllegalStateException("مكتبة llama.cpp الأصلية غير متاحة على معمارية هذا الجهاز.")
                CheetahRuntimeBus.updateLocalLlmStatus {
                    it.copy(loadState = LocalLlmLoadState.ERROR, lastErrorAr = err.message)
                }
                return@withLock Result.failure(err)
            }

            val cfg = settingsRepo.currentSettings().localLlmConfig
            if (!hasConfiguredModel(cfg)) {
                val err = IllegalStateException("اختر ملف نموذج .gguf الأول.")
                CheetahRuntimeBus.updateLocalLlmStatus {
                    it.copy(loadState = LocalLlmLoadState.ERROR, lastErrorAr = err.message)
                }
                return@withLock Result.failure(err)
            }

            refreshStorageAndMemoryTelemetry(cfg)
            val statusSnap = CheetahRuntimeBus.localLlmStatus.value
            val health = statusSnap.deviceHealth
            val effCtx = resolveEffectiveContextLength(cfg, health.isThermalThrottled, health.isBatteryLow)
            val effThreads = resolveEffectiveThreads(cfg, health.isThermalThrottled, health.isBatteryLow)
            val effBatch = cfg.batchSize.coerceIn(32, minOf(effCtx, 1024))

            val ramCheck = GgufHeaderParser.evaluateRamSafety(
                fileSizeBytes = cfg.fileSizeBytes,
                availableRamMb = statusSnap.availableRamMb,
                totalRamMb = statusSnap.totalRamMb,
                contextLength = effCtx
            )
            if (!ramCheck.isSafeToLoad) {
                val err = IllegalStateException(ramCheck.warningMessageAr ?: "الرام غير كافية لتحميل النموذج.")
                CheetahRuntimeBus.updateLocalLlmStatus {
                    it.copy(loadState = LocalLlmLoadState.ERROR, lastErrorAr = err.message)
                }
                return@withLock Result.failure(err)
            }

            CheetahRuntimeBus.updateLocalLlmStatus {
                it.copy(loadState = LocalLlmLoadState.LOADING, lastErrorAr = null)
            }

            unloadNativeOnly()

            val loaded = runCatching {
                when {
                    cfg.storageMode == LocalLlmStorageMode.COPIED_INTERNAL &&
                        cfg.internalPath.isNotBlank() &&
                        File(cfg.internalPath).exists() -> {
                        LlamaNativeBridge.nativeLoadModelFromPath(
                            path = cfg.internalPath,
                            contextLen = effCtx,
                            threads = effThreads,
                            batchSize = effBatch
                        )
                    }
                    cfg.modelUri.isNotBlank() -> {
                        val pfd = runCatching {
                            appContext.contentResolver.openFileDescriptor(Uri.parse(cfg.modelUri), "r")
                        }.getOrNull()
                        if (pfd == null) {
                            false
                        } else {
                            openPfd = pfd
                            val ok = LlamaNativeBridge.nativeLoadModelFromFd(
                                fd = pfd.fd,
                                contextLen = effCtx,
                                threads = effThreads,
                                batchSize = effBatch
                            )
                            if (!ok) {
                                runCatching { openPfd?.close() }
                                openPfd = null
                            }
                            ok
                        }
                    }
                    else -> false
                }
            }.getOrDefault(false)

            if (loaded) {
                loadedCtxLen = effCtx
                loadedThreads = effThreads
                loadedBatchSize = effBatch

                val desc = runCatching { LlamaNativeBridge.nativeGetModelDescription() }.getOrDefault("")
                val nativeTmpl = runCatching { LlamaNativeBridge.nativeGetChatTemplate() }.getOrDefault("")
                val resolvedTmplRaw = nativeTmpl.ifBlank { cfg.chatTemplateRaw }
                val resolvedArch = desc.ifBlank { cfg.architecture }
                val resolvedFamily = ChatTemplateFormatter.detectTemplateFamily(resolvedTmplRaw, resolvedArch)
                val tmplWarning = ChatTemplateFormatter.buildTemplateWarning(
                    rawTemplate = resolvedTmplRaw,
                    family = resolvedFamily,
                    override = cfg.chatTemplateOverride
                )

                if (resolvedTmplRaw != cfg.chatTemplateRaw || resolvedFamily != cfg.chatTemplateFamily) {
                    settingsRepo.updateLocalLlmConfig(
                        cfg.copy(
                            architecture = resolvedArch,
                            chatTemplateRaw = resolvedTmplRaw,
                            chatTemplateFamily = resolvedFamily
                        )
                    )
                }

                CheetahRuntimeBus.updateLocalLlmStatus {
                    it.copy(
                        loadState = LocalLlmLoadState.LOADED,
                        loadedContextLen = effCtx,
                        metadata = GgufModelMetadata(
                            displayName = cfg.displayName,
                            fileSizeBytes = cfg.fileSizeBytes,
                            architecture = resolvedArch,
                            quantization = cfg.quantization,
                            sourceUri = cfg.modelUri,
                            internalPath = cfg.internalPath,
                            storageMode = cfg.storageMode,
                            chatTemplateRaw = resolvedTmplRaw,
                            chatTemplateFamily = resolvedFamily,
                            templateWarningAr = tmplWarning
                        ),
                        templateWarningAr = tmplWarning,
                        lastErrorAr = null
                    )
                }
                scheduleAutoUnloadTimer(cfg.autoUnloadMinutes)
                Result.success(Unit)
            } else {
                val err = IllegalStateException("فشل تحميل ملف GGUF في llama.cpp (تأكد إن الصيغة مدعومة وإن الرام كافية).")
                CheetahRuntimeBus.updateLocalLlmStatus {
                    it.copy(loadState = LocalLlmLoadState.ERROR, lastErrorAr = err.message)
                }
                Result.failure(err)
            }
        }
    }

    suspend fun unloadModel() = withContext(Dispatchers.IO) {
        inactivityUnloadJob?.cancel()
        loadMutex.withLock {
            unloadNativeOnly()
            CheetahRuntimeBus.updateLocalLlmStatus {
                it.copy(
                    loadState = LocalLlmLoadState.UNLOADED,
                    isGenerating = false,
                    isThinking = false,
                    thinkingElapsedMs = 0L,
                    lastErrorAr = null
                )
            }
        }
    }

    suspend fun removeModel() = withContext(Dispatchers.IO) {
        inactivityUnloadJob?.cancel()
        loadMutex.withLock {
            unloadNativeOnly()
            val cfg = settingsRepo.currentSettings().localLlmConfig
            if (cfg.internalPath.isNotBlank()) {
                runCatching { File(cfg.internalPath).delete() }
            }
            modelsDir.listFiles()?.forEach { runCatching { it.delete() } }
            settingsRepo.clearLocalLlmSelection()
            refreshStorageAndMemoryTelemetry(null)
            CheetahRuntimeBus.updateLocalLlmStatus {
                it.copy(
                    loadState = LocalLlmLoadState.UNLOADED,
                    metadata = null,
                    isGenerating = false,
                    isThinking = false,
                    thinkingElapsedMs = 0L,
                    ramWarningAr = null,
                    templateWarningAr = null,
                    lastErrorAr = null
                )
            }
        }
    }

    fun isModelLoaded(): Boolean {
        return CheetahRuntimeBus.localLlmStatus.value.loadState == LocalLlmLoadState.LOADED
    }

    fun cancelGeneration() {
        cancelledByUser = true
        if (LlamaNativeBridge.isNativeAvailable) {
            runCatching { LlamaNativeBridge.nativeCancelGeneration() }
        }
    }

    suspend fun generateReply(
        userPrompt: String,
        settings: AssistantSettings,
        onToken: (String) -> Unit = {},
        onSentenceReady: (String, Boolean) -> Unit = { _, _ -> }
    ): Result<String> {
        val sysPrompt = JarvisEgyptianResponder.buildLocalLlmSystemPrompt(settings)
        val res = generateBrainReply(
            systemPrompt = sysPrompt,
            messages = listOf(ConversationTurnMessage("user", userPrompt)),
            settings = settings,
            reuseKvCache = true,
            onVisibleStreamText = onToken,
            onSentenceReady = onSentenceReady
        )
        return res.map { it.visibleAnswer }
    }

    suspend fun generateBrainReply(
        systemPrompt: String,
        messages: List<ConversationTurnMessage>,
        settings: AssistantSettings,
        maxTokensOverride: Int? = null,
        temperatureOverride: Float? = null,
        reuseKvCache: Boolean = true,
        onVisibleStreamText: (String) -> Unit = {},
        onSentenceReady: (String, Boolean) -> Unit = { _, _ -> }
    ): Result<LlmGenerationResult> = withContext(Dispatchers.IO) {
        if (!isModelLoaded()) {
            val reload = ensureModelLoadedIfConfigured()
            if (reload.isFailure) {
                return@withContext Result.failure(reload.exceptionOrNull() ?: IllegalStateException("النموذج المحلي غير محمّل."))
            }
        } else {
            ensureModelLoadedIfConfigured()
        }

        inferenceMutex.withLock {
            inactivityUnloadJob?.cancel()
            cancelledByUser = false
            val cfg = settings.localLlmConfig
            val family = ChatTemplateFormatter.resolveEffectiveFamily(cfg.chatTemplateFamily, cfg.chatTemplateOverride)
            val formattedPrompt = ChatTemplateFormatter.formatPrompt(
                family = family,
                systemPrompt = systemPrompt,
                messages = messages
            )

            val modeMaxTokens = maxTokensOverride ?: when (cfg.aiMode) {
                AiMode.FAST -> (cfg.maxTokens * 0.6f).toInt().coerceAtLeast(64)
                AiMode.BALANCED -> cfg.maxTokens
                AiMode.DEEP_THINKING -> (cfg.maxTokens * 1.6f).toInt().coerceAtMost(768)
            }
            val modeTemp = temperatureOverride ?: when (cfg.aiMode) {
                AiMode.FAST -> (cfg.temperature * 0.8f).coerceAtLeast(0.2f)
                AiMode.BALANCED -> cfg.temperature
                AiMode.DEEP_THINKING -> cfg.temperature.coerceAtMost(0.75f)
            }

            val rawAcc = StringBuilder()
            val sentenceBuf = StringBuilder()
            var emittedVisibleLen = 0
            var firstSentence = true
            var tokenCount = 0
            var timedOut = false
            val tStart = System.currentTimeMillis()

            CheetahRuntimeBus.setStreamingReplyText("")
            CheetahRuntimeBus.updateLocalLlmStatus {
                it.copy(isGenerating = true, isThinking = true, thinkingElapsedMs = 0L, tokensPerSec = 0f)
            }

            val deadlineJob = managerScope.launch {
                val limitMs = cfg.maxThinkingSeconds.coerceIn(5, 120) * 1000L
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - tStart
                    CheetahRuntimeBus.updateLocalLlmStatus { it.copy(thinkingElapsedMs = elapsed) }
                    if (elapsed >= limitMs) {
                        timedOut = true
                        if (LlamaNativeBridge.isNativeAvailable) {
                            runCatching { LlamaNativeBridge.nativeCancelGeneration() }
                        }
                        break
                    }
                    delay(250L)
                }
            }

            val prevPriority = Process.myTid().let {
                runCatching { Process.getThreadPriority(it) }.getOrNull()
            }
            // Cooperate with background wake-word audio loop by running inference at background priority
            if (CheetahRuntimeBus.serviceRunning.value) {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            }

            return@withLock try {
                val stopArray = cfg.stopSequences.takeIf { it.isNotEmpty() }?.toTypedArray()
                val rawOut = LlamaNativeBridge.nativeGenerateRawStream(
                    formattedPrompt = formattedPrompt,
                    maxTokens = modeMaxTokens,
                    temperature = modeTemp,
                    topP = cfg.topP,
                    topK = cfg.topK,
                    minP = cfg.minP,
                    repeatPenalty = cfg.repeatPenalty,
                    seed = cfg.seed,
                    stopSequences = stopArray,
                    gbnfGrammar = null,
                    reuseKvCache = reuseKvCache,
                    tokenCallback = object : LlamaTokenCallback {
                        override fun onToken(tokenText: String) {
                            tokenCount++
                            rawAcc.append(tokenText)
                            val visibleSoFar = if (cfg.stripReasoningTags) {
                                ReasoningOutputParser.extractVisibleStreamText(rawAcc.toString())
                            } else {
                                rawAcc.toString()
                            }
                            CheetahRuntimeBus.setStreamingReplyText(visibleSoFar)
                            onVisibleStreamText(visibleSoFar)

                            if (visibleSoFar.length > emittedVisibleLen) {
                                val delta = visibleSoFar.substring(emittedVisibleLen)
                                emittedVisibleLen = visibleSoFar.length
                                sentenceBuf.append(delta)

                                val cur = sentenceBuf.toString()
                                if (cur.length >= 28 &&
                                    (cur.endsWith(".") ||
                                        cur.endsWith("!") ||
                                        cur.endsWith("؟") ||
                                        cur.endsWith("\n") ||
                                        cur.endsWith("،"))
                                ) {
                                    val cleanChunk = cur.trim()
                                    sentenceBuf.clear()
                                    if (cleanChunk.isNotEmpty()) {
                                        onSentenceReady(cleanChunk, firstSentence)
                                        firstSentence = false
                                    }
                                }
                            }
                        }
                    }
                )

                val tail = sentenceBuf.toString().trim()
                if (tail.isNotEmpty() && !cancelledByUser) {
                    onSentenceReady(tail, firstSentence)
                }

                val elapsedMs = (System.currentTimeMillis() - tStart).coerceAtLeast(100L)
                val tps = tokenCount / (elapsedMs / 1000f)
                CheetahRuntimeBus.updateLocalLlmStatus {
                    it.copy(
                        isGenerating = false,
                        isThinking = false,
                        thinkingElapsedMs = elapsedMs,
                        tokensPerSec = tps
                    )
                }
                scheduleAutoUnloadTimer(cfg.autoUnloadMinutes)

                val combinedRaw = trimStopSequences(rawOut.ifBlank { rawAcc.toString() }.trim(), cfg.stopSequences)
                val split = ReasoningOutputParser.splitReasoningAndAnswer(combinedRaw)
                val baseAnswer = if (cfg.stripReasoningTags) split.finalAnswer else combinedRaw
                val finalAnswer = when {
                    timedOut && baseAnswer.isNotBlank() ->
                        "${baseAnswer}\n\n(ملاحظة: انتهى الحد الأقصى لوقت التفكير (${cfg.maxThinkingSeconds} ثانية)، فده أفضل ملخص وصلتله لحد دلوقتي.)"
                    timedOut ->
                        "انتهى وقت التفكير المحدد (${cfg.maxThinkingSeconds} ثانية) قبل اكتمال الإجابة. جرب زيادة الحد من الإعدادات أو تفعيل الوضع السريع."
                    else -> baseAnswer
                }

                if (finalAnswer.isBlank() && split.reasoningText.isBlank()) {
                    Result.failure(IllegalStateException(if (cancelledByUser) "تم إيقاف التوليد." else "النموذج لم ينتج رداً."))
                } else {
                    Result.success(
                        LlmGenerationResult(
                            rawText = combinedRaw,
                            visibleAnswer = finalAnswer.ifBlank { split.reasoningText },
                            reasoningText = if (cfg.stripReasoningTags) split.reasoningText else "",
                            elapsedMs = elapsedMs,
                            tokensPerSec = tps,
                            timedOut = timedOut,
                            cancelled = cancelledByUser
                        )
                    )
                }
            } catch (ce: CancellationException) {
                if (LlamaNativeBridge.isNativeAvailable) {
                    runCatching { LlamaNativeBridge.nativeCancelGeneration() }
                }
                throw ce
            } catch (oom: OutOfMemoryError) {
                unloadNativeOnly()
                CheetahRuntimeBus.updateLocalLlmStatus {
                    it.copy(
                        loadState = LocalLlmLoadState.UNLOADED,
                        isGenerating = false,
                        isThinking = false,
                        lastErrorAr = "تم تفريغ النموذج تلقائياً بسبب امتلاء الذاكرة (OOM)."
                    )
                }
                Result.failure(oom)
            } catch (t: Throwable) {
                CheetahRuntimeBus.updateLocalLlmStatus {
                    it.copy(isGenerating = false, isThinking = false, lastErrorAr = t.message)
                }
                Result.failure(t)
            } finally {
                deadlineJob.cancel()
                prevPriority?.let { p -> runCatching { Process.setThreadPriority(p) } }
                CheetahRuntimeBus.setStreamingReplyText("")
            }
        }
    }

    suspend fun generateStructuredJson(
        systemPrompt: String,
        userPrompt: String,
        gbnfGrammar: String,
        settings: AssistantSettings,
        maxTokens: Int = 220
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isModelLoaded()) {
            val reload = ensureModelLoadedIfConfigured()
            if (reload.isFailure) {
                return@withContext Result.failure(reload.exceptionOrNull() ?: IllegalStateException("النموذج المحلي غير محمّل."))
            }
        }

        inferenceMutex.withLock {
            inactivityUnloadJob?.cancel()
            cancelledByUser = false
            val cfg = settings.localLlmConfig
            val family = ChatTemplateFormatter.resolveEffectiveFamily(cfg.chatTemplateFamily, cfg.chatTemplateOverride)
            val formatted = ChatTemplateFormatter.formatPrompt(
                family = family,
                systemPrompt = systemPrompt,
                messages = listOf(ConversationTurnMessage("user", userPrompt))
            )
            CheetahRuntimeBus.updateLocalLlmStatus { it.copy(isGenerating = true, isThinking = true) }
            val tStart = System.currentTimeMillis()
            return@withLock try {
                val out = LlamaNativeBridge.nativeGenerateRawStream(
                    formattedPrompt = formatted,
                    maxTokens = maxTokens,
                    temperature = 0.12f,
                    topP = 0.90f,
                    topK = 24,
                    minP = 0.05f,
                    repeatPenalty = 1.05f,
                    seed = cfg.seed,
                    stopSequences = cfg.stopSequences.takeIf { it.isNotEmpty() }?.toTypedArray(),
                    gbnfGrammar = gbnfGrammar.takeIf { it.isNotBlank() },
                    reuseKvCache = false,
                    tokenCallback = null
                ).trim()
                val elapsed = System.currentTimeMillis() - tStart
                CheetahRuntimeBus.updateLocalLlmStatus {
                    it.copy(isGenerating = false, isThinking = false, thinkingElapsedMs = elapsed)
                }
                scheduleAutoUnloadTimer(cfg.autoUnloadMinutes)
                if (out.isBlank()) Result.failure(IllegalStateException("فشل إنتاج JSON من النموذج."))
                else Result.success(out)
            } catch (t: Throwable) {
                CheetahRuntimeBus.updateLocalLlmStatus {
                    it.copy(isGenerating = false, isThinking = false, lastErrorAr = t.message)
                }
                Result.failure(t)
            }
        }
    }

    suspend fun runBenchmarkTest(settings: AssistantSettings): ModelBenchmarkResult {
        val loaded = ensureModelLoadedIfConfigured()
        if (loaded.isFailure) {
            val res = ModelBenchmarkResult(
                tokensGenerated = 0,
                elapsedMs = 0L,
                tokensPerSec = 0f,
                sampleOutput = "",
                success = false,
                errorAr = loaded.exceptionOrNull()?.message ?: "تعذر تحميل النموذج."
            )
            CheetahRuntimeBus.updateLocalLlmStatus { it.copy(lastBenchmark = res) }
            return res
        }

        val benchResult = generateBrainReply(
            systemPrompt = "أجب باختصار شديد وبالأرقام الواضحة فقط.",
            messages = listOf(ConversationTurnMessage("user", "احسب 17 + 28 واشرح الناتج في جملة واحدة قصيرة.")),
            settings = settings,
            maxTokensOverride = 72,
            temperatureOverride = 0.2f,
            reuseKvCache = false
        )

        val modelRes = benchResult.fold(
            onSuccess = { gen ->
                val estTokens = (gen.rawText.length / 4).coerceAtLeast(1)
                val tps = if (gen.tokensPerSec > 0f) gen.tokensPerSec else (estTokens / (gen.elapsedMs / 1000f))
                ModelBenchmarkResult(
                    tokensGenerated = estTokens,
                    elapsedMs = gen.elapsedMs,
                    tokensPerSec = tps,
                    sampleOutput = gen.visibleAnswer.take(180),
                    success = true
                )
            },
            onFailure = { err ->
                ModelBenchmarkResult(
                    tokensGenerated = 0,
                    elapsedMs = 0L,
                    tokensPerSec = 0f,
                    sampleOutput = "",
                    success = false,
                    errorAr = err.message ?: "فشل الاختبار"
                )
            }
        )
        CheetahRuntimeBus.updateLocalLlmStatus { it.copy(lastBenchmark = modelRes) }
        return modelRes
    }

    private fun scheduleAutoUnloadTimer(minutes: Int) {
        inactivityUnloadJob?.cancel()
        if (minutes <= 0) return
        inactivityUnloadJob = managerScope.launch {
            delay(minutes * 60_000L)
            if (!CheetahRuntimeBus.localLlmStatus.value.isGenerating && isModelLoaded()) {
                unloadModel()
            }
        }
    }

    private fun resolveEffectiveThreads(cfg: LocalLlmConfig, thermalThrottled: Boolean, batteryLow: Boolean): Int {
        var cap = cfg.performanceProfile.threadsCap
        if (thermalThrottled || (cfg.performanceProfile == PerformanceProfile.BATTERY_SAVER && batteryLow)) {
            cap = minOf(cap, 2)
        } else if (batteryLow) {
            cap = minOf(cap, 3)
        }
        return cfg.cpuThreads.coerceIn(1, cap)
    }

    private fun resolveEffectiveContextLength(cfg: LocalLlmConfig, thermalThrottled: Boolean, batteryLow: Boolean): Int {
        var cap = cfg.performanceProfile.contextCap
        if (thermalThrottled || batteryLow) {
            cap = minOf(cap, 1024)
        }
        return cfg.contextLength.coerceIn(256, cap)
    }

    private fun trimStopSequences(text: String, stopSequences: List<String>): String {
        var result = text
        for (stop in stopSequences) {
            if (stop.isNotEmpty() && result.endsWith(stop)) {
                result = result.removeSuffix(stop).trimEnd()
            }
        }
        return result
    }

    private fun unloadNativeOnly() {
        if (LlamaNativeBridge.isNativeAvailable) {
            runCatching { LlamaNativeBridge.nativeUnloadModel() }
        }
        loadedCtxLen = 0
        loadedThreads = 0
        loadedBatchSize = 0
        runCatching { openPfd?.close() }
        openPfd = null
    }
}
