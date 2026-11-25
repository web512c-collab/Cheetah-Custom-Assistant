package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Shortcut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.model.ConversationContextState
import com.example.core.model.WakeDiagnosticSnapshot
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LocalEngineStatusCard(
    offlineSttStatus: OfflineSttStatus,
    ttsStatus: TtsStatus,
    diagnostics: WakeDiagnosticSnapshot
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CheetahCard),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CheetahBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Memory, contentDescription = null, tint = CheetahCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "5. حالة المحركات المحلية (STT / TTS / DSP)",
                    style = MaterialTheme.typography.titleMedium,
                    color = CheetahCyan
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            EngineStatusRow(
                label = "التعرف المحلي (STT ar-EG)",
                value = offlineSttStatus.engineModeLabelAr,
                detail = offlineSttStatus.statusDetailAr,
                ok = offlineSttStatus.isOnDeviceApiAvailable
            )
            HorizontalDivider(color = CheetahBorder, modifier = Modifier.padding(vertical = 8.dp))
            EngineStatusRow(
                label = "صوت الرد الأوفلاين (TTS)",
                value = if (ttsStatus.hasOfflineVoice) "أوفلاين جاهز (${ttsStatus.activeLocaleTag})" else ttsStatus.activeLocaleTag,
                detail = ttsStatus.statusDetailAr,
                ok = ttsStatus.hasEgyptianVoice && ttsStatus.hasOfflineVoice
            )
            HorizontalDivider(color = CheetahBorder, modifier = Modifier.padding(vertical = 8.dp))
            EngineStatusRow(
                label = "محرك كلمة التنبيه المحلي",
                value = diagnostics.modelStatus,
                detail = "نموذج ثنائي مدمج CHKW-v1 لكلمة «يا شيتا» (Int8 عبر MappedByteBuffer) + بصمات MFCC-DTW محلية 16kHz بدون إنترنت.",
                ok = diagnostics.modelStatus.contains("CHKW") || !diagnostics.modelStatus.startsWith("0/")
            )
        }
    }
}

@Composable
private fun EngineStatusRow(
    label: String,
    value: String,
    detail: String,
    ok: Boolean
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = CheetahTextPrimary,
                fontWeight = FontWeight.Bold
            )
            Surface(
                color = (if (ok) CheetahEmerald else CheetahAmber).copy(alpha = 0.16f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ok) CheetahEmerald else CheetahAmber,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = CheetahTextSecondary
        )
    }
}

@Composable
fun WakeDiagnosticConsoleCard(
    diagnostics: WakeDiagnosticSnapshot,
    liveMicLevel: Float,
    serviceRunning: Boolean,
    onRunMicDiagnosticTest: () -> Unit,
    onMarkFalseActivation: () -> Unit,
    onResetCounters: () -> Unit
) {
    val lastWakeStr = diagnostics.lastWakeTimestampMs?.let { ts ->
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts))
    } ?: "لم يتم التشغيل بعد"

    Card(
        colors = CardDefaults.cardColors(containerColor = CheetahCard),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CheetahCyan.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.BugReport, contentDescription = null, tint = CheetahCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "6. وحدة تشخيص كلمة التنبيه (Diagnostic Console)",
                    style = MaterialTheme.typography.titleMedium,
                    color = CheetahCyan
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            DiagnosticKv("حالة AudioRecord", "${diagnostics.audioRecordStatus} (${diagnostics.audioSourceUsed})")
            DiagnosticKv("معدل العينة (Sample Rate)", "${diagnostics.sampleRateHz} Hz • مستوى المايك ${(liveMicLevel * 100).toInt()}%")
            DiagnosticKv("محرك كلمة التنبيه", diagnostics.wakeEngineStatus)
            DiagnosticKv("حالة النماذج", diagnostics.modelStatus)
            DiagnosticKv("عتبة الكشف (Threshold)", "${(diagnostics.detectionThreshold * 100).toInt()}%")
            DiagnosticKv(
                "آخر ثقة DTW",
                diagnostics.lastConfidence?.let { "${(it * 100).toInt()}%" } ?: "—"
            )
            DiagnosticKv(
                "آخر نتيجة Voice Match",
                "${diagnostics.lastVoiceMatchResult} (${diagnostics.lastVoiceMatchScore?.let { "${(it * 100).toInt()}%" } ?: "—"})"
            )
            DiagnosticKv("مستوى الضوضاء (Noise Floor)", String.format(Locale.US, "%.4f RMS", diagnostics.ambientNoiseRms))
            DiagnosticKv("آخر حدث صوتي", diagnostics.lastAudioEvent)
            DiagnosticKv("وقت آخر تنبيه", lastWakeStr)
            DiagnosticKv("حالة الخدمة الخلفية", if (serviceRunning) "تعمل (Foreground)" else "متوقفة")
            DiagnosticKv("عداد التفعيل الخاطئ", "${diagnostics.falseActivationsCount}")

            if (!diagnostics.lastError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = CheetahCrimson.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "آخر خطأ: ${diagnostics.lastError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CheetahCrimson,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onRunMicDiagnosticTest,
                colors = ButtonDefaults.buttonColors(containerColor = CheetahCyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("settings_diag_mic_test_btn")
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = CheetahObsidian)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "اختبار المايك وكلمة التنبيه (5 ثوانٍ بدون فتح شاشة)",
                    color = CheetahObsidian,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onMarkFalseActivation,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("settings_diag_false_btn")
                ) {
                    Text("تسجيل تفعيل خاطئ", color = CheetahAmber)
                }
                OutlinedButton(
                    onClick = onResetCounters,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("settings_diag_reset_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = null,
                        tint = CheetahTextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تصفير العداد", color = CheetahTextPrimary)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticKv(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = CheetahTextSecondary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = CheetahTextPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun LocalMemoryAndShortcutsCard(
    memoryState: ConversationContextState,
    onAddShortcut: (String, String) -> Unit,
    onDeleteShortcut: (String) -> Unit,
    onDeleteMemoryEntry: (Long) -> Unit,
    onClearAllMemory: () -> Unit
) {
    var draftTrigger by remember { mutableStateOf("") }
    var draftCommand by remember { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(containerColor = CheetahCard),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CheetahBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.History, contentDescription = null, tint = CheetahEmerald)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "7. الذاكرة المحلية والاختصارات الشخصية",
                    style = MaterialTheme.typography.titleMedium,
                    color = CheetahEmerald
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "سجل نصي محلي فقط (محمي من النسخ السحابي، ولا يحفظ أي تسجيلات صوتية).",
                style = MaterialTheme.typography.bodySmall,
                color = CheetahTextSecondary
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Shortcut, contentDescription = null, tint = CheetahAmber, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "اختصارات صوتية مخصصة (${memoryState.shortcuts.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = CheetahTextPrimary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = draftTrigger,
                onValueChange = { draftTrigger = it },
                label = { Text("جملتك (مثلاً: وضع التركيز)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CheetahCyan,
                    unfocusedBorderColor = CheetahBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_shortcut_trigger_input")
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = draftCommand,
                onValueChange = { draftCommand = it },
                label = { Text("الأمر التنفيذي (مثلاً: وطي الصوت شوية)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CheetahCyan,
                    unfocusedBorderColor = CheetahBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_shortcut_command_input")
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (draftTrigger.isNotBlank() && draftCommand.isNotBlank()) {
                        onAddShortcut(draftTrigger, draftCommand)
                        draftTrigger = ""
                        draftCommand = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CheetahCardElevated),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("settings_add_shortcut_btn")
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = CheetahCyan)
                Spacer(modifier = Modifier.width(6.dp))
                Text("إضافة اختصار مصري", color = CheetahCyan)
            }

            memoryState.shortcuts.forEach { sc ->
                Surface(
                    color = CheetahDeepNavy,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(sc.triggerPhrase, style = MaterialTheme.typography.bodyMedium, color = CheetahCyan)
                            Text("← ${sc.mappedCommand}", style = MaterialTheme.typography.bodySmall, color = CheetahTextSecondary)
                        }
                        IconButton(onClick = { onDeleteShortcut(sc.id) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "حذف الاختصار", tint = CheetahCrimson)
                        }
                    }
                }
            }

            HorizontalDivider(color = CheetahBorder, modifier = Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "سجل الحوارات (${memoryState.recentEntries.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = CheetahTextPrimary
                )
                if (memoryState.recentEntries.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onClearAllMemory,
                        modifier = Modifier.testTag("settings_clear_memory_btn")
                    ) {
                        Text("مسح الذاكرة كلها", color = CheetahCrimson)
                    }
                }
            }

            if (memoryState.recentEntries.isEmpty()) {
                Text(
                    text = "الذاكرة المحلية فارغة حالياً.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CheetahTextSecondary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                memoryState.recentEntries.take(6).forEach { entry ->
                    Surface(
                        color = CheetahDeepNavy,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.userText, style = MaterialTheme.typography.bodySmall, color = CheetahTextPrimary, fontWeight = FontWeight.Bold)
                                Text(entry.assistantReply, style = MaterialTheme.typography.bodySmall, color = CheetahTextSecondary)
                            }
                            IconButton(onClick = { onDeleteMemoryEntry(entry.id) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "حذف السجل", tint = Color(0xFF94A3B8))
                            }
                        }
                    }
                }
            }
        }
    }
}
