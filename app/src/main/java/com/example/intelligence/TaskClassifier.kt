package com.example.intelligence

import com.example.core.model.AiTaskType
import com.example.voice.EgyptianPhoneticNormalizer

data class TaskClassificationResult(
    val taskType: AiTaskType,
    val isAmbiguous: Boolean,
    val isNonArabicUserInput: Boolean,
    val needsFreshInternetInfo: Boolean
)

/**
 * Lightweight local task classifier: deterministic rules run first in microseconds;
 * ambiguous queries may optionally consult the local model for tie-breaking.
 */
object TaskClassifier {

    private val ARITHMETIC_REGEX = Regex("\\d+\\s*[+\\-*/×÷^%]\\s*\\d+")

    fun classifyRulesOnly(
        rawInput: String,
        hasAttachedText: Boolean = false
    ): TaskClassificationResult {
        val norm = EgyptianPhoneticNormalizer.normalizeForNlu(rawInput)
        val lower = rawInput.trim().lowercase()
        val nonArabic = isPredominantlyNonArabic(rawInput)
        val freshNeeded = needsFreshOnlineInfo(norm, lower)

        val (type, ambiguous) = when {
            // 1. Explaining text provided by user
            (hasAttachedText && containsAny(norm, lower, "اشرح", "فسر", "معني", "وضح", "يعني ايه", "explain", "meaning")) ||
                containsAny(norm, lower, "اشرحلي النص", "اشرحلي الكلام", "فسرلي الكلام", "وضحلي النص", "explain this text") ->
                AiTaskType.EXPLAINING_USER_TEXT to false

            // 2. Summarization
            containsAny(norm, lower, "لخص", "ملخص", "اختصر", "الخلاصه", "tl;dr", "tldr", "summarize", "summary") ->
                AiTaskType.SUMMARIZATION to false

            // 3. Translation
            containsAny(norm, lower, "ترجم", "ترجمه", "بالانجليزي", "بالعربي", "للعربي", "للانجليزي", "translate", "translation") ->
                AiTaskType.TRANSLATION to false

            // 4. Math
            ARITHMETIC_REGEX.containsMatchIn(rawInput) ||
                containsAny(norm, lower, "احسب", "ناتج", "معادله", "جذر", "قسمه", "ضرب", "نسبه مئويه", "متوسط حسابي", "calculate", "solve equation", "percentage") ->
                AiTaskType.MATH to false

            // 5. Code help
            rawInput.contains("```") ||
                containsAny(norm, lower, "كود", "برمجه", "برمجة", "بايثون", "كوتلن", "جافا", "سكربت", "داله", "فانكشن", "debug", "compile", "kotlin", "python", "javascript", "sql", "regex", "stack trace") ->
                AiTaskType.CODE_HELP to false

            // 6. Writing or rewriting
            containsAny(norm, lower, "اكتبلي", "اكتب ", "صيغ", "اعد كتابه", "اعادة صياغه", "حسن الصياغه", "رساله اعتذار", "ايميل", "بوست", "سيره ذاتيه", "rewrite", "draft an email", "write a ") ->
                AiTaskType.WRITING_OR_REWRITING to false

            // 7. Decision help
            containsAny(norm, lower, "محتار", "اعمل ايه", "اختار ايه", "تنصحني بايه", "الافضل ليا", "اخد القرار", "قرار ", "should i", "help me decide", "which should i choose") ->
                AiTaskType.DECISION_HELP to false

            // 8. Analysis or comparison
            containsAny(norm, lower, "قارن", "مقارنه", "الفرق بين", "مميزات وعيوب", "عيوب ومميزات", "حلل", "تحليل", "compare", "difference between", "pros and cons", "analyze") ->
                AiTaskType.ANALYSIS_OR_COMPARISON to false

            // 9. Planning
            containsAny(norm, lower, "خطه", "جدول", "رتبلي", "خطوات", "خريطه طريق", "نظملي", "اسبوع", "plan ", "schedule", "roadmap", "itinerary") ->
                AiTaskType.PLANNING to false

            // 10. Brainstorming
            containsAny(norm, lower, "افكار", "فكره", "اقترح", "فكر معايا", "عصف ذهني", "مقترحات", "brainstorm", "give me ideas", "suggest ideas") ->
                AiTaskType.BRAINSTORMING to false

            // 11. Step-by-step reasoning
            containsAny(norm, lower, "خطوه بخطوه", "برهن", "اثبت", "استنتج", "ليه بيحصل", "فسر سبب", "منطقيا", "step by step", "why does", "reason about") ->
                AiTaskType.STEP_BY_STEP_REASONING to false

            // 12. Explaining user text when attached with generic prompt
            hasAttachedText ->
                AiTaskType.EXPLAINING_USER_TEXT to false

            // 13. Question answering
            containsAny(
                norm,
                lower,
                "مين ", "ايه هو", "ايه هي", "ما هو", "ما هي", "يعني ايه",
                "اشرحلي", "فسرلي", "وضحلي", "عرفني", "ايه معني", "معني ",
                "امتي ", "متي ", "فين ", "ازاي ", "ليه ", "كام ",
                "who is", "what is", "when ", "where ", "how ", "explain "
            ) ||
                rawInput.contains("؟") || rawInput.contains("?") ->
                AiTaskType.QUESTION_ANSWERING to false

            // 14. Casual conversation (greetings/chat) or ambiguous short prompt
            containsAny(norm, lower, "ازيك", "عامل ايه", "صباح الخير", "مساء الخير", "هلا", "اهلا", "احكيلي", "دردش", "hello", "hi ", "how are you") ->
                AiTaskType.CASUAL_CONVERSATION to false

            else -> AiTaskType.CASUAL_CONVERSATION to (rawInput.trim().length > 18)
        }

        return TaskClassificationResult(
            taskType = type,
            isAmbiguous = ambiguous,
            isNonArabicUserInput = nonArabic,
            needsFreshInternetInfo = freshNeeded
        )
    }

    suspend fun classifyWithOptionalModel(
        rawInput: String,
        hasAttachedText: Boolean = false,
        modelClassifier: (suspend (String) -> String?)? = null
    ): TaskClassificationResult {
        val ruleResult = classifyRulesOnly(rawInput, hasAttachedText)
        if (!ruleResult.isAmbiguous || modelClassifier == null) {
            return ruleResult
        }

        val prompt = """
            Classify the following user request into exactly ONE category name from this list:
            casual_conversation, question_answering, step_by_step_reasoning, analysis_or_comparison,
            summarization, translation, writing_or_rewriting, brainstorming, planning, math, code_help,
            decision_help, explaining_user_text.
            Reply with ONLY the category name.
            Request: ${rawInput.take(220)}
        """.trimIndent()

        val rawLabel = runCatching { modelClassifier(prompt) }.getOrNull()?.trim()?.lowercase().orEmpty()
        val mapped = mapModelCategory(rawLabel) ?: ruleResult.taskType
        return ruleResult.copy(taskType = mapped, isAmbiguous = false)
    }

    fun mapModelCategory(label: String): AiTaskType? {
        val norm = label.lowercase().replace('-', '_').trim()
        return when {
            norm.contains("explain") -> AiTaskType.EXPLAINING_USER_TEXT
            norm.contains("summar") -> AiTaskType.SUMMARIZATION
            norm.contains("translat") -> AiTaskType.TRANSLATION
            norm.contains("math") -> AiTaskType.MATH
            norm.contains("code") -> AiTaskType.CODE_HELP
            norm.contains("decision") -> AiTaskType.DECISION_HELP
            norm.contains("analy") || norm.contains("compar") -> AiTaskType.ANALYSIS_OR_COMPARISON
            norm.contains("plan") -> AiTaskType.PLANNING
            norm.contains("brainstorm") -> AiTaskType.BRAINSTORMING
            norm.contains("writ") -> AiTaskType.WRITING_OR_REWRITING
            norm.contains("reason") || norm.contains("step") -> AiTaskType.STEP_BY_STEP_REASONING
            norm.contains("question") -> AiTaskType.QUESTION_ANSWERING
            norm.contains("casual") -> AiTaskType.CASUAL_CONVERSATION
            else -> null
        }
    }

    fun isPredominantlyNonArabic(text: String): Boolean {
        val arabicCount = text.count { it in '\u0600'..'\u06FF' }
        val latinCount = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return latinCount >= 4 && arabicCount == 0
    }

    fun needsFreshOnlineInfo(normArabic: String, lowerRaw: String): Boolean {
        return containsAny(
            normArabic,
            lowerRaw,
            "النهارده", "اليوم", "دلوقتي", "حاليا", "اخبار", "خبر", "سعر", "اسعار",
            "الطقس", "الجو", "درجه الحراره", "نتيجه ماتش", "مباراه", "بكام",
            "2025", "2026", "2027", "احدث", "جديد",
            "today", "latest", "current", "news", "price", "weather", "score", "now", "recent"
        )
    }

    fun isTimeSensitiveQuery(query: String): Boolean {
        val norm = EgyptianPhoneticNormalizer.normalizeForNlu(query)
        return needsFreshOnlineInfo(norm, query.lowercase())
    }

    private fun containsAny(normArabic: String, lowerRaw: String, vararg terms: String): Boolean {
        return terms.any { term ->
            val cleanTerm = EgyptianPhoneticNormalizer.normalizeForNlu(term)
            (cleanTerm.isNotEmpty() && normArabic.contains(cleanTerm)) ||
                lowerRaw.contains(term.lowercase())
        }
    }
}
