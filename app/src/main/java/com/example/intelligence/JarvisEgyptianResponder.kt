package com.example.intelligence

import com.example.core.model.AssistantGender
import com.example.core.model.AssistantSettings
import com.example.core.model.CommMode
import com.example.core.model.EgyptianIntent
import com.example.core.model.MediaAction
import com.example.core.model.PersonaTone

/**
 * Generates authentic Egyptian Colloquial Arabic responses in J.A.R.V.I.S.-inspired
 * personas (100% deterministic & local), dynamically adapting assistant name and
 * Arabic grammatical gender (MALE vs FEMALE) independently of TTS voice selection.
 */
object JarvisEgyptianResponder {

    fun formatSuccessReply(
        intent: EgyptianIntent,
        tone: PersonaTone,
        detailOverride: String? = null,
        assistantName: String = "شيتا",
        gender: AssistantGender = AssistantGender.MALE
    ): String {
        if (!detailOverride.isNullOrBlank()) return detailOverride
        val isFem = gender == AssistantGender.FEMALE
        val readyWord = if (isFem) "جاهزة" else "جاهز"
        val hearingWord = if (isFem) "سامعاك" else "سامعك"
        val rememberWord = if (isFem) "فاكرة" else "فاكر"
        val runningWord = if (isFem) "شغالة" else "شغال"

        return when (intent) {
            is EgyptianIntent.OpenApp -> {
                val app = intent.appQuery
                when (tone) {
                    PersonaTone.JARVIS_FORMAL_EGYPTIAN -> "تحت أمرك يا فندم، بفتحلك «$app» حالاً."
                    PersonaTone.FRIENDLY_EGYPTIAN -> "من عيوني! يلا نفتح «$app» دلوقتي."
                    PersonaTone.CONCISE_TACTICAL -> "تم فتح «$app»."
                }
            }

            is EgyptianIntent.CloseCurrentApp -> {
                when (tone) {
                    PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                        "أندرويد بيمنع إغلاق التطبيقات التانية قسرياً لحماية النظام، فرجعتلك للشاشة الرئيسية يا فندم."
                    PersonaTone.FRIENDLY_EGYPTIAN ->
                        "أندرويد مبيسمحش أقفل تطبيق تاني غصب، فطلعتلك للشاشة الرئيسية!"
                    PersonaTone.CONCISE_TACTICAL ->
                        "تم الرجوع للشاشة الرئيسية (قيد نظام أندرويد)."
                }
            }

            is EgyptianIntent.YouTubeSearchPlay -> {
                when (tone) {
                    PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                        "أمرك يا فندم، بفتح يوتيوب وبجهزلك البحث عن «${intent.query}»."
                    PersonaTone.FRIENDLY_EGYPTIAN ->
                        "حاضر يا بطل! هافتحلك يوتيوب وأجيبلك «${intent.query}»."
                    PersonaTone.CONCISE_TACTICAL ->
                        "يوتيوب: «${intent.query}»."
                }
            }

            is EgyptianIntent.WebSearch -> {
                when (tone) {
                    PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                        "تمام يا فندم، بفتح المتصفح وأدورلك على «${intent.query}»."
                    PersonaTone.FRIENDLY_EGYPTIAN ->
                        "ثواني ويكون قدامك، بدورلك على «${intent.query}» في جوجل."
                    PersonaTone.CONCISE_TACTICAL ->
                        "بحث جوجل: «${intent.query}»."
                }
            }

            is EgyptianIntent.KnowledgeOrChatQuery -> {
                when (tone) {
                    PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                        "للأسف معنديش المعلومة دي أوفلاين حالياً يا فندم."
                    PersonaTone.FRIENDLY_EGYPTIAN ->
                        "معلش المعلومة دي مش عندي أوفلاين دلوقتي!"
                    PersonaTone.CONCISE_TACTICAL ->
                        "لا تتوفر إجابة محلية لهذا السؤال."
                }
            }

            is EgyptianIntent.MediaControl -> {
                val actionText = when (intent.action) {
                    MediaAction.VOLUME_UP -> "رفعتلك الصوت شوية"
                    MediaAction.VOLUME_DOWN -> "وطيتلك الصوت"
                    MediaAction.MUTE -> "كتمت الصوت تماماً"
                    MediaAction.UNMUTE -> "رجعتلك الصوت تاني"
                    MediaAction.PLAY_PAUSE -> "نفذت أمر التشغيل والإيقاف"
                    MediaAction.NEXT_TRACK -> "نقلتلك على اللي بعده"
                    MediaAction.PREVIOUS_TRACK -> "رجعتلك للي قبله"
                }
                when (tone) {
                    PersonaTone.JARVIS_FORMAL_EGYPTIAN -> "تم يا فندم، $actionText."
                    PersonaTone.FRIENDLY_EGYPTIAN -> "خلصانة! $actionText."
                    PersonaTone.CONCISE_TACTICAL -> actionText
                }
            }

            is EgyptianIntent.DeviceSettingPanel -> {
                val targetName = intent.target.labelAr
                when (tone) {
                    PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                        "حاضر يا فندم، فتحتلك لوحة $targetName تتحكم فيها براحتك."
                    PersonaTone.FRIENDLY_EGYPTIAN ->
                        "أهو قدامك إعدادات $targetName، ظبطها براحتك."
                    PersonaTone.CONCISE_TACTICAL -> "تم فتح $targetName."
                }
            }

            is EgyptianIntent.SetAlarmOrTimer -> {
                if (intent.timerMinutes != null) {
                    when (tone) {
                        PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                            "تم يا فندم، ظبطتلك تايمر لمدة ${intent.timerMinutes} دقيقة."
                        PersonaTone.FRIENDLY_EGYPTIAN ->
                            "ماشي يا سيدي، التايمر $runningWord على ${intent.timerMinutes} دقيقة."
                        PersonaTone.CONCISE_TACTICAL ->
                            "تايمر ${intent.timerMinutes} دقيقة."
                    }
                } else {
                    val h = intent.hour ?: 8
                    val m = intent.minute ?: 0
                    val formatted = String.format("%02d:%02d", h, m)
                    when (tone) {
                        PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                            "تحت أمرك يا فندم، ظبطت المنبه على الساعة $formatted."
                        PersonaTone.FRIENDLY_EGYPTIAN ->
                            "نوم العوافي! المنبه اتظبط على $formatted."
                        PersonaTone.CONCISE_TACTICAL -> "منبه الساعة $formatted."
                    }
                }
            }

            is EgyptianIntent.CallOrMessageIntent -> {
                if (intent.mode == CommMode.CALL) {
                    when (tone) {
                        PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                            "بجهزلك شاشة الاتصال لـ «${intent.contactQuery}» عشان تأكد المكالمة بنفسك يا فندم."
                        PersonaTone.FRIENDLY_EGYPTIAN ->
                            "فتحتلك شاشة الاتصال لـ «${intent.contactQuery}»، دوس اتصال لما تكون $readyWord."
                        PersonaTone.CONCISE_TACTICAL -> "تجهيز اتصال: ${intent.contactQuery}."
                    }
                } else {
                    when (tone) {
                        PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                            "جهزتلك شاشة الرسالة لـ «${intent.contactQuery}» للمراجعة قبل الإرسال."
                        PersonaTone.FRIENDLY_EGYPTIAN ->
                            "الرسالة $readyWord لـ «${intent.contactQuery}»، راجعها وابعت."
                        PersonaTone.CONCISE_TACTICAL -> "تجهيز رسالة: ${intent.contactQuery}."
                    }
                }
            }

            is EgyptianIntent.AccessibilityUiAction -> {
                val desc = intent.actionType.labelAr
                when (tone) {
                    PersonaTone.JARVIS_FORMAL_EGYPTIAN -> "نفذت أمر $desc على الشاشة يا فندم."
                    PersonaTone.FRIENDLY_EGYPTIAN -> "تم! عملتلك $desc."
                    PersonaTone.CONCISE_TACTICAL -> "تم: $desc."
                }
            }

            is EgyptianIntent.RecallConversationContext ->
                "$rememberWord طبعاً يا فندم، تقدر تراجع آخر حواراتنا من سجل الذاكرة المحلية."

            is EgyptianIntent.CompoundCommands ->
                "نفذتلك الأوامر المركبة خطوة بخطوة يا فندم."

            is EgyptianIntent.SmallTalkOrPersona -> handleSmallTalk(
                topic = intent.topic,
                tone = tone,
                assistantName = assistantName,
                gender = gender,
                readyWord = readyWord,
                hearingWord = hearingWord,
                runningWord = runningWord
            )

            is EgyptianIntent.SystemStatusBriefing -> "النظام $runningWord بكفاءة."

            is EgyptianIntent.BlockedOrUnsafe -> intent.reasonAr

            is EgyptianIntent.Unknown -> {
                val sorryWord = if (isFem) "آسفة" else "آسف"
                when (tone) {
                    PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                        "$sorryWord يا فندم، الأمر «${intent.rawText}» مش واضح كفاية أو برا الأوامر المتاحة. جرب تقولي مثلاً: افتح يوتيوب، وطي الصوت، أو دورلي على كذا."
                    PersonaTone.FRIENDLY_EGYPTIAN ->
                        "معلش ماجمعتش قصدك في «${intent.rawText}»! قولي مثلاً: افتح واتساب أو ظبط منبه الساعة 7."
                    PersonaTone.CONCISE_TACTICAL ->
                        "أمر غير مفهوم. متاح: فتح تطبيقات، يوتيوب، بحث، صوت، منبه، حالة الجهاز."
                }
            }
        }
    }

    fun formatWakeAck(settings: AssistantSettings): String {
        val raw = settings.wakeAckPhrase.trim()
        val isFem = settings.assistantGender == AssistantGender.FEMALE
        return when {
            raw == "أيوه، معاك" || raw.isBlank() -> {
                if (isFem) "أيوه يا فندم، سامعاك." else "أيوه، معاك يا فندم."
            }
            isFem -> raw
                .replace("سامعك", "سامعاك")
                .replace("جاهز", "جاهزة")
                .replace("مستعد", "مستعدة")
            else -> raw
        }
    }

    fun formatInternetSearchDisabledReply(
        query: String,
        tone: PersonaTone,
        gender: AssistantGender
    ): String {
        val isFem = gender == AssistantGender.FEMALE
        val sorry = if (isFem) "آسفة" else "آسف"
        val working = if (isFem) "شغالة" else "شغال"
        return when (tone) {
            PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                "$sorry يا فندم، ميزة البحث على الإنترنت مقفولة حالياً وأنا $working أوفلاين بالكامل. عشان أبحثلك عن «$query»، افتح الإعدادات وشغّل مفتاح «السماح بالبحث على الإنترنت»."
            PersonaTone.FRIENDLY_EGYPTIAN ->
                "البحث أونلاين مقفول دلوقتي عشان الخصوصية! لو حابب أدورلك على «$query»، افتح الإعدادات وفعل «السماح بالبحث على الإنترنت»."
            PersonaTone.CONCISE_TACTICAL ->
                "البحث على الإنترنت معطّل. فعّله من الإعدادات للبحث عن «$query»."
        }
    }

    fun formatGuardrailBlockReply(
        reasonAr: String,
        tone: PersonaTone,
        gender: AssistantGender = AssistantGender.MALE
    ): String {
        val sorry = if (gender == AssistantGender.FEMALE) "آسفة" else "آسف"
        return when (tone) {
            PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                "$sorry يا فندم، بس ده خط أحمر في قواعد الأمان. $reasonAr"
            PersonaTone.FRIENDLY_EGYPTIAN ->
                "لأ معلش، هنا نقف عند الخط الأحمر! $reasonAr"
            PersonaTone.CONCISE_TACTICAL ->
                "مرفوض (خط أحمر): $reasonAr"
        }
    }

    fun buildLocalLlmSystemPrompt(settings: AssistantSettings): String {
        val name = settings.assistantName.ifBlank { "شيتا" }
        val isFem = settings.assistantGender == AssistantGender.FEMALE
        val identityLine = if (isFem) {
            "اسمك «$name»، وإنتِ مساعدة شخصية ذكية بتتكلمي بصيغة المؤنث بالعامية المصرية الأصيلة (زي: سامعاك، جاهزة، تحت أمرك، قريت، فهمت)."
        } else {
            "اسمك «$name»، وإنت مساعد شخصي ذكي بتتكلم بصيغة المذكر بالعامية المصرية الأصيلة (زي: سامعك، جاهز، تحت أمرك، قريت، فهمت)."
        }
        val toneLine = when (settings.personaTone) {
            PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                "أسلوبك متزن ووقور ومحترم مستوحى من J.A.R.V.I.S. (بتقول يا فندم أو حضرتك باختصار ووضوح)."
            PersonaTone.FRIENDLY_EGYPTIAN ->
                "أسلوبك ودود وخفيف وبسيط بالعامية المصرية."
            PersonaTone.CONCISE_TACTICAL ->
                "أسلوبك عملي ومختصر جداً في جملتين بالكتير."
        }
        return "$identityLine $toneLine أجب بإيجاز ووضوح وماتخترعش تنفيذ أفعال على الموبايل من نفسك."
    }

    private fun handleSmallTalk(
        topic: String,
        tone: PersonaTone,
        assistantName: String,
        gender: AssistantGender,
        readyWord: String,
        hearingWord: String,
        runningWord: String
    ): String {
        val isFem = gender == AssistantGender.FEMALE
        val roleTitle = if (isFem) "مساعدتك الشخصية" else "مساعدك الشخصي"
        val friendTitle = if (isFem) "صاحبتك الرقمية" else "صاحبك الرقمي"
        val understandingWord = if (isFem) "بفهمك" else "بفهمك"
        val sendingWord = if (isFem) "أبعت" else "أبعت"
        val executingWord = if (isFem) "وبنفذ" else "وبنفذ"
        return when (topic) {
            "wake_ack" -> when (tone) {
                PersonaTone.JARVIS_FORMAL_EGYPTIAN -> "تحت أمرك يا فندم، $hearingWord كويس. أؤمرني؟"
                PersonaTone.FRIENDLY_EGYPTIAN -> "أيوة يا بطل، معاك! عايز نعمل إيه؟"
                PersonaTone.CONCISE_TACTICAL -> "$readyWord."
            }
            "greeting" -> when (tone) {
                PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                    "أهلاً بحضرتك يا فندم. $assistantName $readyWord وكل الأنظمة المحلية $runningWord أوفلاين وتحت أمرك."
                PersonaTone.FRIENDLY_EGYPTIAN ->
                    "أهلاً بيك يا غالي! أنا $assistantName وكله تمام ومستعد أساعدك في أي حاجة."
                PersonaTone.CONCISE_TACTICAL -> "أهلاً. الأنظمة $readyWord."
            }
            "identity" -> when (tone) {
                PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                    "أنا $assistantName، $roleTitle بالعامية المصرية. $runningWord محلياً على جهازك بدون أي API مدفوع، $executingWord أوامرك بأمان وبحدود واضحة."
                PersonaTone.FRIENDLY_EGYPTIAN ->
                    "أنا $assistantName $friendTitle! $understandingWord بالمصري و$runningWord على تليفونك من غير ما $sendingWord أسرارك بره."
                PersonaTone.CONCISE_TACTICAL ->
                    "المساعد المحلي $assistantName — عامية مصرية بدون مفاتيح API."
            }
            "thanks" -> when (tone) {
                PersonaTone.JARVIS_FORMAL_EGYPTIAN -> "العفو يا فندم، دايماً في خدمتك."
                PersonaTone.FRIENDLY_EGYPTIAN -> "العفو ولا يهمك! في أي وقت."
                PersonaTone.CONCISE_TACTICAL -> "تحت أمرك."
            }
            else -> "تحت أمرك يا فندم."
        }
    }
}
