package com.example.core.model

enum class AiMode(
    val labelAr: String,
    val labelEn: String,
    val subtitleAr: String
) {
    FAST(
        labelAr = "سريع",
        labelEn = "Fast",
        subtitleAr = "رد مباشر ومختصر بأقل وقت"
    ),
    BALANCED(
        labelAr = "متوازن",
        labelEn = "Balanced",
        subtitleAr = "توازن بين السرعة ووضوح الشرح"
    ),
    DEEP_THINKING(
        labelAr = "تفكير عميق",
        labelEn = "Deep Thinking",
        subtitleAr = "تحليل خطوة بخطوة قبل الخلاصة"
    )
}

enum class PerformanceProfile(
    val labelAr: String,
    val labelEn: String,
    val threadsCap: Int,
    val contextCap: Int,
    val searchEvalCap: Int
) {
    BATTERY_SAVER(
        labelAr = "توفير البطارية",
        labelEn = "Battery Saver",
        threadsCap = 2,
        contextCap = 768,
        searchEvalCap = 3
    ),
    BALANCED(
        labelAr = "متوازن",
        labelEn = "Balanced",
        threadsCap = 4,
        contextCap = 1280,
        searchEvalCap = 5
    ),
    PERFORMANCE(
        labelAr = "أداء عالي",
        labelEn = "Performance",
        threadsCap = 6,
        contextCap = 2048,
        searchEvalCap = 7
    )
}

enum class AiTaskType(
    val labelAr: String,
    val labelEn: String
) {
    CASUAL_CONVERSATION("دردشة عامة", "Casual Conversation"),
    QUESTION_ANSWERING("سؤال وجواب", "Question Answering"),
    STEP_BY_STEP_REASONING("تفكير منطقي خطوة بخطوة", "Step-by-Step Reasoning"),
    ANALYSIS_OR_COMPARISON("تحليل ومقارنة", "Analysis / Comparison"),
    SUMMARIZATION("تلخيص نص", "Summarization"),
    TRANSLATION("ترجمة", "Translation"),
    WRITING_OR_REWRITING("كتابة وإعادة صياغة", "Writing / Rewriting"),
    BRAINSTORMING("عصف ذهني وأفكار", "Brainstorming"),
    PLANNING("تخطيط وترتيب خطوات", "Planning"),
    MATH("حساب ورياضيات", "Math"),
    CODE_HELP("مساعدة برمجية", "Code Help"),
    DECISION_HELP("مساعدة في قرار", "Decision Help"),
    EXPLAINING_USER_TEXT("شرح نص مرفق", "Explaining User Text")
}

enum class ChatTemplateFamily(
    val displayName: String,
    val isKnown: Boolean
) {
    CHATML("ChatML", true),
    LLAMA3("Llama 3", true),
    GEMMA("Gemma", true),
    MISTRAL("Mistral", true),
    QWEN("Qwen (ChatML)", true),
    PHI("Phi", true),
    GENERIC_UNKNOWN("قالب عام غير معروف", false)
}

data class ReasoningSplitResult(
    val finalAnswer: String,
    val reasoningText: String = "",
    val hasOpenThinkTag: Boolean = false,
    val wasTruncatedByTag: Boolean = false
)

enum class SearchConfidence(val labelAr: String, val labelEn: String) {
    HIGH("ثقة عالية", "High"),
    MEDIUM("ثقة متوسطة", "Medium"),
    LOW("ثقة منخفضة", "Low")
}

enum class SearchVerdict(val wireValue: String, val labelAr: String) {
    SUPPORTS("supports", "يدعم"),
    CONTRADICTS("contradicts", "يتعارض"),
    DOES_NOT_ANSWER("does_not_answer", "لا يجيب")
}

data class JudgedSearchSource(
    val index: Int,
    val source: WebSearchSource,
    val domain: String,
    val publishedDate: String? = null,
    val relevanceScore: Int = 5,
    val reliabilityScore: Int = 5,
    val freshnessScore: Int = 5,
    val verdict: SearchVerdict = SearchVerdict.DOES_NOT_ANSWER,
    val keyClaim: String = "",
    val accepted: Boolean = true,
    val filterReason: String? = null
)

data class CrossCheckReport(
    val agreements: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val acceptedCount: Int = 0,
    val rejectedCount: Int = 0
)

data class VerifiedSearchAnswer(
    val answerText: String,
    val confidence: SearchConfidence,
    val judgedSources: List<JudgedSearchSource> = emptyList(),
    val crossCheck: CrossCheckReport = CrossCheckReport(),
    val judgedByAi: Boolean = false,
    val errorAr: String? = null
)

data class ProposedAction(
    val descriptionAr: String,
    val intent: EgyptianIntent,
    val rawTag: String
)

data class ConversationTurnMessage(
    val role: String, // "user" or "assistant"
    val content: String
)

data class ModelBenchmarkResult(
    val tokensGenerated: Int,
    val elapsedMs: Long,
    val tokensPerSec: Float,
    val sampleOutput: String,
    val success: Boolean,
    val errorAr: String? = null
)

data class DeviceHealthSnapshot(
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val thermalStatusLabelAr: String = "طبيعية",
    val isThermalThrottled: Boolean = false,
    val isBatteryLow: Boolean = false
)
