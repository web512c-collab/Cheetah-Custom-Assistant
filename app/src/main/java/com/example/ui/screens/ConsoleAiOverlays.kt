package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.model.InteractionTrace
import com.example.core.model.LocalLlmRuntimeStatus
import com.example.core.model.ProposedAction
import com.example.core.model.SearchConfidence
import com.example.search.OnlineSearchManager
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
fun ThinkingIndicatorBanner(
    llmStatus: LocalLlmRuntimeStatus,
    streamingReplyText: String,
    onCancelGeneration: () -> Unit
) {
    AnimatedVisibility(visible = llmStatus.isGenerating || streamingReplyText.isNotBlank()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            if (llmStatus.isThinking) {
                val sec = llmStatus.thinkingElapsedMs / 1000f
                Surface(
                    color = CheetahDeepNavy,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CheetahAmber.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = "بيفكر",
                            tint = CheetahAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = String.format(Locale.US, "بيفكر محلياً... %.1f ث", sec),
                            style = MaterialTheme.typography.labelMedium,
                            color = CheetahAmber,
                            modifier = Modifier.testTag("console_thinking_timer_text")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (streamingReplyText.isNotBlank()) {
                Text(
                    text = streamingReplyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CheetahCyan
                )
            }

            if (llmStatus.isGenerating) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCancelGeneration,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("console_stop_generation_btn")
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "إيقاف التوليد", tint = CheetahCrimson, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("إيقاف التوليد", color = CheetahCrimson, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AttachedTextActionBanner(
    attachedText: String,
    sourceLabel: String,
    onRunTaskOnAttached: (prompt: String) -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CheetahCard),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CheetahCyan.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, tint = CheetahCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = sourceLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = CheetahCyan
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp).testTag("attached_text_dismiss_btn")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "إلغاء النص المرفق", tint = CheetahTextSecondary)
                }
            }
            Text(
                text = attachedText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = CheetahTextPrimary
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "لخصلي النص ده" to "لخص",
                    "اشرحلي الكلام ده ببساطة" to "اشرح",
                    "ترجملي النص ده للعربي والمصري" to "ترجم",
                    "حلل النص ده وطلع أهم النقاط" to "حلل"
                ).forEachIndexed { idx, (prompt, chipLabel) ->
                    AssistChip(
                        onClick = { onRunTaskOnAttached(prompt) },
                        label = { Text(chipLabel, color = CheetahTextPrimary) },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = CheetahAmber, modifier = Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = CheetahDeepNavy),
                        modifier = Modifier.height(48.dp).testTag("attached_text_chip_$idx")
                    )
                }
            }
        }
    }
}

@Composable
fun ProposedActionConfirmCard(
    action: ProposedAction,
    onConfirm: (ProposedAction) -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CheetahCard),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CheetahAmber)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "إجراء مقترح يتطلب تأكيدك الصريح",
                style = MaterialTheme.typography.labelLarge,
                color = CheetahAmber,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = action.descriptionAr,
                style = MaterialTheme.typography.bodyMedium,
                color = CheetahTextPrimary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onConfirm(action) },
                    colors = ButtonDefaults.buttonColors(containerColor = CheetahEmerald),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("proposed_action_confirm_btn")
                ) {
                    Text("تأكيد وتنفيذ", color = CheetahObsidian, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("proposed_action_dismiss_btn")
                ) {
                    Text("رفض", color = CheetahCrimson)
                }
            }
        }
    }
}

@Composable
fun EnrichedTraceLogCard(
    trace: InteractionTrace,
    assistantName: String,
    showReasoning: Boolean,
    showSources: Boolean,
    hasPendingContinuation: Boolean,
    onContinueSpeech: () -> Unit,
    onOpenModelSettings: () -> Unit
) {
    val context = LocalContext.current
    val accent = if (trace.isBlocked) CheetahCrimson else CheetahEmerald
    var reasoningExpanded by remember(trace.id) { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = CheetahCard),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = if (trace.isBlocked) Icons.Default.Block else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${trace.intentLabel} • ${trace.guardrailStatus}",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val timeStr = if (trace.thinkingElapsedMs != null) {
                    "${trace.timestampFormatted} • ${String.format(Locale.US, "%.1fs", trace.thinkingElapsedMs / 1000f)}"
                } else trace.timestampFormatted
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = CheetahTextSecondary
                )
            }

            if (trace.searchConfidence != null) {
                val confColor = when (trace.searchConfidence) {
                    SearchConfidence.HIGH -> CheetahEmerald
                    SearchConfidence.MEDIUM -> CheetahAmber
                    SearchConfidence.LOW -> CheetahCrimson
                }
                Surface(
                    color = confColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, confColor.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "ثقة التحقق: ${trace.searchConfidence.labelAr}",
                        style = MaterialTheme.typography.labelSmall,
                        color = confColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                text = "أنت: «${trace.rawUtterance}»",
                style = MaterialTheme.typography.bodyMedium,
                color = CheetahTextPrimary,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "$assistantName: ${trace.assistantReplyAr}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (trace.isBlocked) CheetahCrimson else CheetahCyan
            )

            // Collapsible reasoning block (only visible when showReasoning ON)
            if (showReasoning && !trace.reasoningText.isNullOrBlank()) {
                Surface(
                    color = CheetahDeepNavy,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, CheetahBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { reasoningExpanded = !reasoningExpanded }
                        .testTag("trace_reasoning_toggle_${trace.id}")
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "خطوات التفكير الداخلي",
                                style = MaterialTheme.typography.labelSmall,
                                color = CheetahAmber
                            )
                            Icon(
                                imageVector = if (reasoningExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "إظهار أو إخفاء التفكير",
                                tint = CheetahAmber,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (reasoningExpanded) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = trace.reasoningText,
                                style = MaterialTheme.typography.bodySmall,
                                color = CheetahTextSecondary
                            )
                        }
                    }
                }
            }

            // Continuation button for truncated speech
            if (trace.hasContinuation && hasPendingContinuation) {
                OutlinedButton(
                    onClick = onContinueSpeech,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("trace_continue_reading_btn")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "أكمل القراءة", tint = CheetahCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("أكمل القراءة صوتياً", color = CheetahCyan)
                }
            }

            // Model Settings shortcut when local model is missing
            if (trace.suggestModelSettingsShortcut) {
                OutlinedButton(
                    onClick = onOpenModelSettings,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("trace_open_model_settings_btn")
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "إعدادات النموذج المحلي", tint = CheetahAmber, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("فتح إعدادات النموذج المحلي (GGUF)", color = CheetahAmber)
                }
            }

            // Judged or direct sources (only when showSources ON)
            if (showSources && trace.sources.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "المصادر:",
                        style = MaterialTheme.typography.labelSmall,
                        color = CheetahAmber
                    )
                    trace.sources.forEachIndexed { idx, src ->
                        val judgedMeta = trace.judgedSources.firstOrNull { it.source.url == src.url }
                        val numLabel = judgedMeta?.index ?: (idx + 1)
                        val engineLabel = if (src.contributingEngines.size >= 2) {
                            src.contributingEngines.joinToString(" + ")
                        } else {
                            src.engineName.ifBlank { src.provider }
                        }
                        val domainLabel = src.sourceDomain.ifBlank {
                            judgedMeta?.domain?.takeIf { it.isNotBlank() } ?: OnlineSearchManager.extractDomain(src.url)
                        }
                        val datePart = (src.publishedDate ?: judgedMeta?.publishedDate)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { " • $it" }
                            .orEmpty()
                        val consensusBadge = if (src.contributingEngines.size >= 2) " ★تطابق" else ""
                        val metaBadge = if (judgedMeta != null && trace.generatedByLocalLlm) {
                            " [صلة ${judgedMeta.relevanceScore}/10 • ${judgedMeta.verdict.labelAr}]"
                        } else ""

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    if (src.url.startsWith("https://", ignoreCase = true)) {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(src.url))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                    }
                                }
                                .padding(vertical = 4.dp)
                                .testTag("trace_source_item_${numLabel}")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "فتح المصدر",
                                tint = CheetahCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "[$numLabel] [$engineLabel$consensusBadge] ${src.title} ($domainLabel$datePart)$metaBadge",
                                style = MaterialTheme.typography.labelSmall,
                                color = CheetahCyan
                            )
                        }
                    }
                }
            }

            if (trace.normalizedPhonetics.isNotBlank()) {
                Text(
                    text = "Phonetic Key: ${trace.normalizedPhonetics}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CheetahTextSecondary
                )
            }
        }
    }
}
