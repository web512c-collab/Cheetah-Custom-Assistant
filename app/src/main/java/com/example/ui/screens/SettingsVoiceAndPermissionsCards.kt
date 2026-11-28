package com.example.ui.screens

import android.os.Build
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SecurityUpdateWarning
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CheetahAmber
import com.example.ui.theme.CheetahBorder
import com.example.ui.theme.CheetahCard
import com.example.ui.theme.CheetahCrimson
import com.example.ui.theme.CheetahCyan
import com.example.ui.theme.CheetahDeepNavy
import com.example.ui.theme.CheetahEmerald
import com.example.ui.theme.CheetahObsidian
import com.example.ui.theme.CheetahTextPrimary
import com.example.ui.theme.CheetahTextSecondary
import com.example.ui.viewmodel.WakeTestSessionState
import com.example.voice.OfflineSttStatus
import java.util.Locale

@Composable
fun LocalSttModelSettingsCard(
    offlineSttStatus: OfflineSttStatus,
    isImporting: Boolean,
    importFeedbackAr: String?,
    onPickSttModelZip: () -> Unit,
    onRemoveSttModel: () -> Unit
) {
    val sizeMb = offlineSttStatus.modelSizeBytes / (1024.0 * 1024.0)

    Card(
        colors = CardDefaults.cardColors(containerColor = CheetahCard),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CheetahBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.RecordVoiceOver, contentDescription = null, tint = CheetahCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "نموذج التعرف على الكلام المحلي (Vosk Offline STT)",
                    style = MaterialTheme.typography.titleMedium,
                    color = CheetahCyan
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "لا يعتمد على خدمات جوجل مطلقاً. اختر حزمة نموذج Vosk (.zip) من ذاكرة جهازك (مثلاً vosk-model-ar أو نموذج إنجليزي صغيّر) للتعرف أوفلاين 100%.",
                style = MaterialTheme.typography.bodySmall,
                color = CheetahTextSecondary
            )

            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                color = CheetahDeepNavy,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (offlineSttStatus.hasModelConfigured) CheetahEmerald.copy(alpha = 0.5f) else CheetahAmber.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (offlineSttStatus.hasModelConfigured) {
                                offlineSttStatus.modelName.ifBlank { "نموذج Vosk مثبت" }
                            } else {
                                "لا يوجد نموذج كلام مثبت بعد"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = CheetahTextPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = (if (offlineSttStatus.hasModelConfigured) CheetahEmerald else CheetahAmber).copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = offlineSttStatus.engineModeLabelAr,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (offlineSttStatus.hasModelConfigured) CheetahEmerald else CheetahAmber,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (offlineSttStatus.hasModelConfigured) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = String.format(
                                Locale.US,
                                "الحجم: %.1f MB • اللغة: %s • حالة الذاكرة: %s",
                                sizeMb,
                                offlineSttStatus.modelLanguage,
                                if (offlineSttStatus.isModelLoaded) "محمّل بالرام" else "جاهز عند الاستماع"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = CheetahTextSecondary
                        )
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "الأوامر الكتابية تشتغل دائماً بدون نموذج، أما التحدث الصوتي (Talk) فيحتاج حزمة Vosk (.zip).",
                            style = MaterialTheme.typography.bodySmall,
                            color = CheetahAmber
                        )
                    }
                }
            }

            if (isImporting) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    color = CheetahCyan,
                    trackColor = CheetahDeepNavy,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                )
            }

            if (!importFeedbackAr.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = importFeedbackAr,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (importFeedbackAr.contains("تعذر") || importFeedbackAr.contains("ليس") || importFeedbackAr.contains("أكبر")) {
                        CheetahCrimson
                    } else {
                        CheetahEmerald
                    }
                )
            }

            if (!offlineSttStatus.lastErrorAr.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = offlineSttStatus.lastErrorAr,
                    style = MaterialTheme.typography.bodySmall,
                    color = CheetahCrimson
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPickSttModelZip,
                    enabled = !isImporting,
                    colors = ButtonDefaults.buttonColors(containerColor = CheetahCyan),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("settings_pick_stt_zip_btn")
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = CheetahObsidian)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (offlineSttStatus.hasModelConfigured) "تغيير حزمة (.zip)" else "اختيار نموذج كلام (.zip)",
                        color = CheetahObsidian,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (offlineSttStatus.hasModelConfigured) {
                    OutlinedButton(
                        onClick = onRemoveSttModel,
                        enabled = !isImporting,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("settings_remove_stt_model_btn")
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "حذف النموذج", tint = CheetahCrimson, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("حذف", color = CheetahCrimson)
                    }
                }
            }
        }
    }
}

@Composable
fun AutoRevokePermissionBanner(
    onOpenAutoRevokeSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

    Surface(
        color = CheetahAmber.copy(alpha = 0.14f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, CheetahAmber.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_auto_revoke_tip_banner")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.SecurityUpdateWarning,
                        contentDescription = null,
                        tint = CheetahAmber,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "تنبيه أندرويد 11+ (سحب الصلاحيات التلقائي)",
                        style = MaterialTheme.typography.labelLarge,
                        color = CheetahAmber
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("settings_dismiss_auto_revoke_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "إخفاء التنبيه",
                        tint = CheetahTextSecondary
                    )
                }
            }
            Text(
                text = "قد يقوم النظام بسحب إذن المايكروفون تلقائياً إذا لم يُفتح التطبيق لفترة طويلة. اضغط أدناه لتعطيل «إزالة الأذونات إذا لم يُستخدم التطبيق».",
                style = MaterialTheme.typography.bodySmall,
                color = CheetahTextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenAutoRevokeSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("settings_open_auto_revoke_btn")
            ) {
                Text("تعطيل السحب التلقائي للصلاحيات", color = CheetahCyan)
            }
        }
    }
}

@Composable
fun WakeTenSayTestPanel(
    wakePhrase: String,
    wakeTestState: WakeTestSessionState,
    hasMicPermission: Boolean,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit
) {
    Surface(
        color = CheetahDeepNavy,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (wakeTestState.isActive) CheetahCyan else CheetahBorder)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = CheetahCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "اختبار كلمة التنبيه (10 مرات)",
                        style = MaterialTheme.typography.labelLarge,
                        color = CheetahTextPrimary
                    )
                }
                Text(
                    text = "${wakeTestState.attempts}/${wakeTestState.targetCount} • نجاح: ${wakeTestState.hits}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (wakeTestState.hits > 0) CheetahEmerald else CheetahCyan
                )
            }

            if (wakeTestState.isActive || wakeTestState.attempts > 0) {
                LinearProgressIndicator(
                    progress = { wakeTestState.attempts.toFloat() / wakeTestState.targetCount.coerceAtLeast(1) },
                    color = CheetahEmerald,
                    trackColor = CheetahCard,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                )
                val lastPct = wakeTestState.lastConfidence?.let { "${(it * 100).toInt()}%" } ?: "—"
                val threshPct = "${(wakeTestState.lastThreshold * 100).toInt()}%"
                Text(
                    text = "آخر نتيجة DTW: $lastPct  (العتبة المعايرة: $threshPct) ${if (wakeTestState.lastSource.isNotBlank()) "• ${wakeTestState.lastSource}" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CheetahTextSecondary
                )
            }

            if (!wakeTestState.summaryAr.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (wakeTestState.isActive) CheetahCyan else CheetahEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = wakeTestState.summaryAr,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (wakeTestState.isActive) CheetahCyan else CheetahEmerald
                    )
                }
            }

            if (wakeTestState.isActive) {
                OutlinedButton(
                    onClick = onStopTest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("settings_stop_wake_10test_btn")
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = CheetahCrimson)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("إيقاف اختبار الـ 10 مرات", color = CheetahCrimson)
                }
            } else {
                OutlinedButton(
                    onClick = onStartTest,
                    enabled = hasMicPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("settings_start_wake_10test_btn")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = CheetahCyan)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (hasMicPermission) "اختبار «$wakePhrase» (10 مرات)" else "يلزم إذن المايك لاختبار الـ 10 مرات",
                        color = if (hasMicPermission) CheetahCyan else CheetahTextSecondary
                    )
                }
            }
        }
    }
}
