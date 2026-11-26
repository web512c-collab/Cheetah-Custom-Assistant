package com.example.intelligence

import android.content.Context
import com.example.controller.DeviceActionExecutor
import com.example.controller.PermissionHealthChecker
import com.example.core.model.AssistantSettings
import com.example.core.model.ConversationTurnMessage
import com.example.core.model.InteractionTrace
import com.example.core.model.ProposedAction
import com.example.data.ConversationMemoryStore
import com.example.llm.DeviceHealthMonitor
import com.example.llm.LlmGenerationResult
import com.example.llm.LocalLlmManager
import com.example.search.OnlineSearchManager
import com.example.voice.CheetahRuntimeBus

data class ProcessedCommandOutcome(
    val replyAr: String,
    val spokenReplyAr: String = replyAr,
    val isBlocked: Boolean,
    val intentCategory: String,
    val trace: InteractionTrace,
    val streamedViaTts: Boolean = false,
    val proposedAction: ProposedAction? = null,
    val hasContinuation: Boolean = false,
    val fullTextForContinuation: String? = null,
    val suggestModelSettingsShortcut: Boolean = false,
    val wasInterrupted: Boolean = false
)

/**
 * Android-bound adapter delegating all command routing to [AssistantBrain],
 * keeping Foreground Service, ViewModel, AssistActivity, and VoiceInteractionSession aligned.
 */
class AssistantCommandProcessor(
    private val context: Context,
    private val memoryStore: ConversationMemoryStore,
    private val localLlmManager: LocalLlmManager? = null
) {

    private val brain = AssistantBrain(
        actionExecutor = BrainActionExecutor { intent, settings, memoryState ->
            val res = DeviceActionExecutor.execute(
                context = context,
                intent = intent,
                tone = settings.personaTone,
                memoryState = memoryState,
                assistantName = settings.assistantName,
                assistantGender = settings.assistantGender
            )
            BrainActionResult(
                succeeded = res.succeeded,
                replyAr = res.replyAr,
                newAppContext = res.newAppContext,
                newSearchQuery = res.newSearchQuery,
                newOpenedApp = res.newOpenedApp
            )
        },
        llmGateway = localLlmManager?.let { mgr ->
            object : BrainLlmGateway {
                override fun isModelLoaded(): Boolean = mgr.isModelLoaded()
                override fun hasConfiguredModel(settings: AssistantSettings): Boolean =
                    mgr.hasConfiguredModel(settings.localLlmConfig)

                override suspend fun ensureModelLoadedIfConfigured(): Boolean =
                    mgr.ensureModelLoadedIfConfigured().isSuccess

                override suspend fun generateBrainReply(
                    systemPrompt: String,
                    messages: List<ConversationTurnMessage>,
                    settings: AssistantSettings,
                    maxTokensOverride: Int?,
                    temperatureOverride: Float?,
                    reuseKvCache: Boolean,
                    onVisibleStreamText: (String) -> Unit,
                    onSentenceReady: (String, Boolean) -> Unit
                ): Result<LlmGenerationResult> = mgr.generateBrainReply(
                    systemPrompt = systemPrompt,
                    messages = messages,
                    settings = settings,
                    maxTokensOverride = maxTokensOverride,
                    temperatureOverride = temperatureOverride,
                    reuseKvCache = reuseKvCache,
                    onVisibleStreamText = onVisibleStreamText,
                    onSentenceReady = onSentenceReady
                )

                override suspend fun generateStructuredJson(
                    systemPrompt: String,
                    userPrompt: String,
                    gbnfGrammar: String,
                    settings: AssistantSettings,
                    maxTokens: Int
                ): Result<String> = mgr.generateStructuredJson(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    gbnfGrammar = gbnfGrammar,
                    settings = settings,
                    maxTokens = maxTokens
                )

                override fun cancelGeneration() = mgr.cancelGeneration()
            }
        },
        searchGateway = BrainSearchGateway { query, settings, maxResults ->
            OnlineSearchManager.search(
                query = query,
                internetSearchEnabled = settings.internetSearchEnabled,
                customSearxngUrl = settings.customSearxngUrl,
                maxResults = maxResults
            )
        },
        healthProvider = { DeviceHealthMonitor.inspect(context) }
    )

    suspend fun process(
        rawInput: String,
        settings: AssistantSettings,
        voiceMatchPassed: Boolean,
        voiceMatchScore: Float? = CheetahRuntimeBus.lastVoiceMatchScore.value,
        attachedText: String? = null,
        clipboardTextProvider: (() -> String?)? = null,
        onStopSpeech: () -> Unit = {},
        onStreamingToken: (String) -> Unit = {},
        onStreamingSentence: (sentence: String, isFirstChunk: Boolean) -> Unit = { _, _ -> }
    ): ProcessedCommandOutcome {
        val perms = PermissionHealthChecker.inspect(context)
        val memState = memoryStore.current()
        val outcome = brain.handleRequest(
            rawInput = rawInput,
            settings = settings,
            permissions = perms,
            memoryState = memState,
            voiceMatchPassed = voiceMatchPassed,
            voiceMatchScore = voiceMatchScore,
            attachedText = attachedText,
            pendingContinuationText = CheetahRuntimeBus.pendingContinuationText.value,
            clipboardTextProvider = clipboardTextProvider,
            onStopSpeech = onStopSpeech,
            onStreamingToken = onStreamingToken,
            onStreamingSentence = onStreamingSentence,
            onRecordTurn = { turn ->
                memoryStore.recordTurn(
                    userText = turn.userText,
                    assistantReply = turn.assistantReply,
                    intentCategory = turn.intentCategory,
                    newAppContext = turn.newAppContext,
                    newSearchQuery = turn.newSearchQuery,
                    newOpenedApp = turn.newOpenedApp,
                    newRollingSummary = turn.newRollingSummary
                )
            }
        )

        CheetahRuntimeBus.setStreamingReplyText("")
        CheetahRuntimeBus.setPendingContinuationText(outcome.fullTextForContinuation)
        CheetahRuntimeBus.setPendingProposedAction(outcome.proposedAction)
        return outcome
    }
}
