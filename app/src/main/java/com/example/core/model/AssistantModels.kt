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
    val modelStatus: String = "CHKW-v1 ('يا شيتا') + 0/3 MFCC",
    val detectionThreshold: Float = 0.58f,
    val lastConfidence: Float? = null,
    val lastVoiceMatchScore: Float? = null,
    val lastVoiceMatchResult: String = "N/A",
    val lastAudioEvent: String = "لا يوجد نشاط بعد",
    val lastWakeTimestampMs: Long? = null,
    val lastError: String? = null,
    val falseActivationsCount: Int = 0,
    val ambientNoiseRms: Float = 0f
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
    val wakePhrase: String = "يا شيتا",
    val wakeSetupMode: WakeWordSetupMode = WakeWordSetupMode.TEXT_TYPED,
    val wakeSensitivity: Float = 0.65f,
    val voiceMatchEnabled: Boolean = true,
    val voiceMatchThreshold: Float = 0.68f,
    val allowOwnerFallbackOnLowVoiceMatch: Boolean = true,
    val wakeAckPhrase: String = "أيوه، معاك",
    val personaTone: PersonaTone = PersonaTone.JARVIS_FORMAL_EGYPTIAN,
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 0.96f,
    val continuousListeningEnabled: Boolean = false,
    val isListeningMuted: Boolean = false,
    val allowAccessibilityAutomation: Boolean = false,
    val requireVoiceMatchForSensitive: Boolean = true,
    val openDialerInsteadOfDirectCall: Boolean = true
)

data class PermissionHealthState(
    val hasMicPermission: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val isDefaultAssistant: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val canDrawOverlays: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false
) {
    val readinessScore: Int
        get() {
            var score = 0
            if (hasMicPermission) score += 30
            if (hasNotificationPermission) score += 15
            if (isDefaultAssistant) score += 25
            if (isBatteryOptimizationIgnored) score += 15
            if (isAccessibilityEnabled) score += 10
            if (canDrawOverlays) score += 5
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
    val timestampFormatted: String
)
