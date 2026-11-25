package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.model.AssistantSettings
import com.example.core.model.ConversationContextState
import com.example.core.model.EndLineRule
import com.example.core.model.PermissionHealthState
import com.example.core.model.PersonaTone
import com.example.core.model.VoiceTemplateSample
import com.example.core.model.WakeDiagnosticSnapshot
import com.example.data.VoiceTemplateStore
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
import com.example.voice.OfflineSttStatus
import com.example.voice.TtsStatus
import java.util.Locale

private val WAKE_PRESETS = listOf("يا شيتا", "هاي شيتا", "يا كابتن", "ألو نمر")

@Composable
fun SettingsScreen(
    settings: AssistantSettings,
    rules: List<EndLineRule>,
    enrolledSamples: List<VoiceTemplateSample>,
    permissions: PermissionHealthState,
    ttsStatus: TtsStatus,
    offlineSttStatus: OfflineSttStatus,
    diagnostics: WakeDiagnosticSnapshot,
    memoryState: ConversationContextState,
    serviceRunning: Boolean,
    liveMicLevel: Float,
    isRecordingEnrollment: Boolean,
    enrollmentAmplitude: Float,
    enrollmentFeedbackAr: String?,
    onLaunchDefaultAssistantPicker: () -> Unit,
    onOpenVoiceInputSettings: () -> Unit,
    onSaveTypedWakePhrase: (String) -> Unit,
    onRecordVoiceSample: (String) -> Unit,
    onClearVoiceSamples: () -> Unit,
    onUpdateWakeSensitivity: (Float) -> Unit,
    onUpdateVoiceMatch: (Boolean, Float?, Boolean?) -> Unit,
    onUpdateWakeAckPhrase: (String) -> Unit,
    onUpdatePersonaTone: (PersonaTone) -> Unit,
    onUpdateTtsTuning: (Float, Float) -> Unit,
    onTestEgyptianTts: () -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onToggleContinuousListening: (Boolean) -> Unit,
    onToggleMute: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onRunMicDiagnosticTest: () -> Unit,
    onMarkFalseActivation: () -> Unit,
    onResetDiagnosticCounters: () -> Unit,
    onAddShortcut: (String, String) -> Unit,
    onDeleteShortcut: (String) -> Unit,
    onDeleteMemoryEntry: (Long) -> Unit,
    onClearAllMemory: () -> Unit
) {
    var wakeTabIndex by remember { mutableIntStateOf(0) }
    var draftWakeName by remember(settings.wakePhrase) { mutableStateOf(settings.wakePhrase) }
    var draftAckPhrase by remember(settings.wakeAckPhrase) { mutableStateOf(settings.wakeAckPhrase) }

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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "مركز الإعدادات والتحكم",
                        style = MaterialTheme.typography.headlineMedium,
                        color = CheetahTextPrimary
                    )
                    Text(
                        text = "تحكم كامل في المساعد الافتراضي، اسم التنبيه، البصمة الصوتية، التشخيص، والذاكرة المحلية",
                        style = MaterialTheme.typography.bodySmall,
                        color = CheetahTextSecondary
                    )
                }
            }

            // 1. DEFAULT DIGITAL ASSISTANT CARD
            item {
                SettingsSectionCard(
                    title = "1. المساعد الرقمي الافتراضي (Default Assistant)",
                    icon = Icons.Default.Assistant,
                    accent = if (permissions.isDefaultAssistant) CheetahEmerald else CheetahAmber
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (permissions.isDefaultAssistant) {
                                    "شيتا متفعّل حالياً كمساعد الهاتف الافتراضي"
                                } else {
                                    "شيتا لسه مش المساعد الافتراضي للموبايل"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = if (permissions.isDefaultAssistant) CheetahEmerald else CheetahAmber
                            )
                            Text(
                                text = "لما تفعّله، تقدر تندهله بالضغط المطول على زر الهوم أو سحب زاوية الشاشة من أي تطبيق.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CheetahTextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = if (permissions.isDefaultAssistant) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.WarningAmber
                            },
                            contentDescription = null,
                            tint = if (permissions.isDefaultAssistant) CheetahEmerald else CheetahAmber,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onLaunchDefaultAssistantPicker,
                        colors = ButtonDefaults.buttonColors(containerColor = CheetahCyan),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("settings_set_default_assistant_btn")
                    ) {
                        Text(
                            text = if (permissions.isDefaultAssistant) {
                                "تغيير / مراجعة إعدادات المساعد الافتراضي"
                            } else {
                                "تفعيل شيتا كمساعد الهاتف الافتراضي"
                            },
                            color = CheetahObsidian,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onOpenVoiceInputSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("settings_open_voice_input_settings_btn")
                    ) {
                        Text(
                            text = "فتح إعدادات النظام (Android 7.0+ Voice Input)",
                            color = CheetahTextPrimary
                        )
                    }
                }
            }

            // 2. CUSTOM WAKE NAME, VOICE MATCH & BACKGROUND SERVICE
            item {
                SettingsSectionCard(
                    title = "2. اسم التنبيه المخصص و Voice Match",
                    icon = Icons.Default.GraphicEq,
                    accent = CheetahCyan
                ) {
                    Text(
                        text = "الاسم الحالي: «${settings.wakePhrase}» (${settings.wakeSetupMode.labelAr})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CheetahCyan,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Background Listening + Kill-Switch controls directly in Settings too
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Hearing, contentDescription = null, tint = if (serviceRunning) CheetahEmerald else CheetahTextSecondary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("الاستماع المستمر في الخلفية", style = MaterialTheme.typography.bodyMedium, color = CheetahTextPrimary, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (serviceRunning) {
                                        if (settings.isListeningMuted) "شغال — مكتوم مؤقتاً" else "شغال أوفلاين (شاشة مقفولة أو مفتوحة)"
                                    } else "متوقف",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CheetahTextSecondary
                                )
                            }
                        }
                        Switch(
                            checked = serviceRunning,
                            onCheckedChange = { want ->
                                if (want && !permissions.hasMicPermission) onRequestMicPermission()
                                else onToggleContinuousListening(want)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = CheetahObsidian, checkedTrackColor = CheetahCyan),
                            modifier = Modifier.testTag("settings_continuous_listening_switch")
                        )
                    }

                    if (serviceRunning) {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = onToggleMute,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("settings_mute_kill_switch")
                        ) {
                            Text(
                                text = if (settings.isListeningMuted) "إلغاء الكتم واستئناف السمع" else "كتم المايك فوراً (Kill-Switch)",
                                color = if (settings.isListeningMuted) CheetahEmerald else CheetahCrimson
                            )
                        }
                    }

                    HorizontalDivider(color = CheetahBorder, modifier = Modifier.padding(vertical = 12.dp))

                    TabRow(
                        selectedTabIndex = wakeTabIndex,
                        containerColor = CheetahDeepNavy,
                        contentColor = CheetahCyan,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    ) {
                        Tab(
                            selected = wakeTabIndex == 0,
                            onClick = { wakeTabIndex = 0 },
                            text = { Text("بالتسجيل الصوتي") },
                            icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                            modifier = Modifier.testTag("settings_wake_tab_voice")
                        )
                        Tab(
                            selected = wakeTabIndex == 1,
                            onClick = { wakeTabIndex = 1 },
                            text = { Text("بالكتابة (نص)") },
                            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            modifier = Modifier.testTag("settings_wake_tab_text")
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = draftWakeName,
                        onValueChange = { draftWakeName = it },
                        label = { Text("اسم أو جملة التنبيه") },
                        placeholder = { Text("مثلاً: يا شيتا") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CheetahCyan,
                            unfocusedBorderColor = CheetahBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_wake_name_input")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        WAKE_PRESETS.forEach { preset ->
                            AssistChip(
                                onClick = { draftWakeName = preset },
                                label = { Text(preset, style = MaterialTheme.typography.bodySmall) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = CheetahCardElevated
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (wakeTabIndex == 0) {
                        Text(
                            text = "سجل اسم التنبيه بصوتك لحد ${VoiceTemplateStore.MAX_SAMPLES} مرات لبناء نموذج MFCC-DTW محلي يتعرف على الاسم ونبرة صوتك.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CheetahTextSecondary
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        LinearProgressIndicator(
                            progress = {
                                if (isRecordingEnrollment) enrollmentAmplitude
                                else enrolledSamples.size.toFloat() / VoiceTemplateStore.MAX_SAMPLES
                            },
                            color = if (isRecordingEnrollment) CheetahAmber else CheetahCyan,
                            trackColor = CheetahDeepNavy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "النماذج المسجلة: ${enrolledSamples.size} / ${VoiceTemplateStore.MAX_SAMPLES}",
                                style = MaterialTheme.typography.labelMedium,
                                color = CheetahTextPrimary
                            )
                            if (enrolledSamples.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = onClearVoiceSamples,
                                    modifier = Modifier.testTag("settings_clear_voice_samples_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "مسح البصمات",
                                        tint = CheetahCrimson,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("مسح", color = CheetahCrimson)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                if (!permissions.hasMicPermission) {
                                    onRequestMicPermission()
                                } else {
                                    onRecordVoiceSample(draftWakeName.ifBlank { "يا شيتا" })
                                }
                            },
                            enabled = !isRecordingEnrollment,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecordingEnrollment) CheetahAmber else CheetahCyan
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("settings_record_wake_sample_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = CheetahObsidian
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRecordingEnrollment) {
                                    "جاري التسجيل... قول «${draftWakeName.ifBlank { "يا شيتا" }}»"
                                } else if (!permissions.hasMicPermission) {
                                    "امنح صلاحية المايك للتسجيل"
                                } else {
                                    "اضغط وسجل بصوتك (${enrolledSamples.size + 1})"
                                },
                                color = CheetahObsidian,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text(
                            text = "اكتب اسم التنبيه بالعربي أو بالإنجليزي. في الوضع النصي الصرف بدون تسجيل صوتي، يتحقق النظام من الكلمة عبر STT المحلي عند سماع كلام واضح.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CheetahTextSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { onSaveTypedWakePhrase(draftWakeName) },
                            colors = ButtonDefaults.buttonColors(containerColor = CheetahAmber),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("settings_save_typed_wake_btn")
                        ) {
                            Text(
                                text = "حفظ اسم التنبيه النصي",
                                color = CheetahObsidian,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (!enrollmentFeedbackAr.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = enrollmentFeedbackAr,
                            style = MaterialTheme.typography.bodySmall,
                            color = CheetahEmerald
                        )
                    }

                    HorizontalDivider(
                        color = CheetahBorder,
                        modifier = Modifier.padding(vertical = 14.dp)
                    )

                    Text(
                        text = "حساسية كلمة التنبيه: ${(settings.wakeSensitivity * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CheetahTextPrimary
                    )
                    Slider(
                        value = settings.wakeSensitivity,
                        onValueChange = onUpdateWakeSensitivity,
                        valueRange = 0.25f..0.95f,
                        colors = SliderDefaults.colors(
                            thumbColor = CheetahCyan,
                            activeTrackColor = CheetahCyan
                        ),
                        modifier = Modifier.testTag("settings_sensitivity_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "بصمة الصوت (Voice Match)",
                                style = MaterialTheme.typography.titleMedium,
                                color = CheetahTextPrimary
                            )
                            Text(
                                text = "يتعرف على نبرة صوتك ويمنع تفعيل المساعد من أصوات تانية",
                                style = MaterialTheme.typography.bodySmall,
                                color = CheetahTextSecondary
                            )
                        }
                        Switch(
                            checked = settings.voiceMatchEnabled,
                            onCheckedChange = { onUpdateVoiceMatch(it, null, null) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CheetahObsidian,
                                checkedTrackColor = CheetahCyan
                            ),
                            modifier = Modifier.testTag("settings_voice_match_switch")
                        )
                    }

                    if (settings.voiceMatchEnabled) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "دقة تطابق بصمة الصوت: ${(settings.voiceMatchThreshold * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = CheetahTextSecondary
                        )
                        Slider(
                            value = settings.voiceMatchThreshold,
                            onValueChange = { onUpdateVoiceMatch(true, it, null) },
                            valueRange = 0.50f..0.90f,
                            colors = SliderDefaults.colors(
                                thumbColor = CheetahAmber,
                                activeTrackColor = CheetahAmber
                            ),
                            modifier = Modifier.testTag("settings_voice_match_threshold_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "بديل المالك الآمن عند انخفاض الثقة",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CheetahTextPrimary
                                )
                                Text(
                                    text = "يسمح بالاستجابة في الضوضاء أو البرد لو نطق كلمة التنبيه قوي جداً.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CheetahTextSecondary
                                )
                            }
                            Switch(
                                checked = settings.allowOwnerFallbackOnLowVoiceMatch,
                                onCheckedChange = { onUpdateVoiceMatch(true, null, it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CheetahObsidian,
                                    checkedTrackColor = CheetahEmerald
                                ),
                                modifier = Modifier.testTag("settings_voice_match_fallback_switch")
                            )
                        }
                    }
                }
            }

            // 3. PERSONA, WAKE ACK, EGYPTIAN TTS & HARD END-LINE BOUNDARIES
            item {
                SettingsSectionCard(
                    title = "3. شخصية J.A.R.V.I.S. والخطوط الحمراء",
                    icon = Icons.Default.Shield,
                    accent = CheetahAmber
                ) {
                    Text(
                        text = "عبارة الرد الفوري عند سماع كلمة التنبيه",
                        style = MaterialTheme.typography.titleMedium,
                        color = CheetahTextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = draftAckPhrase,
                            onValueChange = { draftAckPhrase = it },
                            singleLine = true,
                            placeholder = { Text("أيوه، معاك") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CheetahCyan,
                                unfocusedBorderColor = CheetahBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_wake_ack_input")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onUpdateWakeAckPhrase(draftAckPhrase) },
                            colors = ButtonDefaults.buttonColors(containerColor = CheetahCyan),
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("settings_save_wake_ack_btn")
                        ) {
                            Text("حفظ", color = CheetahObsidian, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = CheetahBorder, modifier = Modifier.padding(vertical = 14.dp))

                    Text(
                        text = "أسلوب الكلام بالعامية المصرية",
                        style = MaterialTheme.typography.titleMedium,
                        color = CheetahTextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PersonaTone.entries.forEach { tone ->
                            val selected = settings.personaTone == tone
                            Surface(
                                color = if (selected) CheetahCyan.copy(alpha = 0.14f) else CheetahDeepNavy,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        if (selected) CheetahCyan else CheetahBorder,
                                        RoundedCornerShape(14.dp)
                                    )
                                    .clickable { onUpdatePersonaTone(tone) }
                                    .testTag("settings_persona_${tone.name}")
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = tone.labelAr,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (selected) CheetahCyan else CheetahTextPrimary
                                        )
                                        Text(
                                            text = tone.badgeEn,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CheetahAmber
                                        )
                                    }
                                    Text(
                                        text = tone.subtitleAr,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CheetahTextSecondary
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        color = CheetahBorder,
                        modifier = Modifier.padding(vertical = 14.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = if (ttsStatus.hasEgyptianVoice) CheetahEmerald else CheetahAmber
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = ttsStatus.statusDetailAr,
                            style = MaterialTheme.typography.bodySmall,
                            color = CheetahTextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = String.format(
                            Locale.US,
                            "سرعة النطق: %.2fx  •  حدة الصوت: %.2f",
                            settings.ttsSpeechRate,
                            settings.ttsPitch
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = CheetahTextPrimary
                    )
                    Slider(
                        value = settings.ttsSpeechRate,
                        onValueChange = { onUpdateTtsTuning(it, settings.ttsPitch) },
                        valueRange = 0.7f..1.4f,
                        colors = SliderDefaults.colors(thumbColor = CheetahCyan, activeTrackColor = CheetahCyan)
                    )
                    Slider(
                        value = settings.ttsPitch,
                        onValueChange = { onUpdateTtsTuning(settings.ttsSpeechRate, it) },
                        valueRange = 0.7f..1.3f,
                        colors = SliderDefaults.colors(thumbColor = CheetahAmber, activeTrackColor = CheetahAmber)
                    )

                    OutlinedButton(
                        onClick = onTestEgyptianTts,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("settings_test_tts_btn")
                    ) {
                        Text("تجربة النطق بالعامية المصرية", color = CheetahCyan)
                    }

                    HorizontalDivider(
                        color = CheetahBorder,
                        modifier = Modifier.padding(vertical = 14.dp)
                    )

                    Text(
                        text = "قواعد الخط الأحمر (Deterministic End-Line Guardrails)",
                        style = MaterialTheme.typography.titleMedium,
                        color = CheetahCrimson
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    rules.forEach { rule ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (rule.isHardLocked) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "قاعدة ثابتة",
                                            tint = CheetahCrimson,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = rule.titleAr,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = CheetahTextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = rule.descriptionAr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CheetahTextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = rule.isEnabled,
                                enabled = !rule.isHardLocked,
                                onCheckedChange = { onToggleRule(rule.id, it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CheetahObsidian,
                                    checkedTrackColor = if (rule.isHardLocked) CheetahCrimson else CheetahEmerald
                                ),
                                modifier = Modifier.testTag("settings_rule_${rule.id}")
                            )
                        }
                    }
                }
            }

            // 4. PERMISSIONS & BACKGROUND HEALTH CENTER
            item {
                SettingsSectionCard(
                    title = "4. مركز الصلاحيات وصحة الخلفية",
                    icon = Icons.Default.CheckCircle,
                    accent = CheetahEmerald
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "جاهزية النظام: ${permissions.readinessScore}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = CheetahCyan
                        )
                        OutlinedButton(
                            onClick = onRefreshPermissions,
                            modifier = Modifier.testTag("settings_refresh_permissions_btn")
                        ) {
                            Text("تحديث الحالة", color = CheetahTextPrimary)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { permissions.readinessScore / 100f },
                        color = CheetahEmerald,
                        trackColor = CheetahDeepNavy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    PermissionCheckRow(
                        icon = Icons.Default.Mic,
                        titleAr = "صلاحية المايكروفون",
                        subtitleAr = "ضرورية لكلمة التنبيه، Voice Match، والاستماع للأوامر.",
                        granted = permissions.hasMicPermission,
                        actionLabel = "تفعيل",
                        testTag = "perm_mic_btn",
                        onClick = onRequestMicPermission
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        PermissionCheckRow(
                            icon = Icons.Default.Notifications,
                            titleAr = "إشعارات خدمة الخلفية (Android 13+)",
                            subtitleAr = "تظهر إشعار واضح ومفتاح كتم أثناء عمل المايك.",
                            granted = permissions.hasNotificationPermission,
                            actionLabel = "السماح",
                            testTag = "perm_notif_btn",
                            onClick = onRequestNotificationPermission
                        )
                    }
                    PermissionCheckRow(
                        icon = Icons.Default.Assistant,
                        titleAr = "المساعد الافتراضي للهاتف",
                        subtitleAr = "تفعيل شيتا بدل مساعد جوجل عند الضغط المطول.",
                        granted = permissions.isDefaultAssistant,
                        actionLabel = "إعداد",
                        testTag = "perm_assistant_btn",
                        onClick = onLaunchDefaultAssistantPicker
                    )
                    PermissionCheckRow(
                        icon = Icons.Default.BatteryChargingFull,
                        titleAr = "استثناء توفير البطارية (Doze)",
                        subtitleAr = "يمنع النظام من إيقاف خدمة كلمة التنبيه في الخلفية.",
                        granted = permissions.isBatteryOptimizationIgnored,
                        actionLabel = "استثناء",
                        testTag = "perm_battery_btn",
                        onClick = onRequestBatteryExemption
                    )
                    PermissionCheckRow(
                        icon = Icons.Default.AccessibilityNew,
                        titleAr = "خدمة إمكانية الوصول (Accessibility)",
                        subtitleAr = "اختيارية لأوامر التمرير والرجوع والضغط على الشاشة.",
                        granted = permissions.isAccessibilityEnabled,
                        actionLabel = "فتح",
                        testTag = "perm_accessibility_btn",
                        onClick = onOpenAccessibilitySettings
                    )
                    PermissionCheckRow(
                        icon = Icons.Default.Layers,
                        titleAr = "الظهور فوق التطبيقات الأخرى",
                        subtitleAr = "اختيارية لعرض شاشة المساعد فوق أي تطبيق مفتوح.",
                        granted = permissions.canDrawOverlays,
                        actionLabel = "منح",
                        testTag = "perm_overlay_btn",
                        onClick = onOpenOverlaySettings
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = CheetahDeepNavy,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CheetahBorder)
                    ) {
                        Text(
                            text = "ملاحظة لأجهزة شاومي / سامسونج / أوبو: لو الخدمة وقفت لوحدها بعد قفل الشاشة، فعّل «التشغيل التلقائي (Autostart)» واقفل تطبيق شيتا في شاشة التطبيقات الحديثة. وفي أندرويد 14+ بعد إعادة التشغيل يظهر إشعار بضغطة واحدة لتشغيل المايك.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CheetahTextSecondary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            // 5. LOCAL ENGINE STATUS (STT / TTS / MFCC-DTW)
            item {
                LocalEngineStatusCard(
                    offlineSttStatus = offlineSttStatus,
                    ttsStatus = ttsStatus,
                    diagnostics = diagnostics
                )
            }

            // 6. WAKE-WORD DIAGNOSTIC CONSOLE
            item {
                WakeDiagnosticConsoleCard(
                    diagnostics = diagnostics,
                    liveMicLevel = liveMicLevel,
                    serviceRunning = serviceRunning,
                    onRunMicDiagnosticTest = onRunMicDiagnosticTest,
                    onMarkFalseActivation = onMarkFalseActivation,
                    onResetCounters = onResetDiagnosticCounters
                )
            }

            // 7. LOCAL MEMORY & PERSONAL SHORTCUTS
            item {
                LocalMemoryAndShortcutsCard(
                    memoryState = memoryState,
                    onAddShortcut = onAddShortcut,
                    onDeleteShortcut = onDeleteShortcut,
                    onDeleteMemoryEntry = onDeleteMemoryEntry,
                    onClearAllMemory = onClearAllMemory
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CheetahCard),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CheetahBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = accent)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = accent
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun PermissionCheckRow(
    icon: ImageVector,
    titleAr: String,
    subtitleAr: String,
    granted: Boolean,
    actionLabel: String,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (granted) CheetahEmerald else CheetahAmber,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titleAr,
                style = MaterialTheme.typography.bodyMedium,
                color = CheetahTextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitleAr,
                style = MaterialTheme.typography.bodySmall,
                color = CheetahTextSecondary
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (granted) {
            Surface(
                color = CheetahEmerald.copy(alpha = 0.16f),
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = "مفعّل",
                    style = MaterialTheme.typography.labelSmall,
                    color = CheetahEmerald,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        } else {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = CheetahCardElevated),
                modifier = Modifier
                    .height(48.dp)
                    .testTag(testTag)
            ) {
                Text(text = actionLabel, color = CheetahCyan)
            }
        }
    }
}
