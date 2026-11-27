package com.example.intelligence

import com.example.core.model.AiLanguagePreference
import com.example.core.model.AiMode
import com.example.core.model.AiTaskType
import com.example.core.model.AssistantGender
import com.example.core.model.AssistantSettings
import com.example.core.model.ConversationContextState
import com.example.core.model.ConversationTurnMessage
import com.example.core.model.EgyptianIntent
import com.example.core.model.MediaAction
import com.example.core.model.PersonaTone
import com.example.core.model.ProposedAction
import com.example.core.model.ResponseStyle
import com.example.core.model.SettingPanelTarget
import com.example.data.SettingsRepository

/**
 * Builds task-specific prompts across all 13 AiTaskType categories in the selected
 * persona, grammatical gender, AI thinking mode, and Egyptian Arabic (or user language).
 * Also converts optional model-suggested [[PROPOSE_ACTION:...]] tags into typed ProposedAction
 * objects gated by CommandGuardrailValidator & explicit confirmation.
 */
object PromptTemplateEngine {

    fun buildSystemPrompt(
        settings: AssistantSettings,
        taskType: AiTaskType,
        aiMode: AiMode,
        nonArabicInput: Boolean,
        rollingSummary: String = ""
    ): String {
        val basePersona = JarvisEgyptianResponder.buildLocalLlmSystemPrompt(settings)
        val cfg = settings.localLlmConfig
        val languageInstruction = when (cfg.aiLanguagePreference) {
            AiLanguagePreference.EGYPTIAN_ARABIC ->
                "رد دائماً بالعامية المصرية الطبيعية السهلة."
            AiLanguagePreference.ENGLISH ->
                "Always answer clearly and concisely in natural English unless the user explicitly asks for translation."
            AiLanguagePreference.MATCH_USER -> if (nonArabicInput) {
                "المستخدم كتب بلغة غير العربية؛ رد بنفس لغة المستخدم بوضوح ولباقة."
            } else {
                "الرد بالعامية المصرية الطبيعية السهلة."
            }
        }

        val styleInstruction = when (cfg.responseStyle) {
            ResponseStyle.SHORT ->
                "أسلوب الطول: رد مختصر جداً ومباشر بدون إطالة."
            ResponseStyle.BALANCED ->
                "أسلوب الطول: شرح متوازن وواضح مع أهم النقاط فقط."
            ResponseStyle.DETAILED ->
                "أسلوب الطول: شرح مفصّل ومنظم يغطي التفاصيل والأمثلة المهمة."
        }

        val modeInstruction = when (aiMode) {
            AiMode.FAST ->
                "وضع السرعة: جاوب بشكل مباشر ومختصر جداً (جملة أو اتنين) بدون مقدمات."
            AiMode.BALANCED ->
                "الوضع المتوازن: جاوب بوضوح وإيجاز عملي مع نقاط سريعة لو لازم."
            AiMode.DEEP_THINKING ->
                "وضع التفكير العميق: حلل المسألة خطوة بخطوة بعمق. لو حابب تفكر داخلياً اكتب خطوات التفكير جوه <think>...</think> وبعدها اكتب الإجابة النهائية الواضحة للمستخدم."
        }

        val taskDirective = when (taskType) {
            AiTaskType.CASUAL_CONVERSATION ->
                "المهمة: دردشة ودودة وطبيعية متناسقة مع الشخصية."
            AiTaskType.QUESTION_ANSWERING ->
                "المهمة: إجابة مباشرة ودقيقة. لو المعلومة مش مؤكدة أوفلاين قول بصراحة ماتخمنش."
            AiTaskType.STEP_BY_STEP_REASONING ->
                "المهمة: تفكير منطقي مرتب خطوة بخطوة وصولاً لنتيجة واضحة."
            AiTaskType.ANALYSIS_OR_COMPARISON ->
                "المهمة: تحليل موضوعي أو مقارنة منظمة (نقاط قوة وضعف / فروق أساسية / خلاصة)."
            AiTaskType.SUMMARIZATION ->
                "المهمة: تلخيص النص المرفق في أهم النقاط بدقة وبدون اختراع معلومات مش موجودة فيه."
            AiTaskType.TRANSLATION ->
                "المهمة: ترجمة دقيقة وطبيعية تحافظ على المعنى والسياق."
            AiTaskType.WRITING_OR_REWRITING ->
                "المهمة: صياغة أو إعادة كتابة النص بأسلوب احترافي أو مناسب للسياق المطلوب."
            AiTaskType.BRAINSTORMING ->
                "المهمة: توليد أفكار متنوعة ومبتكرة وقابلة للتنفيذ في نقاط مرقمة."
            AiTaskType.PLANNING ->
                "المهمة: خطة عملية مرتبة زمنياً أو خطوات واضحة قابلة للتطبيق."
            AiTaskType.MATH ->
                "المهمة: حساب رياضي دقيق خطوة بخطوة مع التحقق من الناتج النهائي."
            AiTaskType.CODE_HELP ->
                "المهمة: مساعدة برمجية عملية، كود نظيف ومختصر مع شرح السبب والحل."
            AiTaskType.DECISION_HELP ->
                "المهمة: مساعدة في اتخاذ القرار بذكر البدائل والمميزات والمخاطر وتوصية متزنة."
            AiTaskType.EXPLAINING_USER_TEXT ->
                "المهمة: تبسيط وشرح النص اللي قدمه المستخدم بمصطلحات واضحة وأمثلة قريبة."
        }

        val actionSafetyRule =
            "ممنوع تنفيذ أو ادعاء تنفيذ أي فعل على الجهاز. لو المستخدم محتاج إجراء فعلي على الموبايل، تقدر تقترح فقط في آخر سطر: [[PROPOSE_ACTION:OPEN_APP|اسم_التطبيق]] أو [[PROPOSE_ACTION:WEB_SEARCH|استعلام]] أو [[PROPOSE_ACTION:SETTING_PANEL|wifi/bluetooth/location]] عشان المستخدم يأكده بنفسه."

        val customPrompt = SettingsRepository.sanitizeSystemPrompt(cfg.customSystemPrompt)
        val customBlock = if (customPrompt.isNotBlank()) {
            "\nتفضيلات إضافية من المستخدم (لا تتجاوز قواعد الأمان):\n$customPrompt"
        } else ""

        val memoryBlock = if (rollingSummary.isNotBlank()) {
            "\nملخص السياق السابق:\n$rollingSummary"
        } else ""

        return "$basePersona\n$languageInstruction\n$styleInstruction\n$modeInstruction\n$taskDirective\n$actionSafetyRule$customBlock$memoryBlock"
    }

    fun buildUserPrompt(
        rawInput: String,
        attachedText: String? = null,
        taskType: AiTaskType
    ): String {
        val cleanInput = rawInput.trim()
        val cleanAttached = attachedText?.trim().orEmpty()
        return if (cleanAttached.isNotEmpty() && !cleanInput.contains(cleanAttached)) {
            """
            طلب المستخدم (${taskType.labelAr}): $cleanInput
            
            النص المرفق:
            <<<USER_PROVIDED_TEXT>>>
            $cleanAttached
            <<<END_USER_PROVIDED_TEXT>>>
            """.trimIndent()
        } else {
            cleanInput
        }
    }

    fun buildRollingMessages(
        memoryState: ConversationContextState,
        currentUserPrompt: String,
        maxHistoryTurns: Int = 4,
        maxCharsBudget: Int = 1800
    ): Pair<String, List<ConversationTurnMessage>> {
        val chronological = memoryState.recentEntries.asReversed()
        val recentSlice = chronological.takeLast(maxHistoryTurns)
        val dropped = if (chronological.size > recentSlice.size) {
            chronological.dropLast(recentSlice.size)
        } else emptyList()

        val summaryBuilder = StringBuilder()
        if (memoryState.rollingSummary.isNotBlank()) {
            summaryBuilder.append(memoryState.rollingSummary.trim())
        }
        if (dropped.isNotEmpty()) {
            val newlySummarized = dropped.takeLast(4).joinToString(" | ") {
                "س: ${it.userText.take(60)} -> ج: ${it.assistantReply.take(70)}"
            }
            if (summaryBuilder.isNotEmpty()) summaryBuilder.append("\n")
            summaryBuilder.append(newlySummarized)
        }
        val compactSummary = summaryBuilder.toString().takeLast(420).trim()

        val turns = mutableListOf<ConversationTurnMessage>()
        var usedChars = currentUserPrompt.length + compactSummary.length
        for (entry in recentSlice) {
            val u = entry.userText.trim()
            val a = entry.assistantReply.trim()
            if (usedChars + u.length + a.length > maxCharsBudget) break
            turns.add(ConversationTurnMessage("user", u))
            turns.add(ConversationTurnMessage("assistant", a))
            usedChars += u.length + a.length
        }
        turns.add(ConversationTurnMessage("user", currentUserPrompt))
        return compactSummary to turns
    }

    fun parseProposedAction(rawOutput: String): ProposedAction? {
        val tags = ReasoningOutputParser.extractProposedActionRawTags(rawOutput)
        val firstTag = tags.firstOrNull() ?: return null
        val parts = firstTag.split('|', limit = 2).map { it.trim() }
        val kind = parts.getOrNull(0)?.uppercase().orEmpty()
        val arg = parts.getOrNull(1).orEmpty()

        return when (kind) {
            "OPEN_APP" -> if (arg.isNotBlank()) {
                ProposedAction(
                    descriptionAr = "اقتراح فتح تطبيق «$arg» (يتطلب تأكيدك)",
                    intent = EgyptianIntent.OpenApp(arg),
                    rawTag = firstTag
                )
            } else null

            "WEB_SEARCH" -> if (arg.isNotBlank()) {
                ProposedAction(
                    descriptionAr = "اقتراح البحث في المتصفح عن «$arg» (يتطلب تأكيدك)",
                    intent = EgyptianIntent.WebSearch(arg),
                    rawTag = firstTag
                )
            } else null

            "SETTING_PANEL" -> {
                val target = when (arg.lowercase()) {
                    "wifi", "واي فاي" -> SettingPanelTarget.WIFI
                    "bluetooth", "بلوتوث" -> SettingPanelTarget.BLUETOOTH
                    "location", "gps", "الموقع" -> SettingPanelTarget.LOCATION
                    else -> SettingPanelTarget.SYSTEM_SETTINGS
                }
                ProposedAction(
                    descriptionAr = "اقتراح فتح إعدادات ${target.labelAr} (يتطلب تأكيدك)",
                    intent = EgyptianIntent.DeviceSettingPanel(target),
                    rawTag = firstTag
                )
            }

            "VOLUME_DOWN" -> ProposedAction(
                descriptionAr = "اقتراح توطية الصوت (يتطلب تأكيدك)",
                intent = EgyptianIntent.MediaControl(MediaAction.VOLUME_DOWN),
                rawTag = firstTag
            )

            "VOLUME_UP" -> ProposedAction(
                descriptionAr = "اقتراح تعلية الصوت (يتطلب تأكيدك)",
                intent = EgyptianIntent.MediaControl(MediaAction.VOLUME_UP),
                rawTag = firstTag
            )

            else -> null
        }
    }

    fun formatNoModelAvailableReply(
        settings: AssistantSettings,
        taskType: AiTaskType
    ): String {
        val isFem = settings.assistantGender == AssistantGender.FEMALE
        val needWord = if (isFem) "محتاجة" else "محتاج"
        return when (settings.personaTone) {
            PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                "الأمر ده (${taskType.labelAr}) $needWord نموذج ذكاء محلي (GGUF) محمّل على الجهاز عشان أقدر أفكر وأرد عليك أوفلاين. تقدر تختار وتحمل ملف .gguf من إعدادات الذكاء المحلي."
            PersonaTone.FRIENDLY_EGYPTIAN ->
                "عشان أساعدك في (${taskType.labelAr})، أنا $needWord تحملّي نموذج .gguf محلي من الإعدادات الأول!"
            PersonaTone.CONCISE_TACTICAL ->
                "مطلوب نموذج GGUF محلي لتشغيل مهمة (${taskType.labelAr}). افتح إعدادات النموذج المحلي."
        }
    }
}
