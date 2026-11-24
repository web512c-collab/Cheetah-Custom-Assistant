package com.example.controller

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import com.example.core.model.CommMode
import com.example.core.model.EgyptianIntent
import com.example.core.model.MediaAction
import com.example.core.model.PersonaTone
import com.example.core.model.SettingPanelTarget
import com.example.core.model.UiActionType
import com.example.intelligence.JarvisEgyptianResponder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ActionExecutionResult(
    val succeeded: Boolean,
    val replyAr: String
)

/**
 * Executes validated typed commands using real Android OS APIs and Intents.
 */
object DeviceActionExecutor {

    fun execute(
        context: Context,
        intent: EgyptianIntent,
        tone: PersonaTone
    ): ActionExecutionResult {
        return when (intent) {
            is EgyptianIntent.OpenApp -> executeOpenApp(context, intent, tone)
            is EgyptianIntent.YouTubeSearchPlay -> executeYouTube(context, intent, tone)
            is EgyptianIntent.WebSearch -> executeWebSearch(context, intent, tone)
            is EgyptianIntent.MediaControl -> executeMediaControl(context, intent, tone)
            is EgyptianIntent.DeviceSettingPanel -> executeSettingPanel(context, intent, tone)
            is EgyptianIntent.SetAlarmOrTimer -> executeAlarmOrTimer(context, intent, tone)
            is EgyptianIntent.CallOrMessageIntent -> executeCommIntent(context, intent, tone)
            is EgyptianIntent.SystemStatusBriefing -> executeSystemBriefing(context, tone)
            is EgyptianIntent.AccessibilityUiAction -> executeAccessibility(intent, tone)
            is EgyptianIntent.SmallTalkOrPersona -> ActionExecutionResult(
                succeeded = true,
                replyAr = JarvisEgyptianResponder.formatSuccessReply(intent, tone)
            )
            is EgyptianIntent.BlockedOrUnsafe -> ActionExecutionResult(
                succeeded = false,
                replyAr = JarvisEgyptianResponder.formatGuardrailBlockReply(intent.reasonAr, tone)
            )
            is EgyptianIntent.Unknown -> ActionExecutionResult(
                succeeded = false,
                replyAr = JarvisEgyptianResponder.formatSuccessReply(intent, tone)
            )
        }
    }

    private fun executeOpenApp(
        context: Context,
        intent: EgyptianIntent.OpenApp,
        tone: PersonaTone
    ): ActionExecutionResult {
        val (pkg, launchIntent) = PackageResolver.resolveLaunchIntent(context, intent.appQuery)
        if (launchIntent == null) {
            return ActionExecutionResult(
                succeeded = false,
                replyAr = "مش لاقي تطبيق «${intent.appQuery}» متثبت على الموبايل حالياً."
            )
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            ActionExecutionResult(
                succeeded = true,
                replyAr = JarvisEgyptianResponder.formatSuccessReply(
                    intent.copy(resolvedPackage = pkg),
                    tone
                )
            )
        }.getOrElse {
            ActionExecutionResult(
                succeeded = false,
                replyAr = "ماقدرتش أفتح «${intent.appQuery}» بسبب قيود النظام."
            )
        }
    }

    private fun executeYouTube(
        context: Context,
        intent: EgyptianIntent.YouTubeSearchPlay,
        tone: PersonaTone
    ): ActionExecutionResult {
        val ytSearchIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", intent.query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launchedApp = runCatching {
            context.startActivity(ytSearchIntent)
            true
        }.getOrDefault(false)

        if (!launchedApp) {
            val webUri = Uri.parse(
                "https://www.youtube.com/results?search_query=${Uri.encode(intent.query)}"
            )
            val fallback = Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching {
                context.startActivity(fallback)
                true
            }.getOrDefault(false)
            if (!ok) {
                return ActionExecutionResult(
                    succeeded = false,
                    replyAr = "مش لاقي تطبيق يوتيوب أو متصفح على الجهاز."
                )
            }
        }
        return ActionExecutionResult(
            succeeded = true,
            replyAr = JarvisEgyptianResponder.formatSuccessReply(intent, tone)
        )
    }

    private fun executeWebSearch(
        context: Context,
        intent: EgyptianIntent.WebSearch,
        tone: PersonaTone
    ): ActionExecutionResult {
        val webSearch = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, intent.query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching {
            context.startActivity(webSearch)
            true
        }.getOrDefault(false)

        if (!ok) {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=${Uri.encode(intent.query)}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val browserOk = runCatching {
                context.startActivity(browserIntent)
                true
            }.getOrDefault(false)
            if (!browserOk) {
                return ActionExecutionResult(
                    succeeded = false,
                    replyAr = "مفيش متصفح متاح حالياً لعرض نتائج البحث."
                )
            }
        }
        return ActionExecutionResult(
            succeeded = true,
            replyAr = JarvisEgyptianResponder.formatSuccessReply(intent, tone)
        )
    }

    private fun executeMediaControl(
        context: Context,
        intent: EgyptianIntent.MediaControl,
        tone: PersonaTone
    ): ActionExecutionResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionExecutionResult(false, "خدمة الصوت غير متاحة.")

        when (intent.action) {
            MediaAction.VOLUME_UP -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            MediaAction.VOLUME_DOWN -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            MediaAction.MUTE -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            MediaAction.UNMUTE -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            MediaAction.PLAY_PAUSE -> dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            MediaAction.NEXT_TRACK -> dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_NEXT)
            MediaAction.PREVIOUS_TRACK -> dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }

        return ActionExecutionResult(
            succeeded = true,
            replyAr = JarvisEgyptianResponder.formatSuccessReply(intent, tone)
        )
    }

    private fun dispatchMediaKey(audioManager: AudioManager, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun executeSettingPanel(
        context: Context,
        intent: EgyptianIntent.DeviceSettingPanel,
        tone: PersonaTone
    ): ActionExecutionResult {
        val panelIntent = when (intent.target) {
            SettingPanelTarget.WIFI -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_WIFI)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }
            }
            SettingPanelTarget.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            SettingPanelTarget.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            SettingPanelTarget.SYSTEM_SETTINGS -> Intent(Settings.ACTION_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            context.startActivity(panelIntent)
            ActionExecutionResult(
                succeeded = true,
                replyAr = JarvisEgyptianResponder.formatSuccessReply(intent, tone)
            )
        }.getOrElse {
            ActionExecutionResult(false, "ماقدرتش أفتح لوحة الإعدادات المطلوبة.")
        }
    }

    private fun executeAlarmOrTimer(
        context: Context,
        intent: EgyptianIntent.SetAlarmOrTimer,
        tone: PersonaTone
    ): ActionExecutionResult {
        val clockIntent = if (intent.timerMinutes != null) {
            Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, intent.timerMinutes * 60)
                putExtra(AlarmClock.EXTRA_MESSAGE, intent.label ?: "تايمر شيتا")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }
        } else {
            Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, intent.hour ?: 8)
                putExtra(AlarmClock.EXTRA_MINUTES, intent.minute ?: 0)
                putExtra(AlarmClock.EXTRA_MESSAGE, intent.label ?: "منبه شيتا")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            context.startActivity(clockIntent)
            ActionExecutionResult(
                succeeded = true,
                replyAr = JarvisEgyptianResponder.formatSuccessReply(intent, tone)
            )
        }.getOrElse {
            ActionExecutionResult(false, "تطبيق الساعة مش متاح لضبط المنبه أو التايمر.")
        }
    }

    private fun executeCommIntent(
        context: Context,
        intent: EgyptianIntent.CallOrMessageIntent,
        tone: PersonaTone
    ): ActionExecutionResult {
        val commIntent = if (intent.mode == CommMode.CALL) {
            val digits = intent.contactQuery.filter { it.isDigit() || it == '+' }
            val uri = if (digits.length >= 3) Uri.parse("tel:$digits") else Uri.parse("tel:")
            Intent(Intent.ACTION_DIAL, uri)
        } else {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
                if (!intent.messageBody.isNullOrBlank()) {
                    putExtra("sms_body", intent.messageBody)
                }
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            context.startActivity(commIntent)
            ActionExecutionResult(
                succeeded = true,
                replyAr = JarvisEgyptianResponder.formatSuccessReply(intent, tone)
            )
        }.getOrElse {
            ActionExecutionResult(false, "تعذر فتح تطبيق الاتصال أو الرسائل.")
        }
    }

    private fun executeSystemBriefing(
        context: Context,
        tone: PersonaTone
    ): ActionExecutionResult {
        val timeStr = SimpleDateFormat("hh:mm a", Locale.forLanguageTag("ar-EG")).format(Date())
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val batteryText = if (batteryPct in 0..100) "والبطارية $batteryPct%" else ""

        val reply = when (tone) {
            PersonaTone.JARVIS_FORMAL_EGYPTIAN ->
                "الساعة دلوقتي $timeStr $batteryText يا فندم، وكل الأنظمة المحلية شغالة أوفلاين بكفاءة."
            PersonaTone.FRIENDLY_EGYPTIAN ->
                "الساعة دلوقتي $timeStr $batteryText، وكله تمام وزي الفل!"
            PersonaTone.CONCISE_TACTICAL ->
                "$timeStr | بطارية $batteryPct% | أوفلاين جاهز."
        }
        return ActionExecutionResult(succeeded = true, replyAr = reply)
    }

    private fun executeAccessibility(
        intent: EgyptianIntent.AccessibilityUiAction,
        tone: PersonaTone
    ): ActionExecutionResult {
        val service = CheetahAccessibilityService.activeInstance
            ?: return ActionExecutionResult(
                succeeded = false,
                replyAr = "خدمة إمكانية الوصول (Accessibility) مش شغالة دلوقتي. فعلها من إعدادات الموبايل."
            )

        val ok = when (intent.actionType) {
            UiActionType.SCROLL_DOWN -> service.performScroll(down = true)
            UiActionType.SCROLL_UP -> service.performScroll(down = false)
            UiActionType.GO_BACK -> service.performBack()
            UiActionType.GO_HOME -> service.performHome()
            UiActionType.TAP_TEXT -> service.tapElementByText(intent.targetText.orEmpty())
        }

        return if (ok) {
            ActionExecutionResult(
                succeeded = true,
                replyAr = JarvisEgyptianResponder.formatSuccessReply(intent, tone)
            )
        } else {
            ActionExecutionResult(
                succeeded = false,
                replyAr = "ماقدرتش أنفذ الحركة دي على الشاشة الحالية."
            )
        }
    }
}
