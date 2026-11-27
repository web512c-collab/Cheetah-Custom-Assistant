package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.core.AppLinks
import com.example.ui.theme.CheetahAmber
import com.example.ui.theme.CheetahBorder
import com.example.ui.theme.CheetahCard
import com.example.ui.theme.CheetahCyan
import com.example.ui.theme.CheetahDeepNavy
import com.example.ui.theme.CheetahEmerald
import com.example.ui.theme.CheetahObsidian
import com.example.ui.theme.CheetahTextPrimary
import com.example.ui.theme.CheetahTextSecondary

@Composable
fun AboutScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    Box(
        modifier = modifier
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
                        text = AppLinks.APP_NAME_AR,
                        style = MaterialTheme.typography.headlineMedium,
                        color = CheetahTextPrimary
                    )
                    Text(
                        text = "${AppLinks.APP_NAME_EN} • v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.labelMedium,
                        color = CheetahCyan
                    )
                }
            }

            item {
                AboutCard(
                    title = "نظرة عامة — About Sheeta",
                    icon = Icons.Default.Info,
                    accent = CheetahCyan
                ) {
                    Text(
                        text = "مساعد شيتا الشخصي هو مساعد ذكي بالعامية المصرية والإنجليزية يعمل على جهازك بالكامل: تنفيذ الأوامر المحددة أولاً، كلمة تنبيه أوفلاين مخصصة، وتشغيل نماذج GGUF محلياً عبر llama.cpp.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CheetahTextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Sheeta Custom Assistant is a zero-API-key native Android assistant supporting Egyptian Colloquial Arabic & English, offline wake-word detection, and user-chosen local GGUF inference via llama.cpp.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CheetahTextSecondary
                    )
                }
            }

            item {
                AboutCard(
                    title = "المطوّر والروابط الرسمية — Developer & GitHub",
                    icon = Icons.Default.Person,
                    accent = CheetahAmber
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Developer / المطوّر",
                            style = MaterialTheme.typography.bodySmall,
                            color = CheetahTextSecondary
                        )
                        Text(
                            text = AppLinks.DEVELOPER_NAME,
                            style = MaterialTheme.typography.titleMedium,
                            color = CheetahTextPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("about_developer_name")
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    AboutLinkRow(
                        title = "GitHub Profile",
                        subtitle = AppLinks.GITHUB_PROFILE_URL,
                        testTag = "about_link_github_profile",
                        onClick = { openUrl(AppLinks.GITHUB_PROFILE_URL) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AboutLinkRow(
                        title = "Sheeta Custom Assistant Repo",
                        subtitle = AppLinks.GITHUB_APP_REPO_URL,
                        testTag = "about_link_app_repo",
                        onClick = { openUrl(AppLinks.GITHUB_APP_REPO_URL) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AboutLinkRow(
                        title = "Sheeta Operations Research Repo",
                        subtitle = AppLinks.GITHUB_OR_REPO_URL,
                        testTag = "about_link_or_repo",
                        onClick = { openUrl(AppLinks.GITHUB_OR_REPO_URL) }
                    )
                }
            }

            item {
                AboutCard(
                    title = "الخصوصية والشبكة — Privacy Disclosure",
                    icon = Icons.Default.VerifiedUser,
                    accent = CheetahEmerald
                ) {
                    Text(
                        text = "• لا يحتوي التطبيق على أي مفاتيح API ولا أي ذكاء اصطناعي سحابي ولا يتتبع المستخدم.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CheetahTextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• حالة البحث على الإنترنت مقفولة افتراضياً. عند تفعيلها يدوياً فقط، يتم إرسال نص استعلام البحث عبر HTTPS إلى المحركات المختارة بدون مفاتيح (DuckDuckGo / Wikipedia / Mojeek / SearXNG المخصص)، وعند تفعيل جلب المقتطفات تُقرأ نصوص مختصرة من الصفحات الأعلى ترتيباً لتقييمها محلياً.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CheetahTextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• No cloud AI APIs, no bundled LLM weights, no hidden telemetry, and no keys. Your GGUF models, voice templates, and chat memory stay on your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CheetahTextSecondary
                    )
                }
            }

            item {
                AboutCard(
                    title = "التراخيص مفتوحة المصدر — Open-Source Notices",
                    icon = Icons.Default.Code,
                    accent = CheetahCyan
                ) {
                    AppLinks.OPEN_SOURCE_NOTICES.forEachIndexed { idx, notice ->
                        if (idx > 0) {
                            HorizontalDivider(
                                color = CheetahBorder,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = notice.component,
                                style = MaterialTheme.typography.titleSmall,
                                color = CheetahTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                color = CheetahCyan.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = notice.license,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CheetahCyan,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = notice.summaryAr,
                            style = MaterialTheme.typography.bodySmall,
                            color = CheetahTextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCard(
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
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun AboutLinkRow(
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        color = CheetahDeepNavy,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, CheetahBorder),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = CheetahTextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = CheetahCyan
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = title,
                tint = CheetahAmber,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
