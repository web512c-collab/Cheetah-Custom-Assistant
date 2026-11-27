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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.core.model.AiLanguagePreference
import com.example.core.model.AiPreset
import com.example.core.model.AssistantSettings
import com.example.core.model.ChatTemplateOverride
import com.example.core.model.LocalLlmConfig
import com.example.core.model.LocalLlmRuntimeStatus
import com.example.core.model.ResponseStyle
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
import java.util.Locale

@Composable
fun SettingsAiControlsCard(
    settings: AssistantSettings,
    llmStatus: LocalLlmRuntimeStatus,
    onApplyPreset: (AiPreset) -> Unit,
    onUpdateExpandedConfig: (
        topP: Float?,
        topK: Int?,
        minP: Float?,
        repeatPenalty: Float?,
        seed: Int?,
        batchSize: Int?,
        chatTemplateOverride: ChatTemplateOverride?,
        customSystemPrompt: String?,
        responseStyle: ResponseStyle?,
        spokenMaxChars: Int?,
        aiLanguagePreference: AiLanguagePreference?,
        stopSequences: List<String>?,
        stripReasoningTags: Boolean?
    ) -> Unit,
    onResetAiDefaults: () -> Unit,
    onExportAiConfig: () -> Unit,
    onImportAiConfig: () -> Unit
) {
    val cfg = settings.localLlmConfig
    var draftPrompt by remember(cfg.customSystemPrompt) { mutableStateOf(cfg.customSystemPrompt) }
    var draftStops by remember(cfg.stopSequences) { mutableStateOf(cfg.stopSequences.joinToString(", ")) }
    var draftSeed by remember(cfg.seed) { mutableStateOf(if (cfg.seed >= 0) cfg.seed.toString() else "42") }

    Card(
        colors = CardDefaults.cardColors(containerColor = CheetahCard),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CheetahBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Tune, contentDescription = null, tint = CheetahCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "إعدادات التوليد والقالب المتقدمة (AI Controls)",
                    style = MaterialTheme.typography.titleMedium,
                    color = CheetahCyan
                )
            }

            Text(
                text = "أوضاع جاهزة (Presets):",
                style = MaterialTheme.typography.labelLarge,
                color = CheetahTextPrimary
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AiPreset.entries.forEach { preset ->
                    val selected = cfg.activePreset == preset
                    Surface(
                        color = if (selected) CheetahCyan.copy(alpha = 0.2f) else CheetahDeepNavy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .border(1.dp, if (selected) CheetahCyan else CheetahBorder, RoundedCornerShape(12.dp))
                            .clickable { onApplyPreset(preset) }
                            .testTag("settings_ai_preset_${preset.name.lowercase()}")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = preset.labelAr,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) CheetahCyan else CheetahTextPrimary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = CheetahBorder)

            Text(
                text = String.format(
                    Locale.US,
                    "معايير العيّنة: Top-P %.2f • Top-K %d • Min-P %.2f • Repeat %.2f • Batch %d",
                    cfg.topP,
                    cfg.topK,
                    cfg.minP,
                    cfg.repeatPenalty,
                    cfg.batchSize
                ),
                style = MaterialTheme.typography.labelSmall,
                color = CheetahAmber
            )

            Slider(
                value = cfg.topP,
                onValueChange = {
                    onUpdateExpandedConfig(it, null, null, null, null, null, null, null, null, null, null, null, null)
                },
                valueRange = 0.10f..1.00f,
                colors = SliderDefaults.colors(thumbColor = CheetahCyan, activeTrackColor = CheetahCyan),
                modifier = Modifier.testTag("settings_ai_top_p_slider")
            )

            Slider(
                value = cfg.topK.toFloat(),
                onValueChange = {
                    onUpdateExpandedConfig(null, it.toInt(), null, null, null, null, null, null, null, null, null, null, null)
                },
                valueRange = 1f..200f,
                colors = SliderDefaults.colors(thumbColor = CheetahAmber, activeTrackColor = CheetahAmber),
                modifier = Modifier.testTag("settings_ai_top_k_slider")
            )

            Slider(
                value = cfg.minP,
                onValueChange = {
                    onUpdateExpandedConfig(null, null, it, null, null, null, null, null, null, null, null, null, null)
                },
                valueRange = 0.00f..0.50f,
                colors = SliderDefaults.colors(thumbColor = CheetahEmerald, activeTrackColor = CheetahEmerald),
                modifier = Modifier.testTag("settings_ai_min_p_slider")
            )

            Slider(
                value = cfg.repeatPenalty,
                onValueChange = {
                    onUpdateExpandedConfig(null, null, null, it, null, null, null, null, null, null, null, null, null)
                },
                valueRange = 0.90f..1.60f,
                colors = SliderDefaults.colors(thumbColor = CheetahCyan, activeTrackColor = CheetahCyan),
                modifier = Modifier.testTag("settings_ai_repeat_penalty_slider")
            )

            Slider(
                value = cfg.batchSize.toFloat(),
                onValueChange = {
                    val snapped = ((it.toInt() + 32) / 64 * 64).coerceIn(64, 512)
                    onUpdateExpandedConfig(null, null, null, null, null, snapped, null, null, null, null, null, null, null)
                },
                valueRange = 64f..512f,
                colors = SliderDefaults.colors(thumbColor = CheetahAmber, activeTrackColor = CheetahAmber),
                modifier = Modifier.testTag("settings_ai_batch_size_slider")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (cfg.seed < 0) "البذرة العشوائية (Seed): عشوائي (-1)" else "البذرة العشوائية (Seed): ثابت (${cfg.seed})",
                        style = MaterialTheme.typography.labelLarge,
                        color = CheetahTextPrimary
                    )
                    Text(
                        text = "أغلق العشوائية لتثبيت Seed لإخراج نفس الرد تماماً.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CheetahTextSecondary
                    )
                }
                Switch(
                    checked = cfg.seed < 0,
                    onCheckedChange = { randomOn ->
                        val nextSeed = if (randomOn) -1 else (draftSeed.toIntOrNull()?.coerceAtLeast(0) ?: 42)
                        onUpdateExpandedConfig(null, null, null, null, nextSeed, null, null, null, null, null, null, null, null)
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = CheetahObsidian, checkedTrackColor = CheetahCyan),
                    modifier = Modifier.testTag("settings_ai_random_seed_switch")
                )
            }

            if (cfg.seed >= 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draftSeed,
                        onValueChange = { draftSeed = it.filter { ch -> ch.isDigit() }.take(9) },
                        label = { Text("Seed رقم ثابت") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CheetahCyan, unfocusedBorderColor = CheetahBorder),
                        modifier = Modifier.weight(1f).testTag("settings_ai_seed_input")
                    )
                    Button(
                        onClick = {
                            val parsed = draftSeed.toIntOrNull()?.coerceAtLeast(0) ?: 42
                            onUpdateExpandedConfig(null, null, null, null, parsed, null, null, null, null, null, null, null, null)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CheetahCyan),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("settings_ai_seed_save_btn")
                    ) {
                        Text("تطبيق", color = CheetahObsidian, fontWeight = FontWeight.Bold)
                    }
                }
            }

            HorizontalDivider(color = CheetahBorder)

            Text(
                text = "قالب المحادثة (Chat Template):",
                style = MaterialTheme.typography.labelLarge,
                color = CheetahTextPrimary
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChatTemplateOverride.entries.forEach { override ->
                    val selected = cfg.chatTemplateOverride == override
                    Surface(
                        color = if (selected) CheetahAmber.copy(alpha = 0.2f) else CheetahDeepNavy,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .border(1.dp, if (selected) CheetahAmber else CheetahBorder, RoundedCornerShape(10.dp))
                            .clickable {
                                onUpdateExpandedConfig(null, null, null, null, null, null, override, null, null, null, null, null, null)
                            }
                            .testTag("settings_ai_template_${override.name.lowercase()}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = override.labelAr,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) CheetahAmber else CheetahTextPrimary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            if (!llmStatus.templateWarningAr.isNullOrBlank()) {
                Surface(
                    color = CheetahAmber.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, CheetahAmber.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.WarningAmber, contentDescription = null, tint = CheetahAmber, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(llmStatus.templateWarningAr, style = MaterialTheme.typography.bodySmall, color = CheetahAmber)
                    }
                }
            }

            HorizontalDivider(color = CheetahBorder)

            Text(
                text = "أسلوب الرد، اللغة، وطول القراءة الصوتية (${cfg.spokenMaxChars} حرف):",
                style = MaterialTheme.typography.labelLarge,
                color = CheetahTextPrimary
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResponseStyle.entries.forEach { style ->
                    val selected = cfg.responseStyle == style
                    Surface(
                        color = if (selected) CheetahCyan.copy(alpha = 0.18f) else CheetahDeepNavy,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .border(1.dp, if (selected) CheetahCyan else CheetahBorder, RoundedCornerShape(10.dp))
                            .clickable {
                                onUpdateExpandedConfig(null, null, null, null, null, null, null, null, style, null, null, null, null)
                            }
                            .testTag("settings_ai_style_${style.name.lowercase()}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = style.labelAr,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) CheetahCyan else CheetahTextPrimary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AiLanguagePreference.entries.forEach { lang ->
                    val selected = cfg.aiLanguagePreference == lang
                    Surface(
                        color = if (selected) CheetahEmerald.copy(alpha = 0.18f) else CheetahDeepNavy,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .border(1.dp, if (selected) CheetahEmerald else CheetahBorder, RoundedCornerShape(10.dp))
                            .clickable {
                                onUpdateExpandedConfig(null, null, null, null, null, null, null, null, null, null, lang, null, null)
                            }
                            .testTag("settings_ai_lang_${lang.name.lowercase()}")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text(
                                text = lang.labelAr,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) CheetahEmerald else CheetahTextPrimary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Slider(
                value = cfg.spokenMaxChars.toFloat(),
                onValueChange = {
                    val snapped = ((it.toInt() + 10) / 20 * 20).coerceIn(80, 600)
                    onUpdateExpandedConfig(null, null, null, null, null, null, null, null, null, snapped, null, null, null)
                },
                valueRange = 80f..600f,
                colors = SliderDefaults.colors(thumbColor = CheetahEmerald, activeTrackColor = CheetahEmerald),
                modifier = Modifier.testTag("settings_ai_spoken_chars_slider")
            )

            HorizontalDivider(color = CheetahBorder)

            OutlinedTextField(
                value = draftPrompt,
                onValueChange = { draftPrompt = it },
                label = { Text("توجيه النظام المخصص (Custom System Prompt)") },
                placeholder = { Text("اتركه فارغاً لاستخدام التوجيه المصري الافتراضي...") },
                minLines = 2,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CheetahCyan, unfocusedBorderColor = CheetahBorder),
                modifier = Modifier.fillMaxWidth().testTag("settings_ai_custom_prompt_input")
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onUpdateExpandedConfig(null, null, null, null, null, null, null, draftPrompt, null, null, null, null, null)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CheetahCyan),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("settings_ai_save_prompt_btn")
                ) {
                    Text("حفظ التوجيه", color = CheetahObsidian, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {
                        draftPrompt = ""
                        onUpdateExpandedConfig(null, null, null, null, null, null, null, "", null, null, null, null, null)
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("settings_ai_restore_prompt_btn")
                ) {
                    Text("استعادة الافتراضي", color = CheetahAmber)
                }
            }

            OutlinedTextField(
                value = draftStops,
                onValueChange = { draftStops = it },
                label = { Text("رموز الإيقاف (Stop Sequences مفصولة بفاصلة)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CheetahCyan, unfocusedBorderColor = CheetahBorder),
                modifier = Modifier.fillMaxWidth().testTag("settings_ai_stop_sequences_input")
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = {
                        val parsed = draftStops.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                        onUpdateExpandedConfig(null, null, null, null, null, null, null, null, null, null, null, parsed, null)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("settings_ai_save_stops_btn")
                ) {
                    Text("حفظ رموز الإيقاف", color = CheetahCyan)
                }
                TextButton(
                    onClick = {
                        draftStops = LocalLlmConfig.DEFAULT_STOP_SEQUENCES.joinToString(", ")
                        onUpdateExpandedConfig(
                            null, null, null, null, null, null, null, null, null, null, null,
                            LocalLlmConfig.DEFAULT_STOP_SEQUENCES, null
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("افتراضي", color = CheetahTextSecondary)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("تنظيف وسوم التفكير تلقائياً (<think>)", style = MaterialTheme.typography.bodyMedium, color = CheetahTextPrimary)
                    Text("يفصل التفكير الداخلي عن الرد النهائي المنطوق.", style = MaterialTheme.typography.bodySmall, color = CheetahTextSecondary)
                }
                Switch(
                    checked = cfg.stripReasoningTags,
                    onCheckedChange = {
                        onUpdateExpandedConfig(null, null, null, null, null, null, null, null, null, null, null, null, it)
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = CheetahObsidian, checkedTrackColor = CheetahEmerald),
                    modifier = Modifier.testTag("settings_ai_strip_think_switch")
                )
            }

            HorizontalDivider(color = CheetahBorder)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onExportAiConfig,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("settings_ai_export_json_btn")
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null, tint = CheetahCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تصدير JSON", color = CheetahCyan)
                }
                OutlinedButton(
                    onClick = onImportAiConfig,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("settings_ai_import_json_btn")
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = CheetahEmerald, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("استيراد JSON", color = CheetahEmerald)
                }
            }

            OutlinedButton(
                onClick = onResetAiDefaults,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings_ai_reset_defaults_btn")
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, tint = CheetahCrimson, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("إعادة ضبط إعدادات الذكاء (مع الاحتفاظ بالنموذج)", color = CheetahCrimson)
            }
        }
    }
}
