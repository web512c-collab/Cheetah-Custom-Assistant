package com.example.voice

enum class VoiceFailureKind {
    MIC_PERMISSION_DENIED,
    OS_BACKGROUND_MIC_BLOCKED,
    NO_LOCAL_STT_MODEL,
    STT_MODEL_LOAD_FAILED,
    MIC_HARDWARE_OR_CAPTURE_FAILED,
    NOISE_OR_NO_SPEECH
}

data class MappedVoiceError(
    val kind: VoiceFailureKind,
    val messageAr: String,
    val suggestSettingsTab: Boolean = false,
    val suggestSttSetup: Boolean = false
)

/**
 * Maps real Android audio / permission / STT / background restriction states into
 * distinct, honest Egyptian-Arabic diagnostic messages (never conflating mic denial
 * with missing STT model or OS background restrictions).
 */
object VoiceErrorMapper {

    fun mapFailure(
        rawReason: String?,
        hasMicPermission: Boolean,
        hasLocalSttModel: Boolean,
        isBackgroundContext: Boolean = false
    ): MappedVoiceError {
        val norm = rawReason.orEmpty().trim()
        val upper = norm.uppercase()

        if (!hasMicPermission ||
            upper.contains("MIC_PERMISSION") ||
            upper.contains("SECURITY_EXCEPTION") ||
            upper.contains("RECORD_AUDIO") ||
            norm.contains("إذن الميكروفون")
        ) {
            return MappedVoiceError(
                kind = VoiceFailureKind.MIC_PERMISSION_DENIED,
                messageAr = "إذن الميكروفون مش متاح — افتح الإعدادات واسمح بالمايك مرة واحدة.",
                suggestSettingsTab = true
            )
        }

        if (upper.contains("NO_STT_MODEL") ||
            norm.contains("نموذج الكلام") ||
            norm.contains("نموذج كلام") ||
            norm.contains("ملف حزمة التعرف")
        ) {
            return MappedVoiceError(
                kind = VoiceFailureKind.NO_LOCAL_STT_MODEL,
                messageAr = "مفيش نموذج كلام محلي (Vosk) متثبت — اختار ملف النموذج (.zip) من الإعدادات عشان أسمعك أوفلاين بدون جوجل.",
                suggestSettingsTab = true,
                suggestSttSetup = true
            )
        }

        if (upper.contains("STT_MODEL_LOAD_FAILED") ||
            upper.contains("VOSK_INIT") ||
            norm.contains("تحميل نموذج الكلام")
        ) {
            return MappedVoiceError(
                kind = VoiceFailureKind.STT_MODEL_LOAD_FAILED,
                messageAr = "تعذر تحميل نموذج الكلام المحلي — تأكد من سلامة مجلد النموذج والذاكرة المتاحة في الإعدادات.",
                suggestSettingsTab = true,
                suggestSttSetup = true
            )
        }

        if (upper.contains("BACKGROUND_MIC_BLOCKED") ||
            upper.contains("FOREGROUND_SERVICE_START_NOT_ALLOWED") ||
            upper.contains("FGS_RESTRICTED") ||
            upper.contains("AUDIO_SILENCED") ||
            norm.contains("حظر الميكروفون في الخلفية") ||
            (isBackgroundContext && (upper.contains("SILENT") || upper.contains("BLOCKED")))
        ) {
            return MappedVoiceError(
                kind = VoiceFailureKind.OS_BACKGROUND_MIC_BLOCKED,
                messageAr = "إذن المايك شغال، لكن نظام أندرويد منع التسجيل في الخلفية أو أثناء قفل الشاشة — افتح شاشة التطبيق أو فعّل استثناء البطارية.",
                suggestSettingsTab = true
            )
        }

        if (upper.contains("AUDIORECORD") ||
            upper.contains("INIT_FAILED") ||
            upper.contains("READ_ERROR") ||
            upper.contains("MIC_BUSY") ||
            upper.contains("HARDWARE")
        ) {
            return MappedVoiceError(
                kind = VoiceFailureKind.MIC_HARDWARE_OR_CAPTURE_FAILED,
                messageAr = "تعذر فتح الميكروفون أو قراءة الصوت — تأكد إن مفيش مكالمة أو تطبيق تاني حاجز المايك."
            )
        }

        if (!hasLocalSttModel) {
            return MappedVoiceError(
                kind = VoiceFailureKind.NO_LOCAL_STT_MODEL,
                messageAr = "مفيش نموذج كلام محلي (Vosk) متثبت — اختار ملف النموذج (.zip) من الإعدادات عشان أسمعك أوفلاين بدون جوجل.",
                suggestSettingsTab = true,
                suggestSttSetup = true
            )
        }

        return MappedVoiceError(
            kind = VoiceFailureKind.NOISE_OR_NO_SPEECH,
            messageAr = if (norm.isNotBlank() && norm.any { it in '\u0600'..'\u06FF' }) {
                norm
            } else {
                "مسمعتش كلام واضح — قرّب من المايك وحاول تاني في مكان أهدى."
            }
        )
    }
}
