package com.example.controller

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.voice.EgyptianPhoneticNormalizer

/**
 * Resolves Egyptian Arabic & Arabized English app names (e.g., "الواتساب", "انستا", "يوتيوب")
 * against installed launcher activities declared via <queries> in AndroidManifest.xml.
 */
object PackageResolver {

    private val EGYPTIAN_APP_ALIASES: Map<String, List<String>> = mapOf(
        "com.google.android.youtube" to listOf("يوتيوب", "اليوتيوب", "يوتوب", "youtube"),
        "com.whatsapp" to listOf("واتساب", "الواتساب", "واتس", "الواتس", "whatsapp"),
        "com.whatsapp.w4b" to listOf("واتساب بيزنس", "واتس اعمال"),
        "com.facebook.katana" to listOf("فيسبوك", "الفيسبوك", "فيس", "الفيس", "facebook"),
        "com.instagram.android" to listOf("انستجرام", "انستغرام", "انستا", "instagram"),
        "org.telegram.messenger" to listOf("تليجرام", "التليجرام", "تيليجرام", "telegram"),
        "com.google.android.apps.maps" to listOf("خرائط", "الخرايط", "جوجل مابس", "مابس", "maps"),
        "com.android.chrome" to listOf("كروم", "جوجل كروم", "المتصفح", "chrome"),
        "com.spotify.music" to listOf("سبوتيفاي", "spotify"),
        "com.zhiliaoapp.musically" to listOf("تيك توك", "تيكتوك", "tiktok"),
        "com.android.settings" to listOf("الاعدادات", "الضبط", "اعدادات", "settings")
    )

    fun resolveLaunchIntent(context: Context, appQuery: String): Pair<String?, Intent?> {
        val pm = context.packageManager
        val normQuery = EgyptianPhoneticNormalizer.normalizeForNlu(appQuery)
            .removePrefix("ال")
            .trim()

        // 1. Direct alias match
        for ((pkg, aliases) in EGYPTIAN_APP_ALIASES) {
            val matched = aliases.any { alias ->
                val na = EgyptianPhoneticNormalizer.normalizeForNlu(alias)
                normQuery == na || normQuery == na.removePrefix("ال") || normQuery.contains(na)
            }
            if (matched) {
                pm.getLaunchIntentForPackage(pkg)?.let { return pkg to it }
            }
        }

        // 2. Dynamic scan over installed launcher apps (via <queries> in Manifest)
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        for (info in activities) {
            val label = info.loadLabel(pm).toString()
            val normLabel = EgyptianPhoneticNormalizer.normalizeForNlu(label)
            if (normLabel.isNotEmpty() &&
                (normLabel == normQuery || normLabel.contains(normQuery) || normQuery.contains(normLabel))
            ) {
                val pkg = info.activityInfo.packageName
                pm.getLaunchIntentForPackage(pkg)?.let { return pkg to it }
            }
        }

        // 3. Special system category apps (Camera / Clock / Calculator)
        if (normQuery.contains("كاميرا") || normQuery.contains("camera")) {
            return "camera" to Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        }
        if (normQuery.contains("اعدادات") || normQuery.contains("ضبط") || normQuery.contains("settings")) {
            return "settings" to Intent(android.provider.Settings.ACTION_SETTINGS)
        }

        return null to null
    }
}
