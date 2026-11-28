package com.example.core.model

enum class WakeWordSetupMode(val labelAr: String, val labelEn: String) {
    VOICE_RECORDED("نموذج صوتي محلي (MFCC-DTW)", "Acoustic MFCC-DTW"),
    TEXT_TYPED("نص فقط (يتطلب تحقق STT)", "Text-Only (STT Match)"),
    HYBRID("صوتي + نصي", "Hybrid Voice & Text")
}

enum class PersonaTone(
    val labelAr: String,
    val subtitleAr: String,
    val badgeEn: String
) {
    JARVIS_FORMAL_EGYPTIAN(
        labelAr = "جارفيس مصري متزن",
        subtitleAr = "رسمي راقي، لبق ووقور بالعامية المصرية: «تحت أمرك يا فندم»",
        badgeEn = "J.A.R.V.I.S. Tactical"
    ),
    FRIENDLY_EGYPTIAN(
        labelAr = "مصري ودود",
        subtitleAr = "خفيف الدم وقريب منك: «من عيوني يا بطل»",
        badgeEn = "Friendly Companion"
    ),
    CONCISE_TACTICAL(
        labelAr = "مختصر ومباشر",
        subtitleAr = "أقل كلام وأسرع تنفيذ بدون مقدمات",
        badgeEn = "Minimal Ops"
    )
}

enum class AssistantGender(val labelAr: String, val labelEn: String) {
    MALE("مذكر", "Male"),
    FEMALE("مؤنث", "Female")
}

enum class WakePipelineState(val labelAr: String, val labelEn: String) {
    IDLE_WAKE_LISTENING("بيسمع لكلمة التنبيه أوفلاين...", "IDLE_WAKE_LISTENING"),
    WAKE_DETECTED("تم التقاط كلمة التنبيه", "WAKE_DETECTED"),
    COMMAND_LISTENING("سامعك، قول أمرك...", "COMMAND_LISTENING"),
    PROCESSING("بيحلل وينفذ الأمر...", "PROCESSING"),
    SPEAKING("بيرد بالعامية المصرية...", "SPEAKING")
}

data class TransitionLogEntry(
    val id: Long,
    val timestampMs: Long,
    val fromState: WakePipelineState,
    val toState: WakePipelineState,
    val reason: String,
    val isError: Boolean = false
)

enum class AssistantPipelineState(val labelAr: String, val labelEn: String) {
    IDLE("في وضع الاستعداد", "STANDBY"),
    LISTENING_WAKE_WORD("بيسمع لكلمة التنبيه أوفلاين...", "WAKE_LISTENING"),
    VERIFYING_VOICE_MATCH("بيتحقق من بصمة الصوت...", "VOICE_MATCH"),
    WAKE_ACKNOWLEDGED("أيوه، معاك...", "WAKE_ACK"),
    LISTENING_COMMAND("سامعك، قول أمرك...", "COMMAND_STT"),
    THINKING_LOCAL("بيحلل الأمر محلياً...", "LOCAL_NLU"),
    EXECUTING_ACTION("بينفذ الأمر...", "EXECUTING"),
    SPEAKING("بيرد بالعامية المصرية...", "TTS_SPEAKING"),
    BLOCKED_BY_GUARDRAIL("مرفوض — خط أحمر أمني", "END_LINE_BLOCKED"),
    ERROR("حصل مشكلة بسيطة", "ERROR")
}

data class WakeDiagnosticSnapshot(
    val audioRecordStatus: String = "STOPPED",
    val audioSourceUsed: String = "NONE",
    val sampleRateHz: Int = 16000,
    val wakeEngineStatus: String = "UNINITIALIZED",
    val modelStatus: String = "0/5 MFCC-DTW (نموذج CHKW-v1 مساعد)",
    val detectionThreshold: Float = 0.62f,
    val lastConfidence: Float? = null,
    val lastVoiceMatchScore: Float? = null,
    val lastVoiceMatchResult: String = "N/A",
    val lastAudioEvent: String = "لا يوجد نشاط بعد",
    val lastWakeTimestampMs: Long? = null,
    val lastError: String? = null,
    val falseActivationsCount: Int = 0,
    val ambientNoiseRms: Float = 0f,
    val audioRecordRecoveriesCount: Int = 0,
    val watchdogTimeoutsCount: Int = 0
)

enum class LocalLlmStorageMode(val labelAr: String) {
    IN_PLACE_URI("تشغيل من الملف الأصلي (SAF بدون نسخ)"),
    COPIED_INTERNAL("نسخ لملفات التطبيق الداخلية")
}

enum class LocalLlmLoadState(val labelAr: String) {
    UNLOADED("غير محمّل"),
    LOADING("جاري التحميل..."),
    LOADED("محمّل وجاهز"),
    ERROR("خطأ في التحميل")
}

data class GgufModelMetadata(
    val displayName: String = "",
    val fileSizeBytes: Long = 0L,
    val architecture: String = "",
    val quantization: String = "",
    val sourceUri: String = "",
    val internalPath: String = "",
    val storageMode: LocalLlmStorageMode = LocalLlmStorageMode.IN_PLACE_URI,
    val chatTemplateRaw: String = "",
    val chatTemplateFamily: ChatTemplateFamily = ChatTemplateFamily.GENERIC_UNKNOWN,
    val templateWarningAr: String? = null
)

data class LocalLlmConfig(
    val modelUri: String = "",
    val internalPath: String = "",
    val displayName: String = "",
    val fileSizeBytes: Long = 0L,
    val quantization: String = "",
    val architecture: String = "",
    val storageMode: LocalLlmStorageMode = LocalLlmStorageMode.IN_PLACE_URI,
    val contextLength: Int = 1024,
    val maxTokens: Int = 192,
    val temperature: Float = 0.65f,
    val topP: Float = 0.90f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.10f,
    val seed: Int = -1, // -1 = random (LLAMA_DEFAULT_SEED), >= 0 = fixed deterministic seed
    val batchSize: Int = 256,
    val cpuThreads: Int = 4,
    val chatTemplateRaw: String = "",
    val chatTemplateFamily: ChatTemplateFamily = ChatTemplateFamily.GENERIC_UNKNOWN,
    val chatTemplateOverride: ChatTemplateOverride = ChatTemplateOverride.AUTO,
    val customSystemPrompt: String = "",
    val responseStyle: ResponseStyle = ResponseStyle.BALANCED,
    val spokenMaxChars: Int = 240,
    val aiLanguagePreference: AiLanguagePreference = AiLanguagePreference.EGYPTIAN_ARABIC,
    val stopSequences: List<String> = DEFAULT_STOP_SEQUENCES,
    val stripReasoningTags: Boolean = true,
    val activePreset: AiPreset = AiPreset.BALANCED,
    val useAiForGeneral: Boolean = true,
    val aiMode: AiMode = AiMode.BALANCED,
    val judgeSearchWithAi: Boolean = true,
    val searchEvalCount: Int = 5,
    val showReasoning: Boolean = false,
    val showSources: Boolean = true,
    val maxThinkingSeconds: Int = 25,
    val performanceProfile: PerformanceProfile = PerformanceProfile.BALANCED,
    val autoUnloadMinutes: Int = 10
) {
    companion object {
        val DEFAULT_STOP_SEQUENCES = listOf(
            "<|im_end|>",
            "<|eot_id|>",
            "<end_of_turn>",
            "</s>",
            "<|end|>"
        )
    }
}

data class LocalLlmRuntimeStatus(
    val loadState: LocalLlmLoadState = LocalLlmLoadState.UNLOADED,
    val metadata: GgufModelMetadata? = null,
    val isGenerating: Boolean = false,
    val isThinking: Boolean = false,
    val thinkingElapsedMs: Long = 0L,
    val nativeBackendAvailable: Boolean = true,
    val freeInternalBytes: Long = 0L,
    val availableRamMb: Long = 0L,
    val totalRamMb: Long = 0L,
    val estimatedRamMb: Long = 0L,
    val loadedContextLen: Int = 0,
    val ramWarningAr: String? = null,
    val templateWarningAr: String? = null,
    val lastErrorAr: String? = null,
    val tokensPerSec: Float = 0f,
    val lastBenchmark: ModelBenchmarkResult? = null,
    val deviceHealth: DeviceHealthSnapshot = DeviceHealthSnapshot()
)

data class TtsVoiceCandidate(
    val name: String,
    val localeTag: String,
    val detectedGender: AssistantGender?,
    val isOffline: Boolean,
    val qualityScore: Int
)

enum class SearchEngineMode(val labelAr: String, val endpointDisclosureAr: String) {
    DUCKDUCKGO(
        labelAr = "DuckDuckGo (افتراضي)",
        endpointDisclosureAr = "يستخدم واجهة DuckDuckGo Instant Answer JSON الرسمية + صفحة DuckDuckGo HTML العامة بدون مفتاح API."
    ),
    WIKIPEDIA(
        labelAr = "Wikipedia (عربي / إنجليزي)",
        endpointDisclosureAr = "يستخدم واجهة MediaWiki Search API و REST Summary الرسمية بدون مفتاح API (يتبع لغة السؤال)."
    ),
    MOJEEK(
        labelAr = "Mojeek (بحث مستقل)",
        endpointDisclosureAr = "يقرأ نتائج صفحة Mojeek العامة عبر HTTPS بدون مفتاح API."
    ),
    SEARXNG(
        labelAr = "SearXNG (خادم مخصص)",
        endpointDisclosureAr = "يتصل بنقطة /search?format=json على خادم SearXNG HTTPS الذي تحدده."
    ),
    AUTO(
        labelAr = "تلقائي Auto (بحث متوازي ومقارن)",
        endpointDisclosureAr = "يبحث في المحركات المفعّلة بالتوازي، يوحد الروابط المكررة بـ RRF، ويقارن توافق النتائج."
    )
}

enum class ConcreteSearchEngine(val labelAr: String) {
    DUCKDUCKGO("DuckDuckGo"),
    WIKIPEDIA("Wikipedia"),
    MOJEEK("Mojeek"),
    SEARXNG("SearXNG")
}

data class PerEngineTestReport(
    val engineName: String,
    val success: Boolean,
    val resultCount: Int = 0,
    val elapsedMs: Long = 0L,
    val note: String = ""
)

data class SearchEngineTestResult(
    val success: Boolean,
    val messageAr: String,
    val resultCount: Int = 0,
    val responseTimeMs: Long = 0L,
    val engineUsed: String = "",
    val perEngineReports: List<PerEngineTestReport> = emptyList()
)

data class WebSearchSource(
    val title: String,
    val snippet: String,
    val url: String,
    val provider: String,
    val sourceDomain: String = "",
    val publishedDate: String? = null,
    val engineName: String = provider,
    val contributingEngines: List<String> = listOf(engineName)
)

data class WebSearchOutcome(
    val query: String,
    val summaryText: String,
    val sources: List<WebSearchSource> = emptyList(),
    val fromCache: Boolean = false,
    val errorAr: String? = null,
    val engineUsed: String = "",
    val responseTimeMs: Long = 0L,
    val perEngineReports: List<PerEngineTestReport> = emptyList()
)

data class EndLineRule(
    val id: String,
    val titleAr: String,
    val descriptionAr: String,
    val isHardLocked: Boolean,
    val isEnabled: Boolean
) {
    companion object {
        const val RULE_BLOCK_FINANCIAL = "ENDLINE_BLOCK_FINANCIAL"
        const val RULE_BLOCK_SYSTEM_DESTRUCTIVE = "ENDLINE_BLOCK_SYSTEM_DESTRUCTIVE"
        const val RULE_BLOCK_CREDENTIAL_HARVEST = "ENDLINE_BLOCK_CREDENTIAL_HARVEST"
        const val RULE_REQUIRE_VOICE_MATCH_COMMS = "GATE_VOICE_MATCH_COMMS"
        const val RULE_CONFIRM_CALLS_AND_SMS = "GATE_CONFIRM_CALL_SMS"
        const val RULE_ACCESSIBILITY_SCREEN_CONTROL = "GATE_ACCESSIBILITY_CONTROL"

        fun defaultRules(): List<EndLineRule> = listOf(
            EndLineRule(
                id = RULE_BLOCK_FINANCIAL,
                titleAr = "منع المعاملات البنكية والتحويلات",
                descriptionAr = "خط أحمر ثابت: ممنوع تماماً تنفيذ تحويلات مالية أو دفع إلكتروني تلقائياً.",
                isHardLocked = true,
                isEnabled = true
            ),
            EndLineRule(
                id = RULE_BLOCK_SYSTEM_DESTRUCTIVE,
                titleAr = "منع المسح الشامل أو حذف التطبيقات",
                descriptionAr = "خط أحمر ثابت: ممنوع مسح الداتا، الفورمات، أو إزالة تطبيقات النظام.",
                isHardLocked = true,
                isEnabled = true
            ),
            EndLineRule(
                id = RULE_BLOCK_CREDENTIAL_HARVEST,
                titleAr = "حماية كلمات السر وأكواد التحقق (OTP)",
                descriptionAr = "خط أحمر ثابت: ممنوع قراءة أو مشاركة الباسوردات أو رسائل البنك.",
                isHardLocked = true,
                isEnabled = true
            ),
            EndLineRule(
                id = RULE_REQUIRE_VOICE_MATCH_COMMS,
                titleAr = "بصمة الصوت للأوامر الحساسة",
                descriptionAr = "التحقق من صوت المالك قبل فتح شاشة المكالمات أو التحكم بالشاشة (مع بديل آمن).",
                isHardLocked = false,
                isEnabled = true
            ),
            EndLineRule(
                id = RULE_CONFIRM_CALLS_AND_SMS,
                titleAr = "فتح شاشة الاتصال/الرسائل فقط بدون إرسال صامت",
                descriptionAr = "بيجهز الرقم أو الرسالة قدامك على الشاشة وإنت اللي بتدوس إرسال.",
                isHardLocked = false,
                isEnabled = true
            ),
            EndLineRule(
                id = RULE_ACCESSIBILITY_SCREEN_CONTROL,
                titleAr = "السماح بالتحكم في الشاشة (Accessibility)",
                descriptionAr = "تفعيل أوامر التمرير والرجوع والضغط على العناصر الظاهرة عند الحاجة.",
                isHardLocked = false,
                isEnabled = false
            )
        )
    }
}

data class VoiceTemplateSample(
    val id: String,
    val label: String,
    val featureVector: FloatArray,
    val mfccSequence: List<FloatArray> = emptyList(),
    val durationMs: Long,
    val peakRms: Float,
    val timestampMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceTemplateSample) return false
        return id == other.id && featureVector.contentEquals(other.featureVector)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + featureVector.contentHashCode()
        return result
    }
}

data class AssistantSettings(
    val assistantName: String = "شيتا",
    val assistantGender: AssistantGender = AssistantGender.MALE,
    val voiceGender: AssistantGender = AssistantGender.MALE,
    val selectedTtsVoiceName: String = "",
    val wakePhrase: String = "يا شيتا",
    val wakeSetupMode: WakeWordSetupMode = WakeWordSetupMode.TEXT_TYPED,
    val wakeSensitivity: Float = 0.65f,
    val voiceMatchEnabled: Boolean = false,
    val voiceMatchThreshold: Float = 0.68f,
    val allowOwnerFallbackOnLowVoiceMatch: Boolean = true,
    val wakeAckPhrase: String = "أيوه، معاك",
    val personaTone: PersonaTone = PersonaTone.JARVIS_FORMAL_EGYPTIAN,
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 0.96f,
    val continuousListeningEnabled: Boolean = false,
    val isListeningMuted: Boolean = false,
    val allowAccessibilityAutomation: Boolean = false,
    val requireVoiceMatchForSensitive: Boolean = false,
    val openDialerInsteadOfDirectCall: Boolean = true,
    // OS single-truth permission prompt history
    val micPermissionRequestedOnce: Boolean = false,
    val notifPermissionRequestedOnce: Boolean = false,
    val autoRevokeTipDismissed: Boolean = false,
    // Local offline Vosk STT model (imported via SAF, zero bundled Google/network STT)
    val sttModelDir: String = "",
    val sttModelName: String = "",
    val sttModelSizeBytes: Long = 0L,
    val sttModelLanguage: String = "ar",
    // Keyless HTTPS search (ON by default; sends ONLY cleaned search queries when triggered)
    val internetSearchEnabled: Boolean = true,
    val searchEngineMode: SearchEngineMode = SearchEngineMode.DUCKDUCKGO,
    val autoSearchOrder: List<ConcreteSearchEngine> = DEFAULT_AUTO_ENGINE_ORDER,
    val enabledSearchEngines: Set<ConcreteSearchEngine> = DEFAULT_ENABLED_SEARCH_ENGINES,
    val fetchPageExcerpts: Boolean = true,
    val customSearxngUrl: String = "",
    val trustedDomains: List<String> = DEFAULT_TRUSTED_DOMAINS,
    val untrustedDomains: List<String> = DEFAULT_UNTRUSTED_DOMAINS,
    // Local GGUF LLM config (Part B & v0.5.0 AI Controls)
    val localLlmConfig: LocalLlmConfig = LocalLlmConfig()
) {
    companion object {
        val DEFAULT_AUTO_ENGINE_ORDER = listOf(
            ConcreteSearchEngine.DUCKDUCKGO,
            ConcreteSearchEngine.WIKIPEDIA,
            ConcreteSearchEngine.MOJEEK,
            ConcreteSearchEngine.SEARXNG
        )
        val DEFAULT_ENABLED_SEARCH_ENGINES = setOf(
            ConcreteSearchEngine.DUCKDUCKGO,
            ConcreteSearchEngine.WIKIPEDIA,
            ConcreteSearchEngine.MOJEEK,
            ConcreteSearchEngine.SEARXNG
        )
        val DEFAULT_TRUSTED_DOMAINS = listOf(
            "wikipedia.org",
            "ar.wikipedia.org",
            "en.wikipedia.org",
            "who.int",
            "un.org",
            "reuters.com",
            "bbc.com",
            "aljazeera.net",
            "gov.eg"
        )
        val DEFAULT_UNTRUSTED_DOMAINS = listOf(
            "pinterest.com",
            "quora.com",
            "clickbait-example.invalid"
        )
    }
}

data class PermissionHealthState(
    val hasMicPermission: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val isDefaultAssistant: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false,
    val isAutoRevokeExempted: Boolean = true
) {
    val readinessScore: Int
        get() {
            var score = 0
            if (hasMicPermission) score += 30
            if (hasNotificationPermission) score += 15
            if (isDefaultAssistant) score += 30
            if (isBatteryOptimizationIgnored) score += 15
            if (isAccessibilityEnabled) score += 10
            return score
        }
}

enum class MediaAction(val labelAr: String) {
    VOLUME_UP("تعلية الصوت"),
    VOLUME_DOWN("توطية الصوت"),
    MUTE("كتم الصوت"),
    UNMUTE("إلغاء الكتم"),
    PLAY_PAUSE("تشغيل / إيقاف مؤقت"),
    NEXT_TRACK("اللي بعده"),
    PREVIOUS_TRACK("اللي قبله")
}

enum class SettingPanelTarget(val labelAr: String) {
    WIFI("الواي فاي"),
    BLUETOOTH("البلوتوث"),
    LOCATION("الموقع (GPS)"),
    SYSTEM_SETTINGS("الإعدادات العامة")
}

enum class CommMode { CALL, SMS }

enum class UiActionType(val labelAr: String) {
    SCROLL_DOWN("التمرير لتحت"),
    SCROLL_UP("التمرير لفوق"),
    GO_BACK("الرجوع للخلف"),
    GO_HOME("الشاشة الرئيسية"),
    TAP_TEXT("الضغط على عنصر")
}

enum class ActiveAppContext {
    NONE,
    YOUTUBE,
    BROWSER,
    WHATSAPP,
    SETTINGS
}

data class ConversationMemoryEntry(
    val id: Long,
    val userText: String,
    val assistantReply: String,
    val intentCategory: String,
    val activeContext: ActiveAppContext,
    val timestampMs: Long
)

data class PersonalShortcut(
    val id: String,
    val triggerPhrase: String,
    val mappedCommand: String
)

data class ConversationContextState(
    val activeAppContext: ActiveAppContext = ActiveAppContext.NONE,
    val lastSearchQuery: String? = null,
    val lastOpenedApp: String? = null,
    val rollingSummary: String = "",
    val recentEntries: List<ConversationMemoryEntry> = emptyList(),
    val shortcuts: List<PersonalShortcut> = emptyList()
)

sealed class EgyptianIntent(val categoryName: String) {
    data class OpenApp(val appQuery: String, val resolvedPackage: String? = null) :
        EgyptianIntent("OPEN_APP")

    data class CloseCurrentApp(val appHint: String? = null) :
        EgyptianIntent("CLOSE_APP")

    data class YouTubeSearchPlay(val query: String, val autoPlayHint: Boolean = true) :
        EgyptianIntent("YOUTUBE_PLAY")

    data class WebSearch(val query: String) :
        EgyptianIntent("WEB_SEARCH")

    data class KnowledgeOrChatQuery(
        val query: String,
        val explicitlyRequestsOnlineSearch: Boolean = false
    ) : EgyptianIntent("KNOWLEDGE_QUERY")

    data class MediaControl(val action: MediaAction) :
        EgyptianIntent("MEDIA_CONTROL")

    data class DeviceSettingPanel(val target: SettingPanelTarget) :
        EgyptianIntent("SETTING_PANEL")

    data class SetAlarmOrTimer(
        val hour: Int? = null,
        val minute: Int? = null,
        val timerMinutes: Int? = null,
        val label: String? = null
    ) : EgyptianIntent("ALARM_OR_TIMER")

    data class CallOrMessageIntent(
        val mode: CommMode,
        val contactQuery: String,
        val messageBody: String? = null
    ) : EgyptianIntent("COMM_INTENT")

    data object SystemStatusBriefing : EgyptianIntent("SYSTEM_STATUS")

    data object RecallConversationContext : EgyptianIntent("MEMORY_RECALL")

    data class CompoundCommands(val steps: List<EgyptianIntent>) :
        EgyptianIntent("COMPOUND")

    data class AccessibilityUiAction(
        val actionType: UiActionType,
        val targetText: String? = null
    ) : EgyptianIntent("ACCESSIBILITY_UI")

    data class SmallTalkOrPersona(val topic: String) :
        EgyptianIntent("SMALL_TALK")

    data class BlockedOrUnsafe(val reasonAr: String, val ruleId: String) :
        EgyptianIntent("END_LINE_BLOCKED")

    data class Unknown(val rawText: String) :
        EgyptianIntent("UNKNOWN")
}

sealed class GuardrailVerdict {
    data class Allowed(val intent: EgyptianIntent) : GuardrailVerdict()
    data class Denied(
        val ruleId: String,
        val reasonAr: String,
        val requiresSettingsRedirect: Boolean = false
    ) : GuardrailVerdict()
}

data class InteractionTrace(
    val id: Long = System.currentTimeMillis(),
    val rawUtterance: String,
    val normalizedPhonetics: String,
    val intentLabel: String,
    val guardrailStatus: String,
    val isBlocked: Boolean,
    val assistantReplyAr: String,
    val voiceMatchScore: Float? = null,
    val timestampFormatted: String,
    val sources: List<WebSearchSource> = emptyList(),
    val generatedByLocalLlm: Boolean = false,
    val taskTypeLabel: String? = null,
    val reasoningText: String? = null,
    val thinkingElapsedMs: Long? = null,
    val searchConfidence: SearchConfidence? = null,
    val judgedSources: List<JudgedSearchSource> = emptyList(),
    val proposedAction: ProposedAction? = null,
    val hasContinuation: Boolean = false,
    val fullTextForContinuation: String? = null,
    val suggestModelSettingsShortcut: Boolean = false
)
