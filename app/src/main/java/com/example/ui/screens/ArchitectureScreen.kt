package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CheetahAmber
import com.example.ui.theme.CheetahBorder
import com.example.ui.theme.CheetahCard
import com.example.ui.theme.CheetahDeepNavy
import com.example.ui.theme.CheetahEmerald
import com.example.ui.theme.CheetahObsidian
import com.example.ui.theme.CheetahCyan
import com.example.ui.theme.CheetahTextPrimary
import com.example.ui.theme.CheetahTextSecondary
import com.example.voice.TtsStatus

@Composable
fun ArchitectureScreen(
    isOnDeviceSttSupported: Boolean,
    ttsStatus: TtsStatus,
    enrolledTemplatesCount: Int
) {
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
                Column {
                    Text(
                        text = "الهندسة والأداء الأوفلاين",
                        style = MaterialTheme.typography.headlineMedium,
                        color = CheetahTextPrimary
                    )
                    Text(
                        text = "Zero-External-API Blueprint & Android 7.0+ Hardware Telemetry",
                        style = MaterialTheme.typography.labelSmall,
                        color = CheetahCyan
                    )
                }
            }

            item {
                BlueprintCard(
                    title = "إثبات الاستقلالية الكاملة (Zero-API-Key)",
                    icon = Icons.Default.Security,
                    accent = CheetahEmerald
                ) {
                    DiagnosticRow("صلاحية INTERNET في التطبيق", "غير موجودة (0 اتصالات خارجية)", CheetahEmerald)
                    DiagnosticRow("Gemini / Cloud LLM SDKs", "معطّلة ومحذوفة تماماً", CheetahEmerald)
                    DiagnosticRow(
                        "التعرف المحلي على الكلام (STT)",
                        if (isOnDeviceSttSupported) "On-Device متاح (ar-EG)" else "توافق نظامي (Prefer Offline)",
                        if (isOnDeviceSttSupported) CheetahEmerald else CheetahAmber
                    )
                    DiagnosticRow(
                        "محرك النطق المصري (TTS)",
                        ttsStatus.activeLocaleTag,
                        if (ttsStatus.hasEgyptianVoice) CheetahEmerald else CheetahAmber
                    )
                    DiagnosticRow(
                        "بصمات Voice Match المحلية",
                        "$enrolledTemplatesCount عينة محفوظة داخل filesDir",
                        CheetahCyan
                    )
                }
            }

            item {
                BlueprintCard(
                    title = "القاموس المصري المدعوم محلياً (العامية المصرية)",
                    icon = Icons.Default.Translate,
                    accent = CheetahCyan
                ) {
                    val examples = listOf(
                        "يوتيوب وميديا" to "«افتح يوتيوب وشغل قرآن» / «شغل أغنية ...»",
                        "بحث جوجل (Intent)" to "«دورلي على حالة الطقس في جوجل»",
                        "فتح التطبيقات" to "«افتح الواتساب» / «شغل انستا» / «افتح الكاميرا»",
                        "الصوت والميديا" to "«وطي الصوت شوية» / «علي الصوت» / «اكتم الصوت»",
                        "المنبه والتايمر" to "«ظبط منبه الساعة 7 الصبح» / «تايمر ربع ساعة»",
                        "مكالمات ورسائل آمنة" to "«كلم ماما» / «ابعت رسالة لخالد قول له...»",
                        "حالة النظام أوفلاين" to "«الساعة كام والبطارية كام؟»",
                        "شاشة Accessibility" to "«اسكرول لتحت» / «ارجع لورا» / «دوس على موافق»"
                    )
                    examples.forEach { (cat, phrase) ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = cat,
                                style = MaterialTheme.typography.labelMedium,
                                color = CheetahAmber
                            )
                            Text(
                                text = phrase,
                                style = MaterialTheme.typography.bodySmall,
                                color = CheetahTextPrimary
                            )
                        }
                    }
                }
            }

            item {
                BlueprintCard(
                    title = "الميزانية الهندسية والقيود الواقعية (Android 7.0+)",
                    icon = Icons.Default.Memory,
                    accent = CheetahAmber
                ) {
                    Text(
                        text = "• البصمة الحالية (Pure Kotlin DSP + Grammar NLU): ~12 MB APK و < 45 MB RAM في الخلفية — شغالة فوراً على أي جهاز من Android 7.0 (API 24) لحد Android 16.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CheetahTextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• ترقية نماذج أصلية اختيارية (.so / JNI): إضافة محرك Vosk أو Sherpa-ONNX للعامية المصرية يضيف ~45–90 MB للأصول و ~140 MB RAM؛ وإضافة SLM مضغوط (مثل Qwen/Llama Q4 عبر llama.cpp) يحتاج 1.2–2.2 GB RAM ويفضل أجهزة 6GB+ RAM.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CheetahTextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• قيود Android Sandbox: التطبيقات البنكية وشاشات الباسورد محمية بـ FLAG_SECURE ولا يمكن قراءتها. أوامر المكالمات تفتح شاشة الاتصال (ACTION_DIAL) للمراجعة اليدوية بدل الإرسال الصامت.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CheetahTextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun BlueprintCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CheetahCard),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CheetahBorder)
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
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, badgeColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = CheetahTextPrimary,
            modifier = Modifier.weight(1f)
        )
        Surface(
            color = badgeColor.copy(alpha = 0.16f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (badgeColor == CheetahAmber) Icons.Default.WarningAmber else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.height(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
