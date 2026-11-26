package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.model.AiMode
import com.example.core.model.AssistantGender
import com.example.core.model.AssistantSettings
import com.example.core.model.ChatTemplateFamily
import com.example.core.model.EndLineRule
import com.example.core.model.LocalLlmConfig
import com.example.core.model.LocalLlmStorageMode
import com.example.core.model.PerformanceProfile
import com.example.core.model.PersonaTone
import com.example.core.model.WakeWordSetupMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cheetahDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cheetah_assistant_prefs"
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val ASSISTANT_NAME = stringPreferencesKey("assistant_name")
        val ASSISTANT_GENDER = stringPreferencesKey("assistant_gender")
        val VOICE_GENDER = stringPreferencesKey("voice_gender")
        val SELECTED_TTS_VOICE = stringPreferencesKey("selected_tts_voice")
        val WAKE_PHRASE = stringPreferencesKey("wake_phrase")
        val WAKE_MODE = stringPreferencesKey("wake_mode")
        val WAKE_SENSITIVITY = floatPreferencesKey("wake_sensitivity")
        val VOICE_MATCH_ENABLED = booleanPreferencesKey("voice_match_enabled")
        val VOICE_MATCH_THRESHOLD = floatPreferencesKey("voice_match_threshold")
        val VOICE_MATCH_FALLBACK = booleanPreferencesKey("voice_match_fallback")
        val WAKE_ACK_PHRASE = stringPreferencesKey("wake_ack_phrase")
        val PERSONA_TONE = stringPreferencesKey("persona_tone")
        val TTS_RATE = floatPreferencesKey("tts_rate")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val CONTINUOUS_LISTENING = booleanPreferencesKey("continuous_listening")
        val LISTENING_MUTED = booleanPreferencesKey("listening_muted")
        val ALLOW_ACCESSIBILITY = booleanPreferencesKey("allow_accessibility")
        val REQUIRE_VOICE_MATCH_COMMS = booleanPreferencesKey("require_voice_match_comms")
        val DIALER_INSTEAD_OF_DIRECT_CALL = booleanPreferencesKey("dialer_instead_of_direct_call")

        // Bug A3 per-feature toggles & once flags
        val MIC_FEATURE_ENABLED = booleanPreferencesKey("mic_feature_enabled")
        val NOTIF_FEATURE_ENABLED = booleanPreferencesKey("notif_feature_enabled")
        val BATTERY_FEATURE_ENABLED = booleanPreferencesKey("battery_feature_enabled")
        val ACCESSIBILITY_FEATURE_ENABLED = booleanPreferencesKey("accessibility_feature_enabled")
        val OVERLAY_FEATURE_ENABLED = booleanPreferencesKey("overlay_feature_enabled")
        val MIC_ASKED_ONCE = booleanPreferencesKey("mic_asked_once")
        val NOTIF_ASKED_ONCE = booleanPreferencesKey("notif_asked_once")

        // Part D Keyless Internet Search (OFF by default)
        val INTERNET_SEARCH_ENABLED = booleanPreferencesKey("internet_search_enabled")
        val CUSTOM_SEARXNG_URL = stringPreferencesKey("custom_searxng_url")
        val TRUSTED_DOMAINS = stringPreferencesKey("trusted_domains")
        val UNTRUSTED_DOMAINS = stringPreferencesKey("untrusted_domains")

        // Part B & v0.4.0 Local GGUF LLM Config
        val LLM_URI = stringPreferencesKey("llm_uri")
        val LLM_INTERNAL_PATH = stringPreferencesKey("llm_internal_path")
        val LLM_DISPLAY_NAME = stringPreferencesKey("llm_display_name")
        val LLM_FILE_SIZE = longPreferencesKey("llm_file_size")
        val LLM_QUANTIZATION = stringPreferencesKey("llm_quantization")
        val LLM_ARCH = stringPreferencesKey("llm_arch")
        val LLM_STORAGE_MODE = stringPreferencesKey("llm_storage_mode")
        val LLM_CONTEXT_LEN = intPreferencesKey("llm_context_len")
        val LLM_MAX_TOKENS = intPreferencesKey("llm_max_tokens")
        val LLM_TEMPERATURE = floatPreferencesKey("llm_temperature")
        val LLM_CPU_THREADS = intPreferencesKey("llm_cpu_threads")
        val LLM_CHAT_TEMPLATE_RAW = stringPreferencesKey("llm_chat_template_raw")
        val LLM_CHAT_TEMPLATE_FAMILY = stringPreferencesKey("llm_chat_template_family")
        val LLM_USE_FOR_GENERAL = booleanPreferencesKey("llm_use_for_general")
        val LLM_AI_MODE = stringPreferencesKey("llm_ai_mode")
        val LLM_JUDGE_SEARCH = booleanPreferencesKey("llm_judge_search")
        val LLM_SEARCH_EVAL_COUNT = intPreferencesKey("llm_search_eval_count")
        val LLM_SHOW_REASONING = booleanPreferencesKey("llm_show_reasoning")
        val LLM_SHOW_SOURCES = booleanPreferencesKey("llm_show_sources")
        val LLM_MAX_THINKING_SEC = intPreferencesKey("llm_max_thinking_sec")
        val LLM_PERFORMANCE_PROFILE = stringPreferencesKey("llm_performance_profile")
        val LLM_AUTO_UNLOAD_MINUTES = intPreferencesKey("llm_auto_unload_minutes")
    }

    val settingsFlow: Flow<AssistantSettings> = context.cheetahDataStore.data.map { prefs ->
        val defaultThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        AssistantSettings(
            assistantName = prefs[Keys.ASSISTANT_NAME]?.takeIf { it.isNotBlank() } ?: "شيتا",
            assistantGender = prefs[Keys.ASSISTANT_GENDER]?.let {
                runCatching { AssistantGender.valueOf(it) }.getOrNull()
            } ?: AssistantGender.MALE,
            voiceGender = prefs[Keys.VOICE_GENDER]?.let {
                runCatching { AssistantGender.valueOf(it) }.getOrNull()
            } ?: AssistantGender.MALE,
            selectedTtsVoiceName = prefs[Keys.SELECTED_TTS_VOICE].orEmpty(),
            wakePhrase = prefs[Keys.WAKE_PHRASE]?.takeIf { it.isNotBlank() } ?: "يا شيتا",
            wakeSetupMode = prefs[Keys.WAKE_MODE]?.let {
                runCatching { WakeWordSetupMode.valueOf(it) }.getOrNull()
            } ?: WakeWordSetupMode.TEXT_TYPED,
            wakeSensitivity = prefs[Keys.WAKE_SENSITIVITY] ?: 0.65f,
            voiceMatchEnabled = prefs[Keys.VOICE_MATCH_ENABLED] ?: true,
            voiceMatchThreshold = prefs[Keys.VOICE_MATCH_THRESHOLD] ?: 0.68f,
            allowOwnerFallbackOnLowVoiceMatch = prefs[Keys.VOICE_MATCH_FALLBACK] ?: true,
            wakeAckPhrase = prefs[Keys.WAKE_ACK_PHRASE]?.takeIf { it.isNotBlank() } ?: "أيوه، معاك",
            personaTone = prefs[Keys.PERSONA_TONE]?.let {
                runCatching { PersonaTone.valueOf(it) }.getOrNull()
            } ?: PersonaTone.JARVIS_FORMAL_EGYPTIAN,
            ttsSpeechRate = prefs[Keys.TTS_RATE] ?: 1.0f,
            ttsPitch = prefs[Keys.TTS_PITCH] ?: 0.96f,
            continuousListeningEnabled = prefs[Keys.CONTINUOUS_LISTENING] ?: false,
            isListeningMuted = prefs[Keys.LISTENING_MUTED] ?: false,
            allowAccessibilityAutomation = prefs[Keys.ALLOW_ACCESSIBILITY] ?: false,
            requireVoiceMatchForSensitive = prefs[Keys.REQUIRE_VOICE_MATCH_COMMS] ?: true,
            openDialerInsteadOfDirectCall = prefs[Keys.DIALER_INSTEAD_OF_DIRECT_CALL] ?: true,
            micFeatureEnabled = prefs[Keys.MIC_FEATURE_ENABLED] ?: true,
            notificationsFeatureEnabled = prefs[Keys.NOTIF_FEATURE_ENABLED] ?: true,
            batteryExemptionFeatureEnabled = prefs[Keys.BATTERY_FEATURE_ENABLED] ?: false,
            accessibilityFeatureEnabled = prefs[Keys.ACCESSIBILITY_FEATURE_ENABLED] ?: false,
            overlayFeatureEnabled = prefs[Keys.OVERLAY_FEATURE_ENABLED] ?: false,
            micPermissionRequestedOnce = prefs[Keys.MIC_ASKED_ONCE] ?: false,
            notifPermissionRequestedOnce = prefs[Keys.NOTIF_ASKED_ONCE] ?: false,
            internetSearchEnabled = prefs[Keys.INTERNET_SEARCH_ENABLED] ?: false,
            customSearxngUrl = prefs[Keys.CUSTOM_SEARXNG_URL].orEmpty(),
            trustedDomains = prefs[Keys.TRUSTED_DOMAINS]?.let { raw ->
                raw.split(',').map { d -> d.trim().lowercase() }.filter { d -> d.isNotEmpty() }
            } ?: AssistantSettings.DEFAULT_TRUSTED_DOMAINS,
            untrustedDomains = prefs[Keys.UNTRUSTED_DOMAINS]?.let { raw ->
                raw.split(',').map { d -> d.trim().lowercase() }.filter { d -> d.isNotEmpty() }
            } ?: AssistantSettings.DEFAULT_UNTRUSTED_DOMAINS,
            localLlmConfig = LocalLlmConfig(
                modelUri = prefs[Keys.LLM_URI].orEmpty(),
                internalPath = prefs[Keys.LLM_INTERNAL_PATH].orEmpty(),
                displayName = prefs[Keys.LLM_DISPLAY_NAME].orEmpty(),
                fileSizeBytes = prefs[Keys.LLM_FILE_SIZE] ?: 0L,
                quantization = prefs[Keys.LLM_QUANTIZATION].orEmpty(),
                architecture = prefs[Keys.LLM_ARCH].orEmpty(),
                storageMode = prefs[Keys.LLM_STORAGE_MODE]?.let {
                    runCatching { LocalLlmStorageMode.valueOf(it) }.getOrNull()
                } ?: LocalLlmStorageMode.IN_PLACE_URI,
                contextLength = prefs[Keys.LLM_CONTEXT_LEN] ?: 1024,
                maxTokens = prefs[Keys.LLM_MAX_TOKENS] ?: 192,
                temperature = prefs[Keys.LLM_TEMPERATURE] ?: 0.65f,
                cpuThreads = prefs[Keys.LLM_CPU_THREADS] ?: defaultThreads,
                chatTemplateRaw = prefs[Keys.LLM_CHAT_TEMPLATE_RAW].orEmpty(),
                chatTemplateFamily = prefs[Keys.LLM_CHAT_TEMPLATE_FAMILY]?.let {
                    runCatching { ChatTemplateFamily.valueOf(it) }.getOrNull()
                } ?: ChatTemplateFamily.GENERIC_UNKNOWN,
                useAiForGeneral = prefs[Keys.LLM_USE_FOR_GENERAL] ?: true,
                aiMode = prefs[Keys.LLM_AI_MODE]?.let {
                    runCatching { AiMode.valueOf(it) }.getOrNull()
                } ?: AiMode.BALANCED,
                judgeSearchWithAi = prefs[Keys.LLM_JUDGE_SEARCH] ?: true,
                searchEvalCount = prefs[Keys.LLM_SEARCH_EVAL_COUNT] ?: 5,
                showReasoning = prefs[Keys.LLM_SHOW_REASONING] ?: false,
                showSources = prefs[Keys.LLM_SHOW_SOURCES] ?: true,
                maxThinkingSeconds = prefs[Keys.LLM_MAX_THINKING_SEC] ?: 25,
                performanceProfile = prefs[Keys.LLM_PERFORMANCE_PROFILE]?.let {
                    runCatching { PerformanceProfile.valueOf(it) }.getOrNull()
                } ?: PerformanceProfile.BALANCED,
                autoUnloadMinutes = prefs[Keys.LLM_AUTO_UNLOAD_MINUTES] ?: 10
            )
        )
    }

    val rulesFlow: Flow<List<EndLineRule>> = settingsFlow.map { s ->
        EndLineRule.defaultRules().map { rule ->
            when (rule.id) {
                EndLineRule.RULE_REQUIRE_VOICE_MATCH_COMMS ->
                    rule.copy(isEnabled = s.requireVoiceMatchForSensitive)
                EndLineRule.RULE_CONFIRM_CALLS_AND_SMS ->
                    rule.copy(isEnabled = s.openDialerInsteadOfDirectCall)
                EndLineRule.RULE_ACCESSIBILITY_SCREEN_CONTROL ->
                    rule.copy(isEnabled = s.allowAccessibilityAutomation)
                else -> rule
            }
        }
    }

    suspend fun currentSettings(): AssistantSettings = settingsFlow.first()

    suspend fun updateIdentityAndGender(
        assistantName: String? = null,
        assistantGender: AssistantGender? = null,
        voiceGender: AssistantGender? = null,
        selectedTtsVoiceName: String? = null
    ) {
        context.cheetahDataStore.edit { prefs ->
            if (assistantName != null) {
                prefs[Keys.ASSISTANT_NAME] = assistantName.trim().ifEmpty { "شيتا" }
            }
            if (assistantGender != null) {
                prefs[Keys.ASSISTANT_GENDER] = assistantGender.name
            }
            if (voiceGender != null) {
                prefs[Keys.VOICE_GENDER] = voiceGender.name
            }
            if (selectedTtsVoiceName != null) {
                prefs[Keys.SELECTED_TTS_VOICE] = selectedTtsVoiceName
            }
        }
    }

    suspend fun updateWakePhrase(phrase: String, mode: WakeWordSetupMode) {
        val sanitized = phrase.trim().ifEmpty { "يا شيتا" }
        context.cheetahDataStore.edit { prefs ->
            prefs[Keys.WAKE_PHRASE] = sanitized
            prefs[Keys.WAKE_MODE] = mode.name
        }
    }

    suspend fun updateWakeMode(mode: WakeWordSetupMode) {
        context.cheetahDataStore.edit { prefs ->
            prefs[Keys.WAKE_MODE] = mode.name
        }
    }

    suspend fun updateWakeSensitivity(value: Float) {
        context.cheetahDataStore.edit { prefs ->
            prefs[Keys.WAKE_SENSITIVITY] = value.coerceIn(0.2f, 0.95f)
        }
    }

    suspend fun updateVoiceMatch(
        enabled: Boolean,
        threshold: Float? = null,
        allowFallback: Boolean? = null
    ) {
        context.cheetahDataStore.edit { prefs ->
            prefs[Keys.VOICE_MATCH_ENABLED] = enabled
            if (threshold != null) {
                prefs[Keys.VOICE_MATCH_THRESHOLD] = threshold.coerceIn(0.45f, 0.90f)
            }
            if (allowFallback != null) {
                prefs[Keys.VOICE_MATCH_FALLBACK] = allowFallback
            }
        }
    }

    suspend fun updateWakeAckPhrase(phrase: String) {
        val sanitized = phrase.trim().ifEmpty { "أيوه، معاك" }
        context.cheetahDataStore.edit { prefs ->
            prefs[Keys.WAKE_ACK_PHRASE] = sanitized
        }
    }

    suspend fun updatePersonaTone(tone: PersonaTone) {
        context.cheetahDataStore.edit { prefs ->
            prefs[Keys.PERSONA_TONE] = tone.name
        }
    }

    suspend fun updateTtsTuning(rate: Float, pitch: Float) {
        context.cheetahDataStore.edit { prefs ->
            prefs[Keys.TTS_RATE] = rate.coerceIn(0.6f, 1.5f)
            prefs[Keys.TTS_PITCH] = pitch.coerceIn(0.6f, 1.4f)
        }
    }

    suspend fun setContinuousListening(enabled: Boolean) {
        context.cheetahDataStore.edit { prefs ->
            prefs[Keys.CONTINUOUS_LISTENING] = enabled
            if (enabled) prefs[Keys.LISTENING_MUTED] = false
        }
    }

    suspend fun setListeningMuted(muted: Boolean) {
        context.cheetahDataStore.edit { prefs ->
            prefs[Keys.LISTENING_MUTED] = muted
        }
    }

    suspend fun setPermissionFeatureState(
        micEnabled: Boolean? = null,
        notifEnabled: Boolean? = null,
        batteryEnabled: Boolean? = null,
        accessibilityEnabled: Boolean? = null,
        overlayEnabled: Boolean? = null,
        micAskedOnce: Boolean? = null,
        notifAskedOnce: Boolean? = null
    ) {
        context.cheetahDataStore.edit { prefs ->
            micEnabled?.let { prefs[Keys.MIC_FEATURE_ENABLED] = it }
            notifEnabled?.let { prefs[Keys.NOTIF_FEATURE_ENABLED] = it }
            batteryEnabled?.let { prefs[Keys.BATTERY_FEATURE_ENABLED] = it }
            accessibilityEnabled?.let { prefs[Keys.ACCESSIBILITY_FEATURE_ENABLED] = it }
            overlayEnabled?.let { prefs[Keys.OVERLAY_FEATURE_ENABLED] = it }
            micAskedOnce?.let { prefs[Keys.MIC_ASKED_ONCE] = it }
            notifAskedOnce?.let { prefs[Keys.NOTIF_ASKED_ONCE] = it }
        }
    }

    suspend fun setInternetSearch(enabled: Boolean, customSearxngUrl: String? = null) {
        context.cheetahDataStore.edit { prefs ->
            prefs[Keys.INTERNET_SEARCH_ENABLED] = enabled
            if (customSearxngUrl != null) {
                prefs[Keys.CUSTOM_SEARXNG_URL] = customSearxngUrl.trim()
            }
        }
    }

    suspend fun updateDomainLists(trustedDomains: List<String>, untrustedDomains: List<String>) {
        context.cheetahDataStore.edit { prefs ->
            prefs[Keys.TRUSTED_DOMAINS] = trustedDomains.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
            prefs[Keys.UNTRUSTED_DOMAINS] = untrustedDomains.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
        }
    }

    suspend fun updateLocalLlmConfig(config: LocalLlmConfig) {
        context.cheetahDataStore.edit { prefs ->
            prefs[Keys.LLM_URI] = config.modelUri
            prefs[Keys.LLM_INTERNAL_PATH] = config.internalPath
            prefs[Keys.LLM_DISPLAY_NAME] = config.displayName
            prefs[Keys.LLM_FILE_SIZE] = config.fileSizeBytes
            prefs[Keys.LLM_QUANTIZATION] = config.quantization
            prefs[Keys.LLM_ARCH] = config.architecture
            prefs[Keys.LLM_STORAGE_MODE] = config.storageMode.name
            prefs[Keys.LLM_CONTEXT_LEN] = config.contextLength.coerceIn(256, 4096)
            prefs[Keys.LLM_MAX_TOKENS] = config.maxTokens.coerceIn(32, 1024)
            prefs[Keys.LLM_TEMPERATURE] = config.temperature.coerceIn(0.0f, 1.5f)
            prefs[Keys.LLM_CPU_THREADS] = config.cpuThreads.coerceIn(1, 8)
            prefs[Keys.LLM_CHAT_TEMPLATE_RAW] = config.chatTemplateRaw.take(4096)
            prefs[Keys.LLM_CHAT_TEMPLATE_FAMILY] = config.chatTemplateFamily.name
            prefs[Keys.LLM_USE_FOR_GENERAL] = config.useAiForGeneral
            prefs[Keys.LLM_AI_MODE] = config.aiMode.name
            prefs[Keys.LLM_JUDGE_SEARCH] = config.judgeSearchWithAi
            prefs[Keys.LLM_SEARCH_EVAL_COUNT] = config.searchEvalCount.coerceIn(2, 10)
            prefs[Keys.LLM_SHOW_REASONING] = config.showReasoning
            prefs[Keys.LLM_SHOW_SOURCES] = config.showSources
            prefs[Keys.LLM_MAX_THINKING_SEC] = config.maxThinkingSeconds.coerceIn(5, 120)
            prefs[Keys.LLM_PERFORMANCE_PROFILE] = config.performanceProfile.name
            prefs[Keys.LLM_AUTO_UNLOAD_MINUTES] = config.autoUnloadMinutes.coerceIn(0, 120)
        }
    }

    suspend fun clearLocalLlmSelection() {
        context.cheetahDataStore.edit { prefs ->
            prefs.remove(Keys.LLM_URI)
            prefs.remove(Keys.LLM_INTERNAL_PATH)
            prefs.remove(Keys.LLM_DISPLAY_NAME)
            prefs.remove(Keys.LLM_FILE_SIZE)
            prefs.remove(Keys.LLM_QUANTIZATION)
            prefs.remove(Keys.LLM_ARCH)
            prefs.remove(Keys.LLM_CHAT_TEMPLATE_RAW)
            prefs.remove(Keys.LLM_CHAT_TEMPLATE_FAMILY)
        }
    }

    suspend fun toggleConfigurableRule(ruleId: String, enabled: Boolean) {
        context.cheetahDataStore.edit { prefs ->
            when (ruleId) {
                EndLineRule.RULE_REQUIRE_VOICE_MATCH_COMMS ->
                    prefs[Keys.REQUIRE_VOICE_MATCH_COMMS] = enabled
                EndLineRule.RULE_CONFIRM_CALLS_AND_SMS ->
                    prefs[Keys.DIALER_INSTEAD_OF_DIRECT_CALL] = enabled
                EndLineRule.RULE_ACCESSIBILITY_SCREEN_CONTROL ->
                    prefs[Keys.ALLOW_ACCESSIBILITY] = enabled
            }
        }
    }
}
