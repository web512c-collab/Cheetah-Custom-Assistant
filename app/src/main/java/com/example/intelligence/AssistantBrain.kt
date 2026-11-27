package com.example.intelligence

import com.example.core.model.ActiveAppContext
import com.example.core.model.AssistantSettings
import com.example.core.model.ConversationContextState
import com.example.core.model.ConversationTurnMessage
import com.example.core.model.DeviceHealthSnapshot
import com.example.core.model.EgyptianIntent
import com.example.core.model.GuardrailVerdict
import com.example.core.model.InteractionTrace
import com.example.core.model.PermissionHealthState
import com.example.core.model.ProposedAction
import com.example.core.model.WebSearchOutcome
import com.example.guardrails.CommandGuardrailValidator
import com.example.llm.LlmGenerationResult
import com.example.search.OnlineSearchManager
import com.example.search.SearchJudgePipeline
import com.example.voice.EgyptianPhoneticNormalizer

interface BrainLlmGateway {
    fun isModelLoaded(): Boolean
    fun hasConfiguredModel(settings: AssistantSettings): Boolean
    suspend fun ensureModelLoadedIfConfigured(): Boolean
    suspend fun generateBrainReply(
        systemPrompt: String,
        messages: List<ConversationTurnMessage>,
        settings: AssistantSettings,
        maxTokensOverride: Int? = null,
        temperatureOverride: Float? = null,
        reuseKvCache: Boolean = true,
        onVisibleStreamText: (String) -> Unit = {},
        onSentenceReady: (String, Boolean) -> Unit = { _, _ -> }
    ): Result<LlmGenerationResult>

    suspend fun generateStructuredJson(
        systemPrompt: String,
        userPrompt: String,
        gbnfGrammar: String,
        settings: AssistantSettings,
        maxTokens: Int = 220
    ): Result<String>

    fun cancelGeneration()
}

data class BrainActionResult(
    val succeeded: Boolean,
    val replyAr: String,
    val newAppContext: ActiveAppContext? = null,
    val newSearchQuery: String? = null,
    val newOpenedApp: String? = null
)

fun interface BrainActionExecutor {
    fun execute(
        intent: EgyptianIntent,
        settings: AssistantSettings,
        memoryState: ConversationContextState
    ): BrainActionResult
}

fun interface BrainSearchGateway {
    suspend fun search(
        query: String,
        settings: AssistantSettings,
        maxResults: Int
    ): WebSearchOutcome
}

data class BrainTurnRecord(
    val userText: String,
    val assistantReply: String,
    val intentCategory: String,
    val newAppContext: ActiveAppContext? = null,
    val newSearchQuery: String? = null,
    val newOpenedApp: String? = null,
    val newRollingSummary: String? = null
)

/**
 * Part 1 — Central Unified AI Orchestrator ("Brain").
 *
 * Enforces strict deterministic priority order on every user request:
 * 1. Explicit voice/text interruption & pending-continuation control.
 * 2. Deterministic Egyptian NLU + End-Line Guardrails (always FIRST).
 *    If it matches a device command or guardrail block -> execute/block deterministically and stop.
 * 3. Non-command requests needing fresh web info (when internetSearchEnabled is ON) ->
 *    Keyless HTTPS Search -> SearchJudgePipeline (scoring, cross-check, grounded synthesis).
 * 4. Non-command requests with local GGUF model (when useAiForGeneral is ON) ->
 *    TaskClassifier (13 categories) -> PromptTemplateEngine -> llama.cpp generation ->
 *    reasoning stripping, proposed-action guardrail verification & concise speech summary.
 * 5. Honest deterministic fallback when no local GGUF model is loaded, pointing to Model Settings.
 */
class AssistantBrain(
    private val actionExecutor: BrainActionExecutor,
    private val llmGateway: BrainLlmGateway? = null,
    private val searchGateway: BrainSearchGateway = BrainSearchGateway { q, s, cap ->
        OnlineSearchManager.search(
            query = q,
            internetSearchEnabled = s.internetSearchEnabled,
            customSearxngUrl = s.customSearxngUrl,
            maxResults = cap,
            searchEngineMode = s.searchEngineMode,
            autoSearchOrder = s.autoSearchOrder,
            enabledEngines = s.enabledSearchEngines,
            fetchPageExcerpts = s.fetchPageExcerpts
        )
    },
    private val healthProvider: () -> DeviceHealthSnapshot = { DeviceHealthSnapshot() },
    private val timeFormatter: () -> String = {
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
    }
) {

    suspend fun handleRequest(
        rawInput: String,
        settings: AssistantSettings,
        permissions: PermissionHealthState,
        memoryState: ConversationContextState,
        voiceMatchPassed: Boolean,
        voiceMatchScore: Float? = null,
        attachedText: String? = null,
        pendingContinuationText: String? = null,
        clipboardTextProvider: (() -> String?)? = null,
        onStopSpeech: () -> Unit = {},
        onStreamingToken: (String) -> Unit = {},
        onStreamingSentence: (sentence: String, isFirstChunk: Boolean) -> Unit = { _, _ -> },
        onRecordTurn: suspend (BrainTurnRecord) -> Unit = {}
    ): ProcessedCommandOutcome {
        val timeLabel = timeFormatter()
        val normRaw = EgyptianPhoneticNormalizer.normalizeForNlu(rawInput)

        // 0a. Voice/Text interruption ("وقف" / "كفاية" / "اسكت")
        if (isStopInterruptionCommand(normRaw, rawInput)) {
            llmGateway?.cancelGeneration()
            onStopSpeech()
            val reply = "تمام يا فندم، وقفت."
            return ProcessedCommandOutcome(
                replyAr = reply,
                spokenReplyAr = reply,
                isBlocked = false,
                intentCategory = "INTERRUPTED",
                wasInterrupted = true,
                trace = InteractionTrace(
                    rawUtterance = rawInput,
                    normalizedPhonetics = normRaw,
                    intentLabel = "VOICE_INTERRUPT",
                    guardrailStatus = "USER_STOP",
                    isBlocked = false,
                    assistantReplyAr = reply,
                    voiceMatchScore = voiceMatchScore,
                    timestampFormatted = timeLabel
                )
            )
        }

        // 0b. Continuation request ("كمل" / "اقرا الباقي") when a truncated long answer is queued
        if (!pendingContinuationText.isNullOrBlank() && isContinuationCommand(normRaw, rawInput)) {
            val speech = ReasoningOutputParser.buildConciseSpeechVersion(
                fullAnswer = pendingContinuationText,
                maxChars = settings.localLlmConfig.spokenMaxChars
            )
            val full = pendingContinuationText.trim()
            return ProcessedCommandOutcome(
                replyAr = full,
                spokenReplyAr = speech.spokenText,
                isBlocked = false,
                intentCategory = "CONTINUATION",
                hasContinuation = speech.hasContinuation,
                fullTextForContinuation = if (speech.hasContinuation) speech.remainingText else null,
                trace = InteractionTrace(
                    rawUtterance = rawInput,
                    normalizedPhonetics = normRaw,
                    intentLabel = "VOICE_CONTINUATION",
                    guardrailStatus = "CONTINUATION_PLAYBACK",
                    isBlocked = false,
                    assistantReplyAr = full,
                    voiceMatchScore = voiceMatchScore,
                    timestampFormatted = timeLabel,
                    hasContinuation = speech.hasContinuation,
                    fullTextForContinuation = if (speech.hasContinuation) speech.remainingText else null
                )
            )
        }

        // 0c. Explicit clipboard request ONLY when user explicitly mentions clipboard
        var effectiveAttachedText = attachedText?.trim()?.takeIf { it.isNotEmpty() }
        if (effectiveAttachedText == null && isExplicitClipboardRequest(normRaw, rawInput)) {
            val clip = runCatching { clipboardTextProvider?.invoke() }.getOrNull()?.trim()
            if (clip.isNullOrEmpty()) {
                val emptyReply = "الحافظة فاضية حالياً يا فندم. انسخ نص الأول أو شاركه للتطبيق وأنا تحت أمرك."
                return ProcessedCommandOutcome(
                    replyAr = emptyReply,
                    spokenReplyAr = emptyReply,
                    isBlocked = false,
                    intentCategory = "CLIPBOARD_EMPTY",
                    trace = InteractionTrace(
                        rawUtterance = rawInput,
                        normalizedPhonetics = normRaw,
                        intentLabel = "CLIPBOARD_EMPTY",
                        guardrailStatus = "EXPLICIT_CLIPBOARD_READ",
                        isBlocked = false,
                        assistantReplyAr = emptyReply,
                        voiceMatchScore = voiceMatchScore,
                        timestampFormatted = timeLabel
                    )
                )
            }
            effectiveAttachedText = clip
        }

        // 1. DETERMINISTIC LAYER FIRST (Egyptian NLU + End-Line Guardrails)
        val (normalizedPhonetic, parsedIntent) = EgyptianColloquialNluParser.parse(
            rawInput = rawInput,
            configuredWakePhrase = settings.wakePhrase,
            contextState = memoryState
        )
        val verdict = CommandGuardrailValidator.validate(
            intent = parsedIntent,
            settings = settings,
            permissions = permissions,
            voiceMatchPassed = voiceMatchPassed
        )

        when (verdict) {
            is GuardrailVerdict.Denied -> {
                val reply = JarvisEgyptianResponder.formatGuardrailBlockReply(
                    reasonAr = verdict.reasonAr,
                    tone = settings.personaTone,
                    gender = settings.assistantGender
                )
                onRecordTurn(BrainTurnRecord(rawInput, reply, parsedIntent.categoryName))
                return ProcessedCommandOutcome(
                    replyAr = reply,
                    spokenReplyAr = reply,
                    isBlocked = true,
                    intentCategory = parsedIntent.categoryName,
                    trace = InteractionTrace(
                        rawUtterance = rawInput,
                        normalizedPhonetics = normalizedPhonetic,
                        intentLabel = parsedIntent.categoryName,
                        guardrailStatus = "BLOCKED (${verdict.ruleId})",
                        isBlocked = true,
                        assistantReplyAr = reply,
                        voiceMatchScore = voiceMatchScore,
                        timestampFormatted = timeLabel
                    )
                )
            }

            is GuardrailVerdict.Allowed -> {
                val validatedIntent = verdict.intent
                val isGeneralQuery = validatedIntent is EgyptianIntent.KnowledgeOrChatQuery ||
                    validatedIntent is EgyptianIntent.Unknown

                if (!isGeneralQuery) {
                    val execResult = actionExecutor.execute(validatedIntent, settings, memoryState)
                    onRecordTurn(
                        BrainTurnRecord(
                            userText = rawInput,
                            assistantReply = execResult.replyAr,
                            intentCategory = validatedIntent.categoryName,
                            newAppContext = execResult.newAppContext,
                            newSearchQuery = execResult.newSearchQuery,
                            newOpenedApp = execResult.newOpenedApp
                        )
                    )
                    return ProcessedCommandOutcome(
                        replyAr = execResult.replyAr,
                        spokenReplyAr = execResult.replyAr,
                        isBlocked = false,
                        intentCategory = validatedIntent.categoryName,
                        trace = InteractionTrace(
                            rawUtterance = rawInput,
                            normalizedPhonetics = normalizedPhonetic,
                            intentLabel = validatedIntent.categoryName,
                            guardrailStatus = "ALLOWLIST_VERIFIED",
                            isBlocked = false,
                            assistantReplyAr = execResult.replyAr,
                            voiceMatchScore = voiceMatchScore,
                            timestampFormatted = timeLabel
                        )
                    )
                }

                // 2. Non-command intelligence routing (Search-and-Judge / Local GGUF Brain / Fallback)
                return routeGeneralIntelligence(
                    rawInput = rawInput,
                    normalizedPhonetic = normalizedPhonetic,
                    intent = validatedIntent,
                    settings = settings,
                    permissions = permissions,
                    memoryState = memoryState,
                    voiceMatchPassed = voiceMatchPassed,
                    voiceMatchScore = voiceMatchScore,
                    attachedText = effectiveAttachedText,
                    timeLabel = timeLabel,
                    onStreamingToken = onStreamingToken,
                    onStreamingSentence = onStreamingSentence,
                    onRecordTurn = onRecordTurn
                )
            }
        }
    }

    private suspend fun routeGeneralIntelligence(
        rawInput: String,
        normalizedPhonetic: String,
        intent: EgyptianIntent,
        settings: AssistantSettings,
        permissions: PermissionHealthState,
        memoryState: ConversationContextState,
        voiceMatchPassed: Boolean,
        voiceMatchScore: Float?,
        attachedText: String?,
        timeLabel: String,
        onStreamingToken: (String) -> Unit,
        onStreamingSentence: (String, Boolean) -> Unit,
        onRecordTurn: suspend (BrainTurnRecord) -> Unit
    ): ProcessedCommandOutcome {
        val queryText = when (intent) {
            is EgyptianIntent.KnowledgeOrChatQuery -> intent.query
            is EgyptianIntent.Unknown -> intent.rawText
            else -> rawInput
        }
        val explicitOnline = (intent as? EgyptianIntent.KnowledgeOrChatQuery)?.explicitlyRequestsOnlineSearch == true

        // Privacy gate: explicit online request when Internet Search is OFF
        if (explicitOnline && !settings.internetSearchEnabled) {
            val reply = JarvisEgyptianResponder.formatInternetSearchDisabledReply(
                query = queryText,
                tone = settings.personaTone,
                gender = settings.assistantGender
            )
            onRecordTurn(BrainTurnRecord(rawInput, reply, intent.categoryName))
            return ProcessedCommandOutcome(
                replyAr = reply,
                spokenReplyAr = reply,
                isBlocked = false,
                intentCategory = intent.categoryName,
                trace = InteractionTrace(
                    rawUtterance = rawInput,
                    normalizedPhonetics = normalizedPhonetic,
                    intentLabel = "${intent.categoryName} (OFFLINE_SEARCH_DISABLED)",
                    guardrailStatus = "OFFLINE_PRIVACY_GUARD",
                    isBlocked = false,
                    assistantReplyAr = reply,
                    voiceMatchScore = voiceMatchScore,
                    timestampFormatted = timeLabel
                )
            )
        }

        // Auto-reload GGUF if user picked one and it was unloaded due to inactivity
        val gateway = llmGateway
        var modelLoaded = gateway?.isModelLoaded() == true
        val wantsModelForGeneral = settings.localLlmConfig.useAiForGeneral
        val wantsModelForSearch = settings.internetSearchEnabled && settings.localLlmConfig.judgeSearchWithAi
        if (!modelLoaded && gateway != null && (wantsModelForGeneral || wantsModelForSearch) &&
            gateway.hasConfiguredModel(settings)
        ) {
            modelLoaded = runCatching { gateway.ensureModelLoadedIfConfigured() }.getOrDefault(false)
        }

        val ruleClassification = TaskClassifier.classifyRulesOnly(
            rawInput = rawInput,
            hasAttachedText = !attachedText.isNullOrBlank()
        )
        val shouldSearchWeb = settings.internetSearchEnabled &&
            attachedText.isNullOrBlank() &&
            (explicitOnline || ruleClassification.needsFreshInternetInfo)

        // STEP 3: Search-and-Judge Pipeline (when fresh info is needed & search is enabled)
        if (shouldSearchWeb) {
            val health = healthProvider()
            var effectiveSearchCap = settings.localLlmConfig.searchEvalCount
                .coerceIn(1, settings.localLlmConfig.performanceProfile.searchEvalCap)
            if (health.isThermalThrottled || health.isBatteryLow) {
                effectiveSearchCap = minOf(effectiveSearchCap, 3)
            }

            val searchOutcome = searchGateway.search(queryText, settings, effectiveSearchCap)
            if (searchOutcome.sources.isNotEmpty()) {
                var streamedAny = false
                val canJudgeWithAi = modelLoaded && settings.localLlmConfig.judgeSearchWithAi && gateway != null
                val evalStartMs = System.currentTimeMillis()
                val verified = SearchJudgePipeline.evaluateAndSynthesize(
                    query = queryText,
                    rawSources = searchOutcome.sources,
                    settings = settings,
                    modelLoaded = canJudgeWithAi,
                    maxEvalCountOverride = effectiveSearchCap,
                    jsonEvaluator = if (canJudgeWithAi) {
                        { sys, usr, gbnf ->
                            gateway.generateStructuredJson(sys, usr, gbnf, settings)
                        }
                    } else null,
                    answerSynthesizer = if (canJudgeWithAi) {
                        { sys, usr ->
                            gateway.generateBrainReply(
                                systemPrompt = sys,
                                messages = listOf(ConversationTurnMessage("user", usr)),
                                settings = settings,
                                reuseKvCache = false,
                                onVisibleStreamText = onStreamingToken,
                                onSentenceReady = { s, first ->
                                    streamedAny = true
                                    onStreamingSentence(s, first)
                                }
                            ).map { it.visibleAnswer }
                        }
                    } else null
                )
                val elapsedMs = (System.currentTimeMillis() - evalStartMs).coerceAtLeast(10L)
                val acceptedSources = verified.judgedSources.filter { it.accepted }.map { it.source }
                val speech = ReasoningOutputParser.buildConciseSpeechVersion(
                    fullAnswer = verified.answerText,
                    maxChars = settings.localLlmConfig.spokenMaxChars
                )
                val needsShortcut = !modelLoaded && settings.localLlmConfig.judgeSearchWithAi

                onRecordTurn(
                    BrainTurnRecord(
                        userText = rawInput,
                        assistantReply = verified.answerText,
                        intentCategory = if (verified.judgedByAi) "SEARCH_JUDGED" else "KEYLESS_SEARCH",
                        newAppContext = ActiveAppContext.BROWSER,
                        newSearchQuery = queryText
                    )
                )

                return ProcessedCommandOutcome(
                    replyAr = verified.answerText,
                    spokenReplyAr = speech.spokenText,
                    isBlocked = false,
                    intentCategory = intent.categoryName,
                    streamedViaTts = streamedAny,
                    hasContinuation = speech.hasContinuation,
                    fullTextForContinuation = if (speech.hasContinuation) speech.remainingText else null,
                    suggestModelSettingsShortcut = needsShortcut,
                    trace = InteractionTrace(
                        rawUtterance = rawInput,
                        normalizedPhonetics = normalizedPhonetic,
                        intentLabel = if (verified.judgedByAi) "SEARCH+AI_JUDGE" else "KEYLESS_HTTPS_SEARCH",
                        guardrailStatus = if (verified.judgedByAi) "SEARCH_JUDGED_LOCAL_LLM" else "HTTPS_SEARCH_UNJUDGED",
                        isBlocked = false,
                        assistantReplyAr = verified.answerText,
                        voiceMatchScore = voiceMatchScore,
                        timestampFormatted = timeLabel,
                        sources = acceptedSources.ifEmpty { searchOutcome.sources },
                        generatedByLocalLlm = verified.judgedByAi,
                        taskTypeLabel = ruleClassification.taskType.labelAr,
                        thinkingElapsedMs = if (verified.judgedByAi) elapsedMs else null,
                        searchConfidence = verified.confidence,
                        judgedSources = verified.judgedSources,
                        hasContinuation = speech.hasContinuation,
                        fullTextForContinuation = if (speech.hasContinuation) speech.remainingText else null,
                        suggestModelSettingsShortcut = needsShortcut
                    )
                )
            } else if (explicitOnline || !modelLoaded || !wantsModelForGeneral) {
                val reply = searchOutcome.errorAr ?: "ملقتش نتائج موثوقة عن «$queryText» ومش هخمن من عندي."
                onRecordTurn(BrainTurnRecord(rawInput, reply, intent.categoryName))
                return ProcessedCommandOutcome(
                    replyAr = reply,
                    spokenReplyAr = reply,
                    isBlocked = false,
                    intentCategory = intent.categoryName,
                    suggestModelSettingsShortcut = !modelLoaded,
                    trace = InteractionTrace(
                        rawUtterance = rawInput,
                        normalizedPhonetics = normalizedPhonetic,
                        intentLabel = intent.categoryName,
                        guardrailStatus = "ONLINE_SEARCH_NO_HIT",
                        isBlocked = false,
                        assistantReplyAr = reply,
                        voiceMatchScore = voiceMatchScore,
                        timestampFormatted = timeLabel,
                        suggestModelSettingsShortcut = !modelLoaded
                    )
                )
            }
        }

        // STEP 2: Route to Local GGUF Model as General Intelligence Brain
        if (modelLoaded && wantsModelForGeneral && llmGateway != null) {
            val classification = TaskClassifier.classifyWithOptionalModel(
                rawInput = rawInput,
                hasAttachedText = !attachedText.isNullOrBlank(),
                modelClassifier = { classifyPrompt ->
                    llmGateway.generateBrainReply(
                        systemPrompt = "Classify task type in one word.",
                        messages = listOf(ConversationTurnMessage("user", classifyPrompt)),
                        settings = settings,
                        maxTokensOverride = 14,
                        temperatureOverride = 0.05f,
                        reuseKvCache = false
                    ).getOrNull()?.visibleAnswer
                }
            )

            val userPrompt = PromptTemplateEngine.buildUserPrompt(
                rawInput = rawInput,
                attachedText = attachedText,
                taskType = classification.taskType
            )
            val (rollingSummary, rollingMessages) = PromptTemplateEngine.buildRollingMessages(
                memoryState = memoryState,
                currentUserPrompt = userPrompt
            )
            val sysPrompt = PromptTemplateEngine.buildSystemPrompt(
                settings = settings,
                taskType = classification.taskType,
                aiMode = settings.localLlmConfig.aiMode,
                nonArabicInput = classification.isNonArabicUserInput,
                rollingSummary = rollingSummary
            )

            var streamedAny = false
            val llmRes = llmGateway.generateBrainReply(
                systemPrompt = sysPrompt,
                messages = rollingMessages,
                settings = settings,
                reuseKvCache = true,
                onVisibleStreamText = onStreamingToken,
                onSentenceReady = { sentence, first ->
                    streamedAny = true
                    onStreamingSentence(sentence, first)
                }
            )

            val gen = llmRes.getOrNull()
            if (gen != null && gen.visibleAnswer.isNotBlank()) {
                val candidateAction = PromptTemplateEngine.parseProposedAction(gen.rawText)
                val validatedAction: ProposedAction? = candidateAction?.let { proposed ->
                    when (CommandGuardrailValidator.validate(proposed.intent, settings, permissions, voiceMatchPassed)) {
                        is GuardrailVerdict.Allowed -> proposed
                        is GuardrailVerdict.Denied -> null
                    }
                }

                val speech = ReasoningOutputParser.buildConciseSpeechVersion(
                    fullAnswer = gen.visibleAnswer,
                    maxChars = settings.localLlmConfig.spokenMaxChars
                )
                onRecordTurn(
                    BrainTurnRecord(
                        userText = rawInput,
                        assistantReply = gen.visibleAnswer,
                        intentCategory = "LOCAL_LLM_${classification.taskType.name}",
                        newRollingSummary = rollingSummary
                    )
                )

                return ProcessedCommandOutcome(
                    replyAr = gen.visibleAnswer,
                    spokenReplyAr = speech.spokenText,
                    isBlocked = false,
                    intentCategory = intent.categoryName,
                    streamedViaTts = streamedAny,
                    proposedAction = validatedAction,
                    hasContinuation = speech.hasContinuation,
                    fullTextForContinuation = if (speech.hasContinuation) speech.remainingText else null,
                    trace = InteractionTrace(
                        rawUtterance = rawInput,
                        normalizedPhonetics = normalizedPhonetic,
                        intentLabel = "AI_BRAIN • ${classification.taskType.labelAr}",
                        guardrailStatus = if (validatedAction != null) "LOCAL_LLM_ACTION_PROPOSED" else "LOCAL_LLM_READONLY",
                        isBlocked = false,
                        assistantReplyAr = gen.visibleAnswer,
                        voiceMatchScore = voiceMatchScore,
                        timestampFormatted = timeLabel,
                        generatedByLocalLlm = true,
                        taskTypeLabel = classification.taskType.labelAr,
                        reasoningText = gen.reasoningText.takeIf { it.isNotBlank() },
                        thinkingElapsedMs = gen.elapsedMs,
                        proposedAction = validatedAction,
                        hasContinuation = speech.hasContinuation,
                        fullTextForContinuation = if (speech.hasContinuation) speech.remainingText else null
                    )
                )
            }
        }

        // STEP 4: Honest deterministic fallback + clear explanation that local GGUF model is needed
        val fallbackReply = PromptTemplateEngine.formatNoModelAvailableReply(
            settings = settings,
            taskType = ruleClassification.taskType
        )
        onRecordTurn(BrainTurnRecord(rawInput, fallbackReply, intent.categoryName))
        return ProcessedCommandOutcome(
            replyAr = fallbackReply,
            spokenReplyAr = fallbackReply,
            isBlocked = false,
            intentCategory = intent.categoryName,
            suggestModelSettingsShortcut = true,
            trace = InteractionTrace(
                rawUtterance = rawInput,
                normalizedPhonetics = normalizedPhonetic,
                intentLabel = "${intent.categoryName} (NO_MODEL)",
                guardrailStatus = "LOCAL_MODEL_REQUIRED",
                isBlocked = false,
                assistantReplyAr = fallbackReply,
                voiceMatchScore = voiceMatchScore,
                timestampFormatted = timeLabel,
                taskTypeLabel = ruleClassification.taskType.labelAr,
                suggestModelSettingsShortcut = true
            )
        )
    }

    companion object {
        fun isStopInterruptionCommand(normArabic: String, rawInput: String): Boolean {
            val exactNorm = normArabic.trim()
            val lower = rawInput.trim().lowercase()
            return exactNorm in setOf(
                "وقف", "كفايه", "اسكت", "بس كده", "استني", "خلاص وقف", "بطل كلام", "كفاية كلام"
            ) || lower in setOf("stop", "stop talking", "cancel", "shut up", "quiet")
        }

        fun isContinuationCommand(normArabic: String, rawInput: String): Boolean {
            val exactNorm = normArabic.trim()
            val lower = rawInput.trim().lowercase()
            return exactNorm in setOf(
                "كمل", "كمّل", "كمل الباقي", "اقرا الباقي", "ايوه كمل", "اه كمل", "قول الباقي"
            ) || lower in setOf("continue", "keep going", "read more", "yes continue")
        }

        fun isExplicitClipboardRequest(normArabic: String, rawInput: String): Boolean {
            val lower = rawInput.trim().lowercase()
            return normArabic.contains("الحافظه") ||
                normArabic.contains("اللي نسخته") ||
                normArabic.contains("المنسوخ") ||
                normArabic.contains("كليپبورد") ||
                lower.contains("clipboard")
        }
    }
}
