package com.example.intelligence

import com.example.core.model.CommMode
import com.example.core.model.EgyptianIntent
import com.example.core.model.MediaAction
import com.example.core.model.PersonaTone

/**
 * Generates authentic Egyptian Colloquial Arabic responses in J.A.R.V.I.S.-inspired
 * personas (zero cloud LLM calls, 100% deterministic & local).
 */
object JarvisEgyptianResponder {

    fun formatSuccessReply(
        intent: EgyptianIntent,
        tone: PersonaTone,
        detailOverride: String? = null
    ): String {
        if (!detailOverride.isNullOrBlank()) return detailOverride

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
                            "ماشي يا سيدي، التايمر شغال على ${intent.timerMinutes} دقيقة."
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
                            "فتحتلك شاشة الاتصال لـ «${intent.contactQuery}»، دوس اتصال لما تكون جاهز."
                        PersonaTone.CONCISE_TACTICAL -> "تجهيز اتصال: ${intent.contactQuery}."
                    }
                } else {
                    when (tone) {
                        PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                            "جهزتلك شاشة الرسالة لـ «${intent.contactQuery}» للمراجعة قبل الإرسال."
                        PersonaTone.FRIENDLY_EGYPTIAN ->
                            "الرسالة جاهزة لـ «${intent.contactQuery}»، راجعها وابعت."
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
                "فاكر طبعاً يا فندم، تقدر تراجع آخر حواراتنا من سجل الذاكرة المحلية."

            is EgyptianIntent.CompoundCommands ->
                "نفذتلك الأوامر المركبة خطوة بخطوة يا فندم."

            is EgyptianIntent.SmallTalkOrPersona -> handleSmallTalk(intent.topic, tone)

            is EgyptianIntent.SystemStatusBriefing -> "النظام شغال بكفاءة."

            is EgyptianIntent.BlockedOrUnsafe -> intent.reasonAr

            is EgyptianIntent.Unknown -> {
                when (tone) {
                    PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                        "سامحني يا فندم، الأمر «${intent.rawText}» مش واضح كفاية أو برا الأوامر المتاحة. جرب تقولي مثلاً: افتح يوتيوب، وطي الصوت، أو دورلي على كذا."
                    PersonaTone.FRIENDLY_EGYPTIAN ->
                        "معلش ماجمعتش قصدك في «${intent.rawText}»! قولي مثلاً: افتح واتساب أو ظبط منبه الساعة 7."
                    PersonaTone.CONCISE_TACTICAL ->
                        "أمر غير مفهوم. متاح: فتح تطبيقات، يوتيوب، بحث، صوت، منبه، حالة الجهاز."
                }
            }
        }
    }

    fun formatGuardrailBlockReply(reasonAr: String, tone: PersonaTone): String {
        return when (tone) {
            PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                "آسف يا فندم، بس ده خط أحمر في قواعد الأمان. $reasonAr"
            PersonaTone.FRIENDLY_EGYPTIAN ->
                "لأ معلش، هنا نقف عند الخط الأحمر! $reasonAr"
            PersonaTone.CONCISE_TACTICAL ->
                "مرفوض (خط أحمر): $reasonAr"
        }
    }

    private fun handleSmallTalk(topic: String, tone: PersonaTone): String {
        return when (topic) {
            "wake_ack" -> when (tone) {
                PersonaTone.JARVIS_FORMAL_EGYPTIAN -> "تحت أمرك يا فندم، سامعك كويس. أؤمرني؟"
                PersonaTone.FRIENDLY_EGYPTIAN -> "أيوة يا بطل، معاك! عايز نعمل إيه؟"
                PersonaTone.CONCISE_TACTICAL -> "جاهز."
            }
            "greeting" -> when (tone) {
                PersonaTone.JARVIS_FORMAL_EGYPTIAN -> "أهلاً بحضرتك يا فندم. كل الأنظمة المحلية شغالة أوفلاين وتحت أمرك."
                PersonaTone.FRIENDLY_EGYPTIAN -> "أهلاً بيك يا غالي! كله تمام ومستعد أساعدك في أي حاجة."
                PersonaTone.CONCISE_TACTICAL -> "أهلاً. الأنظمة جاهزة."
            }
            "identity" -> when (tone) {
                PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                    "أنا شيتا، مساعدك الشخصي بالعامية المصرية. شغال بالكامل على جهازك بدون أي API خارجي، وبنفذ أوامرك بأمان وبحدود واضحة."
                PersonaTone.FRIENDLY_EGYPTIAN ->
                    "أنا شيتا صاحبك الرقمي! بفهمك بالمصري وشغال أوفلاين على تليفونك من غير ما أبعت حاجة بره."
                PersonaTone.CONCISE_TACTICAL ->
                    "مساعد شيتا المحلي — صفر API خارجي، عامية مصرية."
            }
            "thanks" -> when (tone) {
                PersonaTone.JARVIS_FORMAL_EGYPTIAN -> "العفو يا فندم، دايماً في خدمتك."
                PersonaTone.FRIENDLY_EGYPTIAN -> "حبيبي ولا يهمك! في أي وقت."
                PersonaTone.CONCISE_TACTICAL -> "تحت أمرك."
            }
            else -> "تحت أمرك يا فندم."
        }
    }
}
