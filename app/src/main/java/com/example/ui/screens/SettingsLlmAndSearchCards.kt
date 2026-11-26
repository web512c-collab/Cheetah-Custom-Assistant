package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.model.AssistantGender
import com.example.core.model.AssistantSettings
import com.example.core.model.GgufModelMetadata
import com.example.core.model.LocalLlmLoadState
import com.example.core.model.LocalLlmRuntimeStatus
import com.example.core.model.LocalLlmStorageMode
import com.example.search.OnlineSearchManager
import com.example.ui.theme.CheetahAmber
import com.example.ui.theme.CheetahBorder
import com.example.ui.theme.CheetahCard
import com.example.ui.theme.CheetahCardElevated
import com.example.ui.theme.CheetahCrimson
import com.example.ui.theme.CheetahCyan
import com.example.ui.theme.CheetahDeepNavy
import com.example.ui.theme.CheetahEmerald
import com.example.ui.theme.CheetahObsidian
import com.example.ui.theme.CheetahSlate
import com.example.ui.theme.CheetahTextPrimary
import com.example.ui.theme.CheetahTextSecondary
import com.example.voice.TtsStatus
import java.util.Locale

@Composable
fun AssistantIdentityAndVoiceCard(
    settings: AssistantSettings,
    ttsStatus: TtsStatus,
    onUpdateIdentityAndGender: (
        assistantName: String?,
        assistantGender: AssistantGender?,
        voiceGender: AssistantGender?,
        selectedTtsVoiceName: String?
    ) -> Unit,
    onOpenSystemTtsSettings: () -> Unit,
    onTestEgyptianTts: () -> Unit
) {
    var draftAssistantName by remember(settings.assistantName) {
        mutableStateOf(settings.assistantName)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CheetahCard),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CheetahBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Badge, contentDescription = null, tint = CheetahCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "هوية المساعد والجنس النحوي والصوت",
                    style = MaterialTheme.typography.titleMedium,
                    color = CheetahCyan
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draftAssistantName,
                    onValueChange = { draftAssistantName = it },
                    label = { Text("اسم المساعد") },
                    placeholder = { Text("شيتا") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CheetahCyan,
                        unfocusedBorderColor = CheetahBorder
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_assistant_name_input")
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        onUpdateIdentityAndGender(draftAssistantName, null, null, null)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CheetahCyan),
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("settings_save_assistant_name_btn")
                ) {
                    Text("حفظ", color = CheetahObsidian, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "1) صيغة كلام المساعد (المذكر / المؤنث في الردود)",
                style = MaterialTheme.typography.labelLarge,
                color = CheetahTextPrimary
            )
            Text(
                text = "تغيّر الصيغة العربية في الردود (مثلاً: «جاهز/سامعك» أو «جاهزة/سامعاك») بشكل مستقل عن الصوت.",
                style = MaterialTheme.typography.bodySmall,
                color = CheetahTextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GenderChoiceChip(
                    label = "مذكر (جاهز)",
                    selected = settings.assistantGender == AssistantGender.MALE,
                    testTag = "settings_grammar_gender_male",
                    modifier = Modifier.weight(1f),
                    onClick = { onUpdateIdentityAndGender(null, AssistantGender.MALE, null, null) }
                )
                GenderChoiceChip(
                    label = "مؤنث (جاهزة)",
                    selected = settings.assistantGender == AssistantGender.FEMALE,
                    testTag = "settings_grammar_gender_female",
                    modifier = Modifier.weight(1f),
                    onClick = { onUpdateIdentityAndGender(null, AssistantGender.FEMALE, null, null) }
                )
            }

            HorizontalDivider(color = CheetahBorder, modifier = Modifier.padding(vertical = 12.dp))

            Text(
                text = "2) جنس صوت النطق (TTS Voice)",
                style = MaterialTheme.typography.labelLarge,
                color = CheetahTextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GenderChoiceChip(
                    label = "صوت رجالي",
                    selected = settings.voiceGender == AssistantGender.MALE,
                    testTag = "settings_voice_gender_male",
                    modifier = Modifier.weight(1f),
                    onClick = { onUpdateIdentityAndGender(null, null, AssistantGender.MALE, "") }
                )
                GenderChoiceChip(
                    label = "صوت أنثوي",
                    selected = settings.voiceGender == AssistantGender.FEMALE,
                    testTag = "settings_voice_gender_female",
                    modifier = Modifier.weight(1f),
                    onClick = { onUpdateIdentityAndGender(null, null, AssistantGender.FEMALE, "") }
                )
            }

            if (ttsStatus.usedPitchFallback) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = CheetahAmber.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, CheetahAmber.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "ملاحظة: محرك الصوت الحالي لا يحتوي على صوت عربي مطابق لهذا الجنس، لذا تم تفعيل تعديل طبقة الصوت (Pitch) كبديل.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CheetahAmber
                        )
                        TextButton(onClick = onOpenSystemTtsSettings) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp), tint = CheetahCyan)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تثبيت أصوات عربية إضافية من إعدادات TTS", color = CheetahCyan)
                        }
                    }
                }
            }

            if (ttsStatus.availableArabicVoices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "الأصوات العربية المثبتة (${ttsStatus.availableArabicVoices.size}) — اضغط لاختيار صوت محدد:",
                    style = MaterialTheme.typography.bodySmall,
                    color = CheetahTextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isAuto = settings.selectedTtsVoiceName.isBlank()
                    Surface(
                        color = if (isAuto) CheetahCyan.copy(alpha = 0.18f) else CheetahDeepNavy,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (isAuto) CheetahCyan else CheetahBorder),
                        modifier = Modifier.clickable {
                            onUpdateIdentityAndGender(null, null, null, "")
                        }
                    ) {
                        Text(
                            text = "تلقائي",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isAuto) CheetahCyan else CheetahTextPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }

                    ttsStatus.availableArabicVoices.take(8).forEach { v ->
                        val selected = settings.selectedTtsVoiceName == v.name ||
                            (settings.selectedTtsVoiceName.isBlank() && ttsStatus.selectedVoiceName == v.name)
                        val gBadge = when (v.detectedGender) {
                            AssistantGender.FEMALE -> "أنثى"
                            AssistantGender.MALE -> "ذكر"
                            null -> "غير محدد"
                        }
                        Surface(
                            color = if (selected) CheetahCyan.copy(alpha = 0.18f) else CheetahDeepNavy,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (selected) CheetahCyan else CheetahBorder),
                            modifier = Modifier.clickable {
                                onUpdateIdentityAndGender(null, null, null, v.name)
                            }
                        ) {
                            Text(
                                text = "${v.localeTag} • $gBadge • ${v.name.takeLast(14)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) CheetahCyan else CheetahTextPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onTestEgyptianTts,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("settings_identity_test_voice_btn")
            ) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = CheetahCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text("تجربة الصوت والهوية الحالية", color = CheetahCyan)
            }
        }
    }
}

@Composable
private fun GenderChoiceChip(
    label: String,
    selected: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) CheetahCyan.copy(alpha = 0.18f) else CheetahDeepNavy,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .height(48.dp)
            .border(1.dp, if (selected) CheetahCyan else CheetahBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) CheetahCyan else CheetahTextPrimary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun LocalGgufLlmCard(
    settings: AssistantSettings,
    llmStatus: LocalLlmRuntimeStatus,
    onPickGgufModel: () -> Unit,
    onLoadModel: () -> Unit,
    onUnloadModel: () -> Unit,
    onRemoveModel: () -> Unit,
    onCancelGeneration: () -> Unit,
    onUpdateLlmConfig: (contextLen: Int?, maxTokens: Int?, temp: Float?, threads: Int?) -> Unit
) {
    val cfg = settings.localLlmConfig
    val hasModelSelected = cfg.displayName.isNotBlank() || cfg.modelUri.isNotBlank()
    val sizeMb = cfg.fileSizeBytes / (1024.0 * 1024.0)
    val freeGb = llmStatus.freeInternalBytes / (1024.0 * 1024.0 * 1024.0)

    Card(
        colors = CardDefaults.cardColors(containerColor = CheetahCard),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CheetahBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = CheetahAmber)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "الذكاء المحلي الاختياري (GGUF عبر llama.cpp)",
                        style = MaterialTheme.typography.titleMedium,
                        color = CheetahAmber
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "لا يوجد أي نموذج مدمج في APK للحفاظ على حجم التطبيق خفيفاً. اختر ملف .gguf من جهازك للإجابات الحوارية أوفلاين (الأوامر الحساسة تظل محكومة بالقواعد الحتمية والخطوط الحمراء).",
                style = MaterialTheme.typography.bodySmall,
                color = CheetahTextSecondary
            )

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = String.format(
                    Locale.US,
                    "الذاكرة المتاحة: %d MB / %d MB RAM  •  المساحة الفاضية: %.2f GB",
                    llmStatus.availableRamMb,
                    llmStatus.totalRamMb,
                    freeGb
                ),
                style = MaterialTheme.typography.labelSmall,
                color = CheetahCyan
            )

            if (!llmStatus.nativeBackendAvailable) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "تنبيه: مكتبة llama.cpp الأصلية متاحة لأجهزة arm64-v8a فقط.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CheetahCrimson
                )
            }

            if (hasModelSelected) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = CheetahDeepNavy,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CheetahBorder)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = cfg.displayName.ifBlank { "نموذج GGUF" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = CheetahTextPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                color = when (llmStatus.loadState) {
                                    LocalLlmLoadState.LOADED -> CheetahEmerald.copy(alpha = 0.18f)
                                    LocalLlmLoadState.ERROR -> CheetahCrimson.copy(alpha = 0.18f)
                                    else -> CheetahAmber.copy(alpha = 0.18f)
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = llmStatus.loadState.labelAr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (llmStatus.loadState) {
                                        LocalLlmLoadState.LOADED -> CheetahEmerald
                                        LocalLlmLoadState.ERROR -> CheetahCrimson
                                        else -> CheetahAmber
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = String.format(
                                Locale.US,
                                "الحجم: %.0f MB • %s • %s • %s",
                                sizeMb,
                                cfg.architecture.ifBlank { "llama" },
                                cfg.quantization.ifBlank { "GGUF" },
                                cfg.storageMode.labelAr
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = CheetahTextSecondary
                        )
                        if (llmStatus.tokensPerSec > 0f) {
                            Text(
                                text = String.format(Locale.US, "سرعة التوليد الأخيرة: %.1f tok/s", llmStatus.tokensPerSec),
                                style = MaterialTheme.typography.labelSmall,
                                color = CheetahEmerald
                            )
                        }
                    }
                }
            }

            if (llmStatus.loadState == LocalLlmLoadState.LOADING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    color = CheetahAmber,
                    trackColor = CheetahDeepNavy,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                )
            }

            if (!llmStatus.ramWarningAr.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = CheetahAmber.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.WarningAmber, contentDescription = null, tint = CheetahAmber, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(llmStatus.ramWarningAr, style = MaterialTheme.typography.bodySmall, color = CheetahAmber)
                    }
                }
            }

            if (!llmStatus.lastErrorAr.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = llmStatus.lastErrorAr,
                    style = MaterialTheme.typography.bodySmall,
                    color = CheetahCrimson
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onPickGgufModel,
                colors = ButtonDefaults.buttonColors(containerColor = CheetahAmber),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("settings_pick_gguf_btn")
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = CheetahObsidian)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (hasModelSelected) "اختيار ملف .gguf آخر" else "اختيار ملف نموذج .gguf من الجهاز",
                    color = CheetahObsidian,
                    fontWeight = FontWeight.Bold
                )
            }

            if (hasModelSelected) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (llmStatus.loadState == LocalLlmLoadState.LOADED) {
                        OutlinedButton(
                            onClick = onUnloadModel,
                            modifier = Modifier.weight(1f).height(48.dp).testTag("settings_unload_gguf_btn")
                        ) {
                            Text("تفريغ من الرام", color = CheetahAmber)
                        }
                    } else {
                        Button(
                            onClick = onLoadModel,
                            enabled = llmStatus.loadState != LocalLlmLoadState.LOADING,
                            colors = ButtonDefaults.buttonColors(containerColor = CheetahCyan),
                            modifier = Modifier.weight(1f).height(48.dp).testTag("settings_load_gguf_btn")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = CheetahObsidian)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تحميل", color = CheetahObsidian, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = onRemoveModel,
                        modifier = Modifier.weight(1f).height(48.dp).testTag("settings_remove_gguf_btn")
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = CheetahCrimson, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("إزالة", color = CheetahCrimson)
                    }
                }

                if (llmStatus.isGenerating) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onCancelGeneration,
                        colors = ButtonDefaults.buttonColors(containerColor = CheetahCrimson),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, tint = CheetahObsidian)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إيقاف التوليد الآن", color = CheetahObsidian, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = CheetahBorder, modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    text = "طول السياق (Context): ${cfg.contextLength} • الحد الأقصى: ${cfg.maxTokens} كلمة • الحرارة: ${String.format(Locale.US, "%.2f", cfg.temperature)} • الخيوط: ${cfg.cpuThreads}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CheetahTextPrimary
                )
                Slider(
                    value = cfg.contextLength.toFloat(),
                    onValueChange = { onUpdateLlmConfig((it.toInt() / 256) * 256, null, null, null) },
                    valueRange = 512f..2048f,
                    colors = SliderDefaults.colors(thumbColor = CheetahCyan, activeTrackColor = CheetahCyan)
                )
                Slider(
                    value = cfg.maxTokens.toFloat(),
                    onValueChange = { onUpdateLlmConfig(null, (it.toInt() / 32) * 32, null, null) },
                    valueRange = 64f..512f,
                    colors = SliderDefaults.colors(thumbColor = CheetahAmber, activeTrackColor = CheetahAmber)
                )
                Slider(
                    value = cfg.temperature,
                    onValueChange = { onUpdateLlmConfig(null, null, it, null) },
                    valueRange = 0.1f..1.2f,
                    colors = SliderDefaults.colors(thumbColor = CheetahEmerald, activeTrackColor = CheetahEmerald)
                )
                Slider(
                    value = cfg.cpuThreads.toFloat(),
                    onValueChange = { onUpdateLlmConfig(null, null, null, it.toInt()) },
                    valueRange = 1f..8f,
                    colors = SliderDefaults.colors(thumbColor = CheetahCyan, activeTrackColor = CheetahCyan)
                )
            }
        }
    }
}

@Composable
fun KeylessInternetSearchCard(
    settings: AssistantSettings,
    onToggleInternetSearch: (Boolean) -> Unit,
    onSaveCustomSearxngUrl: (String) -> Unit
) {
    var draftSearxngUrl by remember(settings.customSearxngUrl) {
        mutableStateOf(settings.customSearxngUrl)
    }
    val urlInvalid = draftSearxngUrl.isNotBlank() && !OnlineSearchManager.isValidHttpsUrl(draftSearxngUrl)

    Card(
        colors = CardDefaults.cardColors(containerColor = CheetahCard),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CheetahBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        tint = if (settings.internetSearchEnabled) CheetahEmerald else CheetahTextSecondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "السماح بالبحث على الإنترنت (بدون مفتاح API)",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (settings.internetSearchEnabled) CheetahEmerald else CheetahTextPrimary
                        )
                        Text(
                            text = if (settings.internetSearchEnabled) {
                                "مفعّل: يبحث عبر Wikipedia و DuckDuckGo HTTPS ويُلخص النتائج مع المصادر."
                            } else {
                                "مقفول افتراضياً: التطبيق أوفلاين 100% ولا يرسل أي طلبات شبكة."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = CheetahTextSecondary
                        )
                    }
                }
                Switch(
                    checked = settings.internetSearchEnabled,
                    onCheckedChange = onToggleInternetSearch,
                    colors = SwitchDefaults.colors(checkedThumbColor = CheetahObsidian, checkedTrackColor = CheetahEmerald),
                    modifier = Modifier.testTag("settings_internet_search_switch")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                color = CheetahDeepNavy,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, CheetahBorder)
            ) {
                Text(
                    text = "ضمان الخصوصية: عند تفعيل الميزة، يتم إرسال نص سؤال البحث فقط عبر HTTPS بدون أي مفاتيح API، ولا يتم إرسال الصوت أو السجل أو أي بيانات شخصية مطلقاً.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CheetahTextSecondary,
                    modifier = Modifier.padding(10.dp)
                )
            }

            if (settings.internetSearchEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = draftSearxngUrl,
                        onValueChange = { draftSearxngUrl = it },
                        label = { Text("رابط SearXNG اختياري (HTTPS فقط)") },
                        placeholder = { Text("https://searx.example.org") },
                        singleLine = true,
                        isError = urlInvalid,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CheetahCyan,
                            unfocusedBorderColor = CheetahBorder
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_searxng_url_input")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (!urlInvalid) {
                                onSaveCustomSearxngUrl(draftSearxngUrl.trim())
                            }
                        },
                        enabled = !urlInvalid,
                        colors = ButtonDefaults.buttonColors(containerColor = CheetahCyan),
                        modifier = Modifier.height(52.dp)
                    ) {
                        Text("حفظ", color = CheetahObsidian, fontWeight = FontWeight.Bold)
                    }
                }
                if (urlInvalid) {
                    Text(
                        text = "يجب أن يبدأ الرابط بـ https:// فقط.",
                        style = MaterialTheme.typography.labelSmall,
                        color = CheetahCrimson
                    )
                }
            }
        }
    }
}

@Composable
fun GgufStorageModeDialog(
    metadata: GgufModelMetadata,
    freeInternalBytes: Long,
    onSelectMode: (LocalLlmStorageMode) -> Unit,
    onDismiss: () -> Unit
) {
    val sizeMb = metadata.fileSizeBytes / (1024.0 * 1024.0)
    val freeMb = freeInternalBytes / (1024.0 * 1024.0)
    val enoughSpaceForCopy = metadata.fileSizeBytes == 0L || freeInternalBytes >= metadata.fileSizeBytes + 64L * 1024L * 1024L

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CheetahSlate,
        title = { Text("اختر طريقة تشغيل نموذج GGUF", color = CheetahCyan) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "الملف: ${metadata.displayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CheetahTextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "الحجم: %.1f MB • البنية: %s • التكميم: %s\nالمساحة الداخلية الفاضية: %.0f MB",
                        sizeMb,
                        metadata.architecture,
                        metadata.quantization,
                        freeMb
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = CheetahTextSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSelectMode(LocalLlmStorageMode.IN_PLACE_URI) },
                colors = ButtonDefaults.buttonColors(containerColor = CheetahCyan)
            ) {
                Text("تشغيل من مكانه (SAF)", color = CheetahObsidian, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { onSelectMode(LocalLlmStorageMode.COPIED_INTERNAL) },
                    enabled = enoughSpaceForCopy
                ) {
                    Text("نسخ لملفات التطبيق", color = if (enoughSpaceForCopy) CheetahAmber else CheetahTextSecondary)
                }
                TextButton(onClick = onDismiss) {
                    Text("إلغاء", color = CheetahTextSecondary)
                }
            }
        }
    )
}
