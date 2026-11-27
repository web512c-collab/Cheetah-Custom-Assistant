package com.example.core

/**
 * Developer identity, public repository URLs, and open-source notices for Sheeta Custom Assistant.
 */
object AppLinks {
    const val APP_NAME_EN = "Sheeta Custom Assistant"
    const val APP_NAME_AR = "مساعد شيتا الشخصي"

    const val DEVELOPER_NAME = "Mohammad Joseph"

    const val GITHUB_PROFILE_URL = "https://github.com/web512c-collab"
    const val GITHUB_APP_REPO_URL = "https://github.com/web512c-collab/sheeta-custom-assistant"
    const val GITHUB_OR_REPO_URL = "https://github.com/web512c-collab/sheeta-operations-research"

    data class OssNotice(
        val component: String,
        val license: String,
        val summaryAr: String
    )

    val OPEN_SOURCE_NOTICES = listOf(
        OssNotice(
            component = "llama.cpp",
            license = "MIT License",
            summaryAr = "محرك تشغيل نماذج GGUF محلياً بالكامل على المعالج عبر JNI بدون أي خادم."
        ),
        OssNotice(
            component = "Jetpack Compose & AndroidX",
            license = "Apache License 2.0",
            summaryAr = "واجهة Material 3، الملاحة، DataStore، وخدمات AndroidX الأساسية."
        ),
        OssNotice(
            component = "Kotlin, Coroutines & Serialization",
            license = "Apache License 2.0",
            summaryAr = "لغة Kotlin والتزامن الآمن وتسلسل البيانات بدون انعكاس خارجي."
        ),
        OssNotice(
            component = "Material Symbols & Icons",
            license = "Apache License 2.0",
            summaryAr = "مجموعة الأيقونات المتجهة لواجهة التطبيق."
        )
    )
}
