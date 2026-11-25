package com.example.guardrails

import com.example.core.model.AssistantSettings
import com.example.core.model.EgyptianIntent
import com.example.core.model.EndLineRule
import com.example.core.model.GuardrailVerdict
import com.example.core.model.PermissionHealthState

/**
 * Deterministic Typed Command Allowlist & Hard "End-Line" Boundary Validator.
 * No action can touch Android device APIs or AccessibilityService without passing here first.
 */
object CommandGuardrailValidator {

    fun validate(
        intent: EgyptianIntent,
        settings: AssistantSettings,
        permissions: PermissionHealthState,
        voiceMatchPassed: Boolean
    ): GuardrailVerdict {
        return when (intent) {
            is EgyptianIntent.BlockedOrUnsafe -> GuardrailVerdict.Denied(
                ruleId = intent.ruleId,
                reasonAr = intent.reasonAr
            )

            is EgyptianIntent.CallOrMessageIntent -> {
                if (settings.requireVoiceMatchForSensitive &&
                    settings.voiceMatchEnabled &&
                    !voiceMatchPassed
                ) {
                    GuardrailVerdict.Denied(
                        ruleId = EndLineRule.RULE_REQUIRE_VOICE_MATCH_COMMS,
                        reasonAr = "لازم بصمة صوتك تتطابق الأول عشان أفتحلك المكالمات أو الرسائل."
                    )
                } else {
                    GuardrailVerdict.Allowed(intent)
                }
            }

            is EgyptianIntent.CloseCurrentApp -> {
                if (!settings.allowAccessibilityAutomation || !permissions.isAccessibilityEnabled) {
                    GuardrailVerdict.Denied(
                        ruleId = EndLineRule.RULE_ACCESSIBILITY_SCREEN_CONTROL,
                        reasonAr = "أندرويد بيمنع قفل التطبيقات التانية قسرياً. لو حابب أرجعلك للشاشة الرئيسية، فعّل خدمة إمكانية الوصول (Accessibility) من الإعدادات.",
                        requiresSettingsRedirect = true
                    )
                } else {
                    GuardrailVerdict.Allowed(intent)
                }
            }

            is EgyptianIntent.AccessibilityUiAction -> {
                if (!settings.allowAccessibilityAutomation) {
                    GuardrailVerdict.Denied(
                        ruleId = EndLineRule.RULE_ACCESSIBILITY_SCREEN_CONTROL,
                        reasonAr = "التحكم في الشاشة مقفول من قواعد الأمان. تقدر تفتحه من الإعدادات.",
                        requiresSettingsRedirect = true
                    )
                } else if (!permissions.isAccessibilityEnabled) {
                    GuardrailVerdict.Denied(
                        ruleId = EndLineRule.RULE_ACCESSIBILITY_SCREEN_CONTROL,
                        reasonAr = "لازم تفعل خدمة إمكانية الوصول (Accessibility) لشيتا من إعدادات الموبايل الأول.",
                        requiresSettingsRedirect = true
                    )
                } else if (settings.requireVoiceMatchForSensitive &&
                    settings.voiceMatchEnabled &&
                    !voiceMatchPassed
                ) {
                    GuardrailVerdict.Denied(
                        ruleId = EndLineRule.RULE_REQUIRE_VOICE_MATCH_COMMS,
                        reasonAr = "التحكم بالشاشة محتاج تطابق بصمة صوتك."
                    )
                } else {
                    GuardrailVerdict.Allowed(intent)
                }
            }

            is EgyptianIntent.CompoundCommands -> {
                for (step in intent.steps) {
                    val stepVerdict = validate(step, settings, permissions, voiceMatchPassed)
                    if (stepVerdict is GuardrailVerdict.Denied) {
                        return stepVerdict
                    }
                }
                GuardrailVerdict.Allowed(intent)
            }

            is EgyptianIntent.OpenApp,
            is EgyptianIntent.YouTubeSearchPlay,
            is EgyptianIntent.WebSearch,
            is EgyptianIntent.MediaControl,
            is EgyptianIntent.DeviceSettingPanel,
            is EgyptianIntent.SetAlarmOrTimer,
            is EgyptianIntent.SystemStatusBriefing,
            is EgyptianIntent.RecallConversationContext,
            is EgyptianIntent.SmallTalkOrPersona,
            is EgyptianIntent.Unknown -> GuardrailVerdict.Allowed(intent)
        }
    }
}
