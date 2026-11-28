package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import com.example.core.model.AssistantPipelineState
import com.example.core.model.AssistantSettings
import com.example.core.model.InteractionTrace
import com.example.core.model.LocalLlmLoadState
import com.example.core.model.LocalLlmRuntimeStatus
import com.example.core.model.PermissionHealthState
import com.example.core.model.ProposedAction
import com.example.ui.theme.CheetahAmber
import com.example.ui.theme.CheetahBorder
import com.example.ui.theme.CheetahCard
import com.example.ui.theme.CheetahCardElevated
import com.example.ui.theme.CheetahCrimson
import com.example.ui.theme.CheetahCyan
import com.example.ui.theme.CheetahDeepNavy
import com.example.ui.theme.CheetahEmerald
import com.example.ui.theme.CheetahObsidian
import com.example.ui.theme.CheetahTextPrimary
import com.example.ui.theme.CheetahTextSecondary
import com.example.voice.MappedVoiceError
import com.example.voice.OfflineSttStatus

private val QUICK_EGYPTIAN_COMMANDS = listOf(
    "افتح يوتيوب وشغل قرآن" to false,
    "دورلي على حالة الطقس في جوجل" to false,
    "وطي الصوت شوية" to false,
    "ظبط منبه الساعة 7 الصبح" to false,
    "الساعة كام والبطارية كام؟" to false,
    "فاكر كنا بنتكلم في إيه؟" to false,
    "حول 500 جنيه انستا باي" to true
)

@Composable
fun ConsoleScreen(
    settings: AssistantSettings,
    permissions: PermissionHealthState,
    offlineSttStatus: OfflineSttStatus,
    voiceErrorBanner: MappedVoiceError? = null,
    serviceRunning: Boolean,
    liveMicLevel: Float,
    pipelineState: AssistantPipelineState,
    partialTranscript: String,
    streamingReplyText: String,
    isNetworkSearching: Boolean,
    localLlmStatus: LocalLlmRuntimeStatus,
    traces: List<InteractionTrace>,
    incomingSharedText: String? = null,
    pendingContinuationText: String? = null,
    pendingProposedAction: ProposedAction? = null,
    onStartVoiceCommand: () -> Unit,
    onDismissVoiceError: () -> Unit = {},
    onSubmitTextCommand: (String) -> Unit,
    onSubmitTextWithAttachment: (String, String?) -> Unit = { cmd, _ -> onSubmitTextCommand(cmd) },
    onConsumeSharedText: () -> Unit = {},
    onReadClipboard: () -> String? = { null },
    onConfirmProposedAction: (ProposedAction) -> Unit = {},
    onDismissProposedAction: () -> Unit = {},
    onContinueSpeech: () -> Unit = {},
    onToggleContinuousListening: (Boolean) -> Unit,
    onToggleMute: () -> Unit,
    onCancelLlmGeneration: () -> Unit,
    onOpenSettingsTab: () -> Unit
) {
    var commandInput by remember { mutableStateOf("") }
    var clipboardAttachedText by remember { mutableStateOf<String?>(null) }
    val activeAttachedText = incomingSharedText ?: clipboardAttachedText
    val attachedLabel = if (incomingSharedText != null) "نص مشارك مع شيتا" else "نص من الحافظة (بطلبك)"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(CheetahObsidian, CheetahDeepNavy, CheetahObsidian)
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                // Header Status Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${settings.assistantName} — القيادة المحلية",
                            style = MaterialTheme.typography.headlineMedium,
                            color = CheetahTextPrimary
                        )
                        val modeSubtitle = buildString {
                            append("Sheeta Custom Assistant • ZERO-API-KEY • ar-EG")
                            if (localLlmStatus.loadState == LocalLlmLoadState.LOADED) append(" • GGUF")
                            if (settings.internetSearchEnabled) append(" • HTTPS Search")
                        }
                        Text(
                            text = modeSubtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = CheetahCyan
                        )
                    }
                    Surface(
                        color = if (permissions.isDefaultAssistant) {
                            CheetahEmerald.copy(alpha = 0.16f)
                        } else {
                            CheetahAmber.copy(alpha = 0.16f)
                        },
                        shape = RoundedCornerShape(50),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (permissions.isDefaultAssistant) CheetahEmerald else CheetahAmber
                        )
                    ) {
                        Text(
                            text = if (permissions.isDefaultAssistant) "مساعد افتراضي" else "جاهز محلياً",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (permissions.isDefaultAssistant) CheetahEmerald else CheetahAmber,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            item {
                // Continuous Wake Word Listener Strip + Kill Switch
                Card(
                    colors = CardDefaults.cardColors(containerColor = CheetahCard),
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CheetahBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Hearing,
                                contentDescription = "الاستماع لكلمة التنبيه",
                                tint = if (serviceRunning && !settings.isListeningMuted) CheetahCyan else CheetahTextSecondary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "كلمة التنبيه: «${settings.wakePhrase}»",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CheetahTextPrimary
                                )
                                Text(
                                    text = when {
                                        !permissions.hasMicPermission -> "محتاج صلاحية المايكروفون"
                                        serviceRunning && settings.isListeningMuted -> "مكتوم مؤقتاً (Kill-Switch)"
                                        serviceRunning -> "الخدمة شغالة أوفلاين • ${settings.wakeSetupMode.labelAr}"
                                        else -> "الاستماع في الخلفية مقفول"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CheetahTextSecondary
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (serviceRunning) {
                                FilledTonalIconButton(
                                    onClick = onToggleMute,
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = if (settings.isListeningMuted) {
                                            CheetahCrimson.copy(alpha = 0.25f)
                                        } else {
                                            CheetahCardElevated
                                        }
                                    ),
                                    modifier = Modifier.testTag("console_mute_kill_switch")
                                ) {
                                    Icon(
                                        imageVector = if (settings.isListeningMuted) {
                                            Icons.AutoMirrored.Filled.VolumeOff
                                        } else {
                                            Icons.AutoMirrored.Filled.VolumeUp
                                        },
                                        contentDescription = "كتم أو تشغيل المايك",
                                        tint = if (settings.isListeningMuted) CheetahCrimson else CheetahCyan
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                            }

                            Switch(
                                checked = serviceRunning,
                                onCheckedChange = { wantEnable ->
                                    if (wantEnable && !permissions.hasMicPermission) {
                                        onOpenSettingsTab()
                                    } else {
                                        onToggleContinuousListening(wantEnable)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CheetahObsidian,
                                    checkedTrackColor = CheetahCyan
                                ),
                                modifier = Modifier.testTag("console_wake_service_switch")
                            )
                        }
                    }
                }
            }

            if (!permissions.hasMicPermission || !offlineSttStatus.hasModelConfigured || voiceErrorBanner != null) {
                item {
                    val bannerColor = if (voiceErrorBanner != null || !permissions.hasMicPermission) {
                        CheetahCrimson
                    } else {
                        CheetahAmber
                    }
                    val bannerText = when {
                        voiceErrorBanner != null -> voiceErrorBanner.messageAr
                        !permissions.hasMicPermission -> "إذن الميكروفون مش مفعّل — افتح الإعدادات للسماح بالمايك أو استخدم الأوامر الكتابية بالأسفل."
                        else -> "التحدث المباشر (Talk) محتاج نموذج كلام محلي (Vosk .zip) من الإعدادات — الأوامر الكتابية شغالة فوراً."
                    }
                    Surface(
                        color = bannerColor.copy(alpha = 0.13f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, bannerColor.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("console_voice_notice_banner")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = bannerColor, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = bannerText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CheetahTextPrimary
                                    )
                                }
                                if (voiceErrorBanner != null) {
                                    IconButton(onClick = onDismissVoiceError) {
                                        Icon(Icons.Default.Close, contentDescription = "إخفاء", tint = CheetahTextSecondary)
                                    }
                                }
                            }
                            if (voiceErrorBanner == null || voiceErrorBanner.suggestSettingsTab) {
                                TextButton(
                                    onClick = onOpenSettingsTab,
                                    modifier = Modifier.testTag("console_notice_open_settings_btn")
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, tint = CheetahCyan, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("فتح الإعدادات", color = CheetahCyan, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            item {
                // Arc-Core Interactive Visualizer Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = CheetahCard),
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        when (pipelineState) {
                            AssistantPipelineState.BLOCKED_BY_GUARDRAIL -> CheetahCrimson.copy(alpha = 0.6f)
                            AssistantPipelineState.LISTENING_COMMAND -> CheetahCyan.copy(alpha = 0.7f)
                            else -> CheetahBorder
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ArcCoreOrb(
                            pipelineState = pipelineState,
                            micLevel = liveMicLevel,
                            onClick = {
                                if (!permissions.hasMicPermission || !offlineSttStatus.hasModelConfigured) {
                                    onStartVoiceCommand() // surfaces mapped notice banner without OS prompt
                                } else {
                                    onStartVoiceCommand()
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = pipelineState.labelAr,
                            style = MaterialTheme.typography.titleMedium,
                            color = when (pipelineState) {
                                AssistantPipelineState.BLOCKED_BY_GUARDRAIL -> CheetahCrimson
                                AssistantPipelineState.SPEAKING -> CheetahAmber
                                else -> CheetahCyan
                            },
                            textAlign = TextAlign.Center
                        )

                        AnimatedVisibility(visible = partialTranscript.isNotBlank()) {
                            Text(
                                text = partialTranscript,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CheetahTextPrimary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }

                        AnimatedVisibility(visible = isNetworkSearching) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Icon(Icons.Default.Public, contentDescription = null, tint = CheetahEmerald, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "جاري البحث على الإنترنت بدون مفاتيح API...",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CheetahEmerald
                                )
                            }
                        }

                        ThinkingIndicatorBanner(
                            llmStatus = localLlmStatus,
                            streamingReplyText = streamingReplyText,
                            onCancelGeneration = onCancelLlmGeneration
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (!permissions.hasMicPermission) {
                                        onOpenSettingsTab()
                                    } else {
                                        onStartVoiceCommand()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (permissions.hasMicPermission && offlineSttStatus.hasModelConfigured) {
                                        CheetahCyan
                                    } else {
                                        CheetahCardElevated
                                    }
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("console_ptt_mic_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "تحدث الآن",
                                    tint = if (permissions.hasMicPermission && offlineSttStatus.hasModelConfigured) {
                                        CheetahObsidian
                                    } else {
                                        CheetahAmber
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when {
                                        !permissions.hasMicPermission -> "افتح الإعدادات للمايك"
                                        !offlineSttStatus.hasModelConfigured -> "تحدث (يلزم نموذج Vosk)"
                                        else -> "تحدث الآن (Talk)"
                                    },
                                    color = if (permissions.hasMicPermission && offlineSttStatus.hasModelConfigured) {
                                        CheetahObsidian
                                    } else {
                                        CheetahAmber
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (!permissions.isDefaultAssistant) {
                                Button(
                                    onClick = onOpenSettingsTab,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CheetahCardElevated,
                                        contentColor = CheetahAmber
                                    ),
                                    modifier = Modifier
                                        .height(50.dp)
                                        .testTag("console_open_setup_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PowerSettingsNew,
                                        contentDescription = "إعداد المساعد"
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("التفعيل")
                                }
                            }
                        }
                    }
                }
            }

            if (!activeAttachedText.isNullOrBlank()) {
                item {
                    AttachedTextActionBanner(
                        attachedText = activeAttachedText,
                        sourceLabel = attachedLabel,
                        onRunTaskOnAttached = { prompt ->
                            val textToProcess = activeAttachedText
                            clipboardAttachedText = null
                            onConsumeSharedText()
                            onSubmitTextWithAttachment(prompt, textToProcess)
                        },
                        onDismiss = {
                            clipboardAttachedText = null
                            onConsumeSharedText()
                        }
                    )
                }
            }

            if (pendingProposedAction != null) {
                item {
                    ProposedActionConfirmCard(
                        action = pendingProposedAction,
                        onConfirm = onConfirmProposedAction,
                        onDismiss = onDismissProposedAction
                    )
                }
            }

            item {
                // Direct Egyptian Text Command Input + Quick Command Chips
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = commandInput,
                            onValueChange = { commandInput = it },
                            placeholder = {
                                Text(
                                    text = "اكتب أمر أو سؤال بالمصري (أو لخص/اشرح/ترجم)...",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CheetahCyan,
                                unfocusedBorderColor = CheetahBorder,
                                focusedContainerColor = CheetahCard,
                                unfocusedContainerColor = CheetahCard
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("console_command_input")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        FilledTonalIconButton(
                            onClick = {
                                val clip = onReadClipboard()
                                if (!clip.isNullOrBlank()) {
                                    clipboardAttachedText = clip
                                } else {
                                    onSubmitTextCommand("اقرا الحافظة")
                                }
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = CheetahCard),
                            modifier = Modifier
                                .size(50.dp)
                                .testTag("console_paste_clipboard_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "لصق نص من الحافظة",
                                tint = CheetahCyan
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = {
                                if (commandInput.isNotBlank()) {
                                    val cmd = commandInput
                                    commandInput = ""
                                    val attached = activeAttachedText
                                    clipboardAttachedText = null
                                    if (incomingSharedText != null) onConsumeSharedText()
                                    onSubmitTextWithAttachment(cmd, attached)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CheetahAmber),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("console_send_command_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "تنفيذ الأمر",
                                tint = CheetahObsidian
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QUICK_EGYPTIAN_COMMANDS.forEachIndexed { idx, (phrase, isRedLineTest) ->
                            AssistChip(
                                onClick = { onSubmitTextCommand(phrase) },
                                label = {
                                    Text(
                                        text = phrase,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isRedLineTest) CheetahCrimson else CheetahTextPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isRedLineTest) Icons.Default.Block else Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = if (isRedLineTest) CheetahCrimson else CheetahCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = CheetahCard
                                ),
                                border = AssistChipDefaults.assistChipBorder(
                                    enabled = true,
                                    borderColor = if (isRedLineTest) CheetahCrimson.copy(alpha = 0.5f) else CheetahBorder
                                ),
                                modifier = Modifier.testTag("quick_cmd_chip_$idx")
                            )
                        }
                    }
                }
            }

            if (traces.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CheetahCard.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(18.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CheetahBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = CheetahCyan,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "سجل الأوامر المحلية فاضي",
                                style = MaterialTheme.typography.titleMedium,
                                color = CheetahTextPrimary
                            )
                            Text(
                                text = "جرب تضغط «اتكلم بالمصري» أو اختار أي مثال فوق عشان تشوف تحليل العامية المصرية وقواعد الخط الأحمر شغالة أوفلاين.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CheetahTextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            } else {
                items(traces, key = { it.id }) { trace ->
                    EnrichedTraceLogCard(
                        trace = trace,
                        assistantName = settings.assistantName,
                        showReasoning = settings.localLlmConfig.showReasoning,
                        showSources = settings.localLlmConfig.showSources,
                        hasPendingContinuation = !pendingContinuationText.isNullOrBlank(),
                        onContinueSpeech = onContinueSpeech,
                        onOpenModelSettings = onOpenSettingsTab
                    )
                }
            }
        }
    }
}

@Composable
private fun ArcCoreOrb(
    pipelineState: AssistantPipelineState,
    micLevel: Float,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "arc_orb")
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val coreColor = when (pipelineState) {
        AssistantPipelineState.BLOCKED_BY_GUARDRAIL -> CheetahCrimson
        AssistantPipelineState.SPEAKING -> CheetahAmber
        AssistantPipelineState.LISTENING_COMMAND,
        AssistantPipelineState.LISTENING_WAKE_WORD -> CheetahCyan
        else -> CheetahCyan.copy(alpha = 0.85f)
    }

    Box(
        modifier = Modifier
            .size(144.dp)
            .clip(CircleShape)
            .testTag("arc_core_orb"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.34f
            val dynamicRadius = baseRadius * (pulse + micLevel * 0.25f)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(coreColor.copy(alpha = 0.35f), Color.Transparent),
                    center = center,
                    radius = size.minDimension * 0.5f
                ),
                center = center,
                radius = size.minDimension * 0.5f
            )

            drawCircle(
                color = coreColor.copy(alpha = 0.35f),
                radius = dynamicRadius + 10.dp.toPx(),
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            drawCircle(
                color = coreColor,
                radius = dynamicRadius,
                center = center,
                style = Stroke(width = 3.5.dp.toPx())
            )
        }

        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = CheetahCardElevated,
            modifier = Modifier
                .size(72.dp)
                .border(1.5.dp, coreColor, CircleShape)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "تفعيل الاستماع",
                    tint = coreColor,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
