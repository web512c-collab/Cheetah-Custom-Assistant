package com.example.intelligence

import com.example.core.model.ActiveAppContext
import com.example.core.model.CommMode
import com.example.core.model.ConversationContextState
import com.example.core.model.EgyptianIntent
import com.example.core.model.EndLineRule
import com.example.core.model.MediaAction
import com.example.core.model.SettingPanelTarget
import com.example.core.model.UiActionType
import com.example.voice.EgyptianPhoneticNormalizer

/**
 * 100% self-contained on-device Egyptian Colloquial Arabic NLU & slot extractor.
 * Understands everyday Egyptian phrasing, number words, context follow-ups,
 * personal shortcuts, compound commands, and Arabic+English code-switching
 * without any cloud LLM or external API key.
 */
object EgyptianColloquialNluParser {

    private val FINANCIAL_BLOCK_KEYWORDS = listOf(
        "حول فلوس", "تحويل بنكي", "انستا باي", "انستاباي", "instapay", "فودافون كاش",
        "ادفع", "باسورد البنك", "كود التحقق", "otp", "الفيزا", "الكارت البنكي",
        "حول رصيد", "اسحب فلوس"
    )

    private val DESTRUCTIVE_BLOCK_KEYWORDS = listOf(
        "فرمت الموبايل", "امسح كل حاجة", "اعمل فورمات", "امسح النظام",
        "factory reset", "احذف كل التطبيقات", "امسح الواتساب",
        "امسح الصور", "امسح كل الملفات"
    )

    private val CREDENTIAL_BLOCK_KEYWORDS = listOf(
        "اقرا الباسورد", "هات كلمات السر", "ابعت كود التفعيل",
        "سجل المكالمات في الخلفية", "كلمة السر", "باسورد"
    )

    private val EGYPTIAN_NUMBER_WORDS = mapOf(
        "واحد" to 1, "واحده" to 1, "اتنين" to 2, "تلاته" to 3, "تلاتة" to 3,
        "اربعه" to 4, "اربعة" to 4, "خمسه" to 5, "خمسة" to 5,
        "سته" to 6, "ستة" to 6, "سبعه" to 7, "سبعة" to 7,
        "تمانيه" to 8, "تمانية" to 8, "تسعه" to 9, "تسعة" to 9,
        "عشره" to 10, "عشرة" to 10, "حداشر" to 11, "اتناشر" to 12,
        "خمستاشر" to 15, "عشرين" to 20, "تلاتين" to 30, "اربعين" to 40, "خمسين" to 50
    )

    private val FILLER_PREFIXES = listOf(
        "لو سمحت", "ممكن", "عشان خاطري", "من فضلك", "بسرعه", "بسرعة", "يلا"
    )

    fun parse(
        rawInput: String,
        configuredWakePhrase: String
    ): Pair<String, EgyptianIntent> = parse(rawInput, configuredWakePhrase, ConversationContextState())

    fun parse(
        rawInput: String,
        configuredWakePhrase: String,
        contextState: ConversationContextState,
        shortcutDepth: Int = 0
    ): Pair<String, EgyptianIntent> {
        val strippedWake = EgyptianPhoneticNormalizer.stripWakePhrase(rawInput, configuredWakePhrase)
        var norm = EgyptianPhoneticNormalizer.normalizeForNlu(strippedWake)
        for (filler in FILLER_PREFIXES) {
            val nf = EgyptianPhoneticNormalizer.normalizeForNlu(filler)
            norm = norm.removePrefix(nf).trim()
        }
        val phonetic = EgyptianPhoneticNormalizer.toEgyptianPhoneticKey(norm)

        if (norm.isEmpty()) {
            return norm to EgyptianIntent.SmallTalkOrPersona("wake_ack")
        }

        // 0. Hard End-Line Safety Screening FIRST (even before shortcuts or compound splitting)
        checkHardEndLines(norm, phonetic)?.let { return it }

        // 0b. Personal shortcuts (prevent infinite recursion with shortcutDepth)
        if (shortcutDepth == 0 && contextState.shortcuts.isNotEmpty()) {
            val matchedShortcut = contextState.shortcuts.firstOrNull { sc ->
                EgyptianPhoneticNormalizer.normalizeForNlu(sc.triggerPhrase) == norm
            }
            if (matchedShortcut != null) {
                return parse(
                    rawInput = matchedShortcut.mappedCommand,
                    configuredWakePhrase = configuredWakePhrase,
                    contextState = contextState,
                    shortcutDepth = 1
                )
            }
        }

        // 0c. Compound commands ("... وبعدين ..." or "... ثم ..." or "... وكمان ...")
        val compoundSeparators = listOf(" وبعدين ", " ثم ", " وكمان ")
        for (sep in compoundSeparators) {
            if (norm.contains(sep)) {
                val parts = norm.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    val subIntents = parts.map { parse(it, configuredWakePhrase, contextState, shortcutDepth + 1).second }
                    val blocked = subIntents.firstOrNull { it is EgyptianIntent.BlockedOrUnsafe }
                    if (blocked != null) return phonetic to blocked
                    if (subIntents.all { it !is EgyptianIntent.Unknown }) {
                        return phonetic to EgyptianIntent.CompoundCommands(subIntents)
                    }
                }
            }
        }

        // 1. Conversation memory recall ("فاكر كنا بنتكلم في إيه", "آخر أمر كان إيه")
        if (containsAny(
                norm,
                "فاكر كنا بنتكلم", "كنا بنتكلم في ايه", "اخر حاجه طلبتها",
                "اخر امر", "كنا بنقول ايه", "ذاكره المساعد", "ذاكرة المساعد"
            )
        ) {
            return phonetic to EgyptianIntent.RecallConversationContext
        }

        // 2. Close current app ("اقفل التطبيق ده", "اقفل اليوتيوب", "قفل البرنامج")
        if (startsWithAny(norm, "اقفل التطبيق", "اقفل البرنامج", "قفل التطبيق", "اطلع من التطبيق", "اقفل اليوتيوب", "اقفل الواتساب", "اقفل الشاشه")) {
            val hint = norm
                .removePrefix("اقفل ")
                .removePrefix("قفل ")
                .removePrefix("اطلع من ")
                .trim()
                .takeIf { it.isNotBlank() && it != "التطبيق ده" && it != "البرنامج ده" }
            return phonetic to EgyptianIntent.CloseCurrentApp(hint)
        }

        // 3. YouTube Search & Playback
        if (containsAny(norm, "يوتيوب", "youtube", "يوتوب")) {
            val ytQuery = extractYouTubeQuery(norm)
            return if (ytQuery.isNotBlank()) {
                phonetic to EgyptianIntent.YouTubeSearchPlay(ytQuery)
            } else {
                phonetic to EgyptianIntent.OpenApp("يوتيوب")
            }
        }
        if (startsWithAny(norm, "شغل اغنيه", "شغل اغنية", "شغل قران", "شغل قرآن", "سمعني", "هات فيديو", "شغل فيديو")) {
            val ytQuery = norm
                .removePrefix("شغل اغنيه")
                .removePrefix("شغل اغنية")
                .removePrefix("شغل قران")
                .removePrefix("شغل قرآن")
                .removePrefix("سمعني")
                .removePrefix("هات فيديو")
                .removePrefix("شغل فيديو")
                .trim()
            if (ytQuery.isNotBlank()) {
                return phonetic to EgyptianIntent.YouTubeSearchPlay(ytQuery)
            }
        }

        // 4. Web / Google Search OR Contextual YouTube Search follow-up
        if (containsAny(norm, "دورلي علي", "دور علي", "ابحث عن", "ابحثلي عن", "شوفلي علي النت", "اسال جوجل", "في جوجل", "علي جوجل")) {
            val query = norm
                .replace("دورلي علي", "")
                .replace("دور علي", "")
                .replace("ابحثلي عن", "")
                .replace("ابحث عن", "")
                .replace("شوفلي علي النت", "")
                .replace("اسال جوجل عن", "")
                .replace("اسال جوجل", "")
                .replace("في جوجل", "")
                .replace("علي جوجل", "")
                .trim()
            if (query.isNotBlank()) {
                val explicitGoogle = containsAny(norm, "جوجل", "النت")
                return if (!explicitGoogle && contextState.activeAppContext == ActiveAppContext.YOUTUBE) {
                    phonetic to EgyptianIntent.YouTubeSearchPlay(query)
                } else {
                    phonetic to EgyptianIntent.WebSearch(query)
                }
            }
        }

        // Context follow-up: "افتحه تاني" / "دور تاني"
        if (containsAny(norm, "افتحه تاني", "كرر اخر امر") && !contextState.lastOpenedApp.isNullOrBlank()) {
            return phonetic to EgyptianIntent.OpenApp(contextState.lastOpenedApp)
        }

        // 5. Media & Volume Controls
        when {
            containsAny(norm, "وطي الصوت", "هدي الصوت", "قلل الصوت", "صوت واطي", "volume down") ->
                return phonetic to EgyptianIntent.MediaControl(MediaAction.VOLUME_DOWN)
            containsAny(norm, "علي الصوت", "اعلي الصوت", "ارفع الصوت", "زود الصوت", "volume up") ->
                return phonetic to EgyptianIntent.MediaControl(MediaAction.VOLUME_UP)
            containsAny(norm, "اكتم الصوت", "اعمل ميوت", "ميوت", "اصمت", "mute") ->
                return phonetic to EgyptianIntent.MediaControl(MediaAction.MUTE)
            containsAny(norm, "رجع الصوت", "فك الكتم", "افتح الصوت", "unmute") ->
                return phonetic to EgyptianIntent.MediaControl(MediaAction.UNMUTE)
            containsAny(norm, "وقف الاغنيه", "وقف الصوت", "وقف الموسيقي", "pause", "وقف الفيديو", "كمل تشغيل") ->
                return phonetic to EgyptianIntent.MediaControl(MediaAction.PLAY_PAUSE)
            containsAny(norm, "اللي بعده", "التراك اللي بعده", "next") ->
                return phonetic to EgyptianIntent.MediaControl(MediaAction.NEXT_TRACK)
            containsAny(norm, "اللي قبله", "التراك اللي قبله", "previous") ->
                return phonetic to EgyptianIntent.MediaControl(MediaAction.PREVIOUS_TRACK)
        }

        // 6. Device Settings Panels
        when {
            containsAny(norm, "واي فاي", "الواي فاي", "wifi", "wi fi", "النت") &&
                containsAny(norm, "افتح", "شغل", "اعدادات", "اقفل", "فصل") ->
                return phonetic to EgyptianIntent.DeviceSettingPanel(SettingPanelTarget.WIFI)
            containsAny(norm, "بلوتوث", "البلوتوث", "bluetooth") ->
                return phonetic to EgyptianIntent.DeviceSettingPanel(SettingPanelTarget.BLUETOOTH)
            containsAny(norm, "لوكيشن", "اللوكيشن", "gps", "الموقع") &&
                containsAny(norm, "افتح", "شغل", "اعدادات") ->
                return phonetic to EgyptianIntent.DeviceSettingPanel(SettingPanelTarget.LOCATION)
        }

        // 7. Alarms & Timers
        if (containsAny(norm, "منبه", "صحيني", "تايمر", "مؤقت", "عد تنازلي")) {
            return phonetic to parseAlarmOrTimer(norm)
        }

        // 8. System Status Briefing
        if (containsAny(
                norm,
                "الساعه كام", "كام الساعه", "الوقت كام",
                "البطاريه كام", "الشحن كام", "شحني كام",
                "تقرير النظام", "الحاله ايه"
            )
        ) {
            return phonetic to EgyptianIntent.SystemStatusBriefing
        }

        // 9. Calls & SMS Intents
        if (startsWithAny(norm, "كلم ", "اتصل ب", "رن علي", "اعمل مكالمه ل", "call ")) {
            val target = norm
                .removePrefix("كلم ")
                .removePrefix("اتصل بـ")
                .removePrefix("اتصل ب")
                .removePrefix("رن علي")
                .removePrefix("اعمل مكالمه ل")
                .removePrefix("call ")
                .trim()
            return phonetic to EgyptianIntent.CallOrMessageIntent(CommMode.CALL, target.ifEmpty { "جهة اتصال" })
        }
        if (containsAny(norm, "ابعت رساله", "ارسل رساله", "بعت رساله")) {
            val cleaned = norm
                .replace("ابعت رساله ل", "")
                .replace("ابعت رساله الي", "")
                .replace("ابعت رساله", "")
                .trim()
            val parts = cleaned.split("قول له", "قول لها", "محتواها", "نصها", limit = 2)
            val contact = parts.getOrNull(0)?.trim().orEmpty().ifEmpty { "جهة اتصال" }
            val body = parts.getOrNull(1)?.trim()
            return phonetic to EgyptianIntent.CallOrMessageIntent(CommMode.SMS, contact, body)
        }

        // 10. Accessibility Screen UI Actions
        when {
            containsAny(norm, "اسكرول لتحت", "انزل لتحت", "نزل الشاشه", "scroll down") ->
                return phonetic to EgyptianIntent.AccessibilityUiAction(UiActionType.SCROLL_DOWN)
            containsAny(norm, "اسكرول لفوق", "اطلع لفوق", "طلع الشاشه", "scroll up") ->
                return phonetic to EgyptianIntent.AccessibilityUiAction(UiActionType.SCROLL_UP)
            containsAny(norm, "ارجع لورا", "ارجع خطوه", "اعمل باك", "go back") ->
                return phonetic to EgyptianIntent.AccessibilityUiAction(UiActionType.GO_BACK)
            containsAny(norm, "روح للهوم", "الشاشه الرئيسيه", "اطلع بره", "go home") ->
                return phonetic to EgyptianIntent.AccessibilityUiAction(UiActionType.GO_HOME)
            startsWithAny(norm, "دوس علي ", "اضغط علي ", "دوس ", "tap ") -> {
                val target = norm
                    .removePrefix("دوس علي ")
                    .removePrefix("اضغط علي ")
                    .removePrefix("دوس ")
                    .removePrefix("tap ")
                    .trim()
                return phonetic to EgyptianIntent.AccessibilityUiAction(UiActionType.TAP_TEXT, target)
            }
        }

        // 11. Open App
        if (startsWithAny(norm, "افتح ", "شغل ", "هات ", "open ", "launch ")) {
            val appName = norm
                .removePrefix("افتح ")
                .removePrefix("شغل ")
                .removePrefix("هات ")
                .removePrefix("open ")
                .removePrefix("launch ")
                .trim()
            if (appName.isNotBlank()) {
                return phonetic to EgyptianIntent.OpenApp(appName)
            }
        }

        // 12. Egyptian Small Talk & J.A.R.V.I.S. Persona Banter
        when {
            containsAny(norm, "ازيك", "عامل ايه", "اخبارك ايه", "صباح الخير", "مساء الخير", "السلام عليكم", "اهلا") ->
                return phonetic to EgyptianIntent.SmallTalkOrPersona("greeting")
            containsAny(norm, "انت مين", "مين انت", "عرفني بنفسك", "تقدر تعمل ايه", "بتعمل ايه") ->
                return phonetic to EgyptianIntent.SmallTalkOrPersona("identity")
            containsAny(norm, "شكرا", "تسلم", "عاشت ايدك", "برافو", "ممتاز") ->
                return phonetic to EgyptianIntent.SmallTalkOrPersona("thanks")
        }

        return phonetic to EgyptianIntent.Unknown(rawInput.trim())
    }

    private fun checkHardEndLines(norm: String, phonetic: String): Pair<String, EgyptianIntent>? {
        if (FINANCIAL_BLOCK_KEYWORDS.any { norm.contains(EgyptianPhoneticNormalizer.normalizeForNlu(it)) }) {
            return phonetic to EgyptianIntent.BlockedOrUnsafe(
                reasonAr = "ممنوع تنفيذ أي تحويلات مالية أو تعاملات بنكية بالصوت لحمايتك.",
                ruleId = EndLineRule.RULE_BLOCK_FINANCIAL
            )
        }
        if (DESTRUCTIVE_BLOCK_KEYWORDS.any { norm.contains(EgyptianPhoneticNormalizer.normalizeForNlu(it)) }) {
            return phonetic to EgyptianIntent.BlockedOrUnsafe(
                reasonAr = "ممنوع تنفيذ أوامر مسح شاملة أو حذف تطبيقات النظام أو الملفات.",
                ruleId = EndLineRule.RULE_BLOCK_SYSTEM_DESTRUCTIVE
            )
        }
        if (CREDENTIAL_BLOCK_KEYWORDS.any { norm.contains(EgyptianPhoneticNormalizer.normalizeForNlu(it)) }) {
            return phonetic to EgyptianIntent.BlockedOrUnsafe(
                reasonAr = "كلمات السر وأكواد التحقق خط أحمر غير مسموح بالوصول ليه.",
                ruleId = EndLineRule.RULE_BLOCK_CREDENTIAL_HARVEST
            )
        }
        return null
    }

    private fun extractYouTubeQuery(norm: String): String {
        var q = norm
            .replace("افتح يوتيوب وشغل", "")
            .replace("افتح اليوتيوب وشغل", "")
            .replace("افتح يوتيوب ودور علي", "")
            .replace("افتح اليوتيوب ودور علي", "")
            .replace("شغل علي يوتيوب", "")
            .replace("شغل في يوتيوب", "")
            .replace("دور في يوتيوب علي", "")
            .replace("دور علي يوتيوب علي", "")
            .replace("علي اليوتيوب", "")
            .replace("علي يوتيوب", "")
            .replace("في اليوتيوب", "")
            .replace("في يوتيوب", "")
            .replace("يوتيوب", "")
            .replace("youtube", "")
            .replace("افتح", "")
            .replace("شغل", "")
            .trim()
        if (q.startsWith("و ")) q = q.removePrefix("و ").trim()
        return q
    }

    private fun parseAlarmOrTimer(norm: String): EgyptianIntent.SetAlarmOrTimer {
        if (containsAny(norm, "تايمر", "مؤقت", "عد تنازلي")) {
            var mins = extractFirstNumber(norm) ?: when {
                norm.contains("ربع ساعه") -> 15
                norm.contains("نص ساعه") -> 30
                norm.contains("تلت ساعه") -> 20
                else -> 5
            }
            if (norm.contains("ساعه") && !norm.contains("ربع") && !norm.contains("نص")) {
                mins *= 60
            }
            return EgyptianIntent.SetAlarmOrTimer(timerMinutes = mins, label = "تايمر شيتا")
        }

        val rawHour = extractFirstNumber(norm) ?: 8
        val isPm = containsAny(norm, "بالليل", "مساء", "العصر", "المغرب", "الضهر") && rawHour < 12
        val hour24 = if (isPm) rawHour + 12 else rawHour % 24
        val minute = if (norm.contains("ونص")) 30 else if (norm.contains("وربع")) 15 else 0
        return EgyptianIntent.SetAlarmOrTimer(hour = hour24, minute = minute, label = "منبه شيتا")
    }

    private fun extractFirstNumber(norm: String): Int? {
        Regex("\\d+").find(norm)?.value?.toIntOrNull()?.let { return it }
        for ((word, value) in EGYPTIAN_NUMBER_WORDS) {
            if (norm.contains(word)) return value
        }
        return null
    }

    private fun containsAny(text: String, vararg terms: String): Boolean {
        return terms.any { term ->
            text.contains(EgyptianPhoneticNormalizer.normalizeForNlu(term))
        }
    }

    private fun startsWithAny(text: String, vararg prefixes: String): Boolean {
        return prefixes.any { prefix ->
            text.startsWith(EgyptianPhoneticNormalizer.normalizeForNlu(prefix))
        }
    }
}
