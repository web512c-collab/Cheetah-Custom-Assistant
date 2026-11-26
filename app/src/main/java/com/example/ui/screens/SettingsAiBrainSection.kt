package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.model.AiMode
import com.example.core.model.AssistantSettings
import com.example.core.model.LocalLlmLoadState
import com.example.core.model.LocalLlmRuntimeStatus
import com.example.core.model.PerformanceProfile
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

private val AUTO_UNLOAD_MINUTES_OPTIONS = listOf(0, 5, 10, 20, 30)

/**
 * Part 4 — AI Brain, Thinking Mode, Search Judging & Performance Controls.
 * Kept modular for clarity (< 400 lines). All changes persist immediately to DataStore.
 */
@Composable
fun AiBrainBehaviorCard(
    settings: AssistantSettings,
    llmStatus: LocalLlmRuntimeStatus,
    isBenchmarkRunning: Boolean,
    onUpdateAiBrainConfig: (
        useAiForGeneral: Boolean?,
        aiMode: AiMode?,
        judgeSearchWithAi: Boolean?,
        searchEvalCount: Int?,
        showReasoning: Boolean?,
        showSources: Boolean?,
        maxThinkingSeconds: Int?,
        performanceProfile: PerformanceProfile?,
        autoUnloadMinutes: Int?
    ) -> Unit,
    onUpdateDomainLists: (trusted: List<String>, untrusted: List<String>) -> Unit,
    onRunBenchmark: () -> Unit
) {
    val cfg = settings.localLlmConfig
    val health = llmStatus.deviceHealth
    var draftTrusted by remember(settings.trustedDomains) {
        mutableStateOf(settings.trustedDomains.joinToString(", "))
    }
    var draftUntrusted by remember(settings.untrustedDomains) {
        mutableStateOf(settings.untrustedDomains.joinToString(", "))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CheetahCard),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CheetahBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Psychology, contentDescription = null, tint = CheetahCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "عقل المساعد الذكي وإعدادات التفكير",
                    style = MaterialTheme.typography.titleMedium,
                    color = CheetahCyan
                )
            }

            // Battery + Thermal telemetry pill
            Surface(
                color = CheetahDeepNavy,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CheetahBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BatteryChargingFull,
                            contentDescription = "البطارية",
                            tint = if (health.isBatteryLow) CheetahAmber else CheetahEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${health.batteryPercent}%${if (health.isCharging) " ⚡" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CheetahTextPrimary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Thermostat,
                            contentDescription = "حرارة الجهاز",
                            tint = if (health.isThermalThrottled) CheetahCrimson else CheetahCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "الحرارة: ${health.thermalStatusLabelAr}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (health.isThermalThrottled) CheetahAmber else CheetahTextSecondary
                        )
                    }
                }
            }

            // Chat template badge + warning when detected
            if (cfg.displayName.isNotBlank() || cfg.modelUri.isNotBlank()) {
                Text(
                    text = "قالب الحوار المكتشف: ${cfg.chatTemplateFamily.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (cfg.chatTemplateFamily.isKnown) CheetahEmerald else CheetahAmber
                )
                if (!llmStatus.templateWarningAr.isNullOrBlank()) {
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
                            Text(llmStatus.templateWarningAr, style = MaterialTheme.typography.bodySmall, color = CheetahAmber)
                        }
                    }
                }
            }

            // 1. Use local AI for general questions and tasks
            AiBrainSwitchRow(
                title = "استخدام النموذج المحلي للأسئلة والمهام العامة",
                subtitle = "الأوامر الحتمية للجهاز والخطوط الحمراء تظل تعمل أولاً دائماً.",
                checked = cfg.useAiForGeneral,
                testTag = "settings_use_ai_general_switch",
                onCheckedChange = {
                    onUpdateAiBrainConfig(it, null, null, null, null, null, null, null, null)
                }
            )

            // 2. AI mode: Fast / Balanced / Deep thinking
            Text(
                text = "وضع التفكير (AI Mode)",
                style = MaterialTheme.typography.labelLarge,
                color = CheetahTextPrimary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AiMode.entries.forEach { mode ->
                    val selected = cfg.aiMode == mode
                    BrainChip(
                        label = mode.labelAr,
                        selected = selected,
                        testTag = "settings_ai_mode_${mode.name.lowercase()}",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onUpdateAiBrainConfig(null, mode, null, null, null, null, null, null, null)
                        }
                    )
                }
            }
            Text(
                text = cfg.aiMode.subtitleAr,
                style = MaterialTheme.typography.bodySmall,
                color = CheetahTextSecondary
            )

            HorizontalDivider(color = CheetahBorder)

            // 3. Judge search results with AI + evaluation count + domain lists
            AiBrainSwitchRow(
                title = "تقييم وفلترة نتائج البحث بالذكاء المحلي",
                subtitle = "يقيّم الصلة والموثوقية والحداثة ويكشف التعارض قبل الإجابة.",
                checked = cfg.judgeSearchWithAi,
                testTag = "settings_judge_search_switch",
                onCheckedChange = {
                    onUpdateAiBrainConfig(null, null, it, null, null, null, null, null, null)
                }
            )

            Text(
                text = "عدد نتائج البحث المُقيّمة: ${cfg.searchEvalCount} نتائج",
                style = MaterialTheme.typography.labelSmall,
                color = CheetahTextPrimary
            )
            Slider(
                value = cfg.searchEvalCount.toFloat(),
                onValueChange = {
                    onUpdateAiBrainConfig(null, null, null, it.toInt(), null, null, null, null, null)
                },
                valueRange = 2f..8f,
                colors = SliderDefaults.colors(thumbColor = CheetahEmerald, activeTrackColor = CheetahEmerald),
                modifier = Modifier.testTag("settings_search_eval_count_slider")
            )

            // 4. Show reasoning / thinking & Show sources
            AiBrainSwitchRow(
                title = "إظهار خطوات التفكير (<think>)",
                subtitle = "يعرضها قابلة للطي في سجل القيادة بدون نطقها صوتياً.",
                checked = cfg.showReasoning,
                testTag = "settings_show_reasoning_switch",
                onCheckedChange = {
                    onUpdateAiBrainConfig(null, null, null, null, it, null, null, null, null)
                }
            )

            AiBrainSwitchRow(
                title = "إظهار المصادر ودرجة الثقة",
                subtitle = "يعرض روابط المصادر المرقمة وتقييم الموثوقية تحت الرد.",
                checked = cfg.showSources,
                testTag = "settings_show_sources_switch",
                onCheckedChange = {
                    onUpdateAiBrainConfig(null, null, null, null, null, it, null, null, null)
                }
            )

            HorizontalDivider(color = CheetahBorder)

            // 5. Max thinking time limit (seconds)
            Text(
                text = "الحد الأقصى لوقت التفكير: ${cfg.maxThinkingSeconds} ثانية",
                style = MaterialTheme.typography.labelSmall,
                color = CheetahTextPrimary
            )
            Slider(
                value = cfg.maxThinkingSeconds.toFloat(),
                onValueChange = {
                    onUpdateAiBrainConfig(null, null, null, null, null, null, (it.toInt() / 5) * 5, null, null)
                },
                valueRange = 10f..90f,
                colors = SliderDefaults.colors(thumbColor = CheetahAmber, activeTrackColor = CheetahAmber),
                modifier = Modifier.testTag("settings_max_thinking_seconds_slider")
            )

            // 6. Performance profile
            Text(
                text = "ملف الأداء واستهلاك البطارية",
                style = MaterialTheme.typography.labelLarge,
                color = CheetahTextPrimary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PerformanceProfile.entries.forEach { profile ->
                    val selected = cfg.performanceProfile == profile
                    BrainChip(
                        label = profile.labelAr,
                        selected = selected,
                        testTag = "settings_perf_profile_${profile.name.lowercase()}",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onUpdateAiBrainConfig(null, null, null, null, null, null, null, profile, null)
                        }
                    )
                }
            }

            // 7. Auto-unload model after inactivity
            Text(
                text = "تفريغ النموذج تلقائياً عند الخمول: ${if (cfg.autoUnloadMinutes == 0) "مقفول" else "بعد ${cfg.autoUnloadMinutes} دقيقة"}",
                style = MaterialTheme.typography.labelSmall,
                color = CheetahTextPrimary
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AUTO_UNLOAD_MINUTES_OPTIONS.forEach { mins ->
                    val label = if (mins == 0) "أبداً" else "${mins}د"
                    BrainChip(
                        label = label,
                        selected = cfg.autoUnloadMinutes == mins,
                        testTag = "settings_auto_unload_$mins",
                        onClick = {
                            onUpdateAiBrainConfig(null, null, null, null, null, null, null, null, mins)
                        }
                    )
                }
            }

            HorizontalDivider(color = CheetahBorder)

            // Trusted / Untrusted domains editor
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = CheetahEmerald, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "قوائم النطاقات الموثوقة والمستبعدة (مفصولة بفاصلة)",
                    style = MaterialTheme.typography.labelMedium,
                    color = CheetahTextPrimary
                )
            }
            OutlinedTextField(
                value = draftTrusted,
                onValueChange = { draftTrusted = it },
                label = { Text("نطاقات موثوقة (تُعطى أولوية)") },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CheetahEmerald, unfocusedBorderColor = CheetahBorder),
                modifier = Modifier.fillMaxWidth().testTag("settings_trusted_domains_input")
            )
            OutlinedTextField(
                value = draftUntrusted,
                onValueChange = { draftUntrusted = it },
                label = { Text("نطاقات مستبعدة (تُرفض مبدئياً)") },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CheetahCrimson, unfocusedBorderColor = CheetahBorder),
                modifier = Modifier.fillMaxWidth().testTag("settings_untrusted_domains_input")
            )
            Button(
                onClick = {
                    val tList = draftTrusted.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    val uList = draftUntrusted.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    onUpdateDomainLists(tList, uList)
                },
                colors = ButtonDefaults.buttonColors(containerColor = CheetahDeepNavy),
                border = BorderStroke(1.dp, CheetahCyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("settings_save_domains_btn")
            ) {
                Text("حفظ قوائم النطاقات", color = CheetahCyan, fontWeight = FontWeight.Bold)
            }

            HorizontalDivider(color = CheetahBorder)

            // 8. "Test the model" benchmark button
            Button(
                onClick = onRunBenchmark,
                enabled = !isBenchmarkRunning && (cfg.displayName.isNotBlank() || cfg.modelUri.isNotBlank() || llmStatus.loadState == LocalLlmLoadState.LOADED),
                colors = ButtonDefaults.buttonColors(containerColor = CheetahCyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("settings_test_model_benchmark_btn")
            ) {
                Icon(Icons.Default.Speed, contentDescription = "اختبار سرعة النموذج", tint = CheetahObsidian)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isBenchmarkRunning) "جاري قياس سرعة النموذج..." else "اختبار النموذج المحلي (Test the model)",
                    color = CheetahObsidian,
                    fontWeight = FontWeight.Bold
                )
            }

            llmStatus.lastBenchmark?.let { bench ->
                Surface(
                    color = CheetahDeepNavy,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (bench.success) CheetahEmerald else CheetahCrimson)
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (bench.success) {
                                String.format(
                                    Locale.US,
                                    "نتيجة الاختبار: %.1f tok/s (%d tokens في %.2f ثانية)",
                                    bench.tokensPerSec,
                                    bench.tokensGenerated,
                                    bench.elapsedMs / 1000f
                                )
                            } else {
                                "فشل الاختبار: ${bench.errorAr ?: "غير متاح"}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (bench.success) CheetahEmerald else CheetahCrimson
                        )
                        if (bench.sampleOutput.isNotBlank()) {
                            Text(
                                text = "عينة: ${bench.sampleOutput}",
                                style = MaterialTheme.typography.bodySmall,
                                color = CheetahTextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiBrainSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = CheetahTextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CheetahTextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CheetahObsidian,
                checkedTrackColor = CheetahCyan
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun BrainChip(
    label: String,
    selected: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) CheetahCyan.copy(alpha = 0.2f) else CheetahDeepNavy,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) CheetahCyan else CheetahBorder),
        modifier = modifier
            .height(48.dp)
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
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) CheetahCyan else CheetahTextPrimary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
