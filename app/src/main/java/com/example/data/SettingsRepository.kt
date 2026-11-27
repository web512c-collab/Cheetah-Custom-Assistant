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
import com.example.core.model.AiLanguagePreference
import com.example.core.model.AiMode
import com.example.core.model.AiPreset
import com.example.core.model.AssistantGender
import com.example.core.model.AssistantSettings
import com.example.core.model.ChatTemplateFamily
import com.example.core.model.ChatTemplateOverride
import com.example.core.model.ConcreteSearchEngine
import com.example.core.model.EndLineRule
import com.example.core.model.LocalLlmConfig
import com.example.core.model.LocalLlmStorageMode
import com.example.core.model.PerformanceProfile
import com.example.core.model.PersonaTone
import com.example.core.model.ResponseStyle
import com.example.core.model.SearchEngineMode
import com.example.core.model.WakeWordSetupMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.cheetahDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cheetah_assistant_prefs"
)

@Serializable
data class ExportableAiConfigPayload(
    val schemaVersion: Int = 1,
    val contextLength: Int = 1024,
    val maxTokens: Int = 192,
    val temperature: Float = 0.65f,
    val topP: Float = 0.90f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.10f,
    val seed: Int = -1,
    val batchSize: Int = 256,
    val cpuThreads: Int = 4,
    val chatTemplateOverride: String = ChatTemplateOverride.AUTO.name,
    val customSystemPrompt: String = "",
    val responseStyle: String = ResponseStyle.BALANCED.name,
    val spokenMaxChars: Int = 240,
    val aiLanguagePreference: String = AiLanguagePreference.EGYPTIAN_ARABIC.name,
    val stopSequences: List<String> = LocalLlmConfig.DEFAULT_STOP_SEQUENCES,
    val stripReasoningTags: Boolean = true,
    val activePreset: String = AiPreset.BALANCED.name,
    val useAiForGeneral: Boolean = true,
    val aiMode: String = AiMode.BALANCED.name,
    val judgeSearchWithAi: Boolean = true,
    val searchEvalCount: Int = 5,
    val showReasoning: Boolean = false,
    val showSources: Boolean = true,
    val maxThinkingSeconds: Int = 25,
    val performanceProfile: String = PerformanceProfile.BALANCED.name,
    val autoUnloadMinutes: Int = 10
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

        val MIC_FEATURE_ENABLED = booleanPreferencesKey("mic_feature_enabled")
        val NOTIF_FEATURE_ENABLED = booleanPreferencesKey("notif_feature_enabled")
        val BATTERY_FEATURE_ENABLED = booleanPreferencesKey("battery_feature_enabled")
        val ACCESSIBILITY_FEATURE_ENABLED = booleanPreferencesKey("accessibility_feature_enabled")
        val MIC_ASKED_ONCE = booleanPreferencesKey("mic_asked_once")
        val NOTIF_ASKED_ONCE = booleanPreferencesKey("notif_asked_once")

        // Keyless Internet Search (OFF by default)
        val INTERNET_SEARCH_ENABLED = booleanPreferencesKey("internet_search_enabled")
        val SEARCH_ENGINE_MODE = stringPreferencesKey("search_engine_mode")
        val AUTO_SEARCH_ORDER = stringPreferencesKey("auto_search_order")
        val ENABLED_SEARCH_ENGINES = stringPreferencesKey("enabled_search_engines")
        val FETCH_PAGE_EXCERPTS = booleanPreferencesKey("fetch_page_excerpts")
        val CUSTOM_SEARXNG_URL = stringPreferencesKey("custom_searxng_url")
        val TRUSTED_DOMAINS = stringPreferencesKey("trusted_domains")
        val UNTRUSTED_DOMAINS = stringPreferencesKey("untrusted_domains")

        // Local GGUF LLM Config
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
        val LLM_TOP_P = floatPreferencesKey("llm_top_p")
        val LLM_TOP_K = intPreferencesKey("llm_top_k")
        val LLM_MIN_P = floatPreferencesKey("llm_min_p")
        val LLM_REPEAT_PENALTY = floatPreferencesKey("llm_repeat_penalty")
        val LLM_SEED = intPreferencesKey("llm_seed")
        val LLM_BATCH_SIZE = intPreferencesKey("llm_batch_size")
        val LLM_CPU_THREADS = intPreferencesKey("llm_cpu_threads")
        val LLM_CHAT_TEMPLATE_RAW = stringPreferencesKey("llm_chat_template_raw")
        val LLM_CHAT_TEMPLATE_FAMILY = stringPreferencesKey("llm_chat_template_family")
        val LLM_CHAT_TEMPLATE_OVERRIDE = stringPreferencesKey("llm_chat_template_override")
        val LLM_CUSTOM_SYSTEM_PROMPT = stringPreferencesKey("llm_custom_system_prompt")
        val LLM_RESPONSE_STYLE = stringPreferencesKey("llm_response_style")
        val LLM_SPOKEN_MAX_CHARS = intPreferencesKey("llm_spoken_max_chars")
        val LLM_LANGUAGE_PREF = stringPreferencesKey("llm_language_pref")
        val LLM_STOP_SEQUENCES = stringPreferencesKey("llm_stop_sequences")
        val LLM_STRIP_REASONING = booleanPreferencesKey("llm_strip_reasoning")
        val LLM_ACTIVE_PRESET = stringPreferencesKey("llm_active_preset")
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
            micPermissionRequestedOnce = prefs[Keys.MIC_ASKED_ONCE] ?: false,
            notifPermissionRequestedOnce = prefs[Keys.NOTIF_ASKED_ONCE] ?: false,
            internetSearchEnabled = prefs[Keys.INTERNET_SEARCH_ENABLED] ?: false,
            searchEngineMode = prefs[Keys.SEARCH_ENGINE_MODE]?.let {
                runCatching { SearchEngineMode.valueOf(it) }.getOrNull()
            } ?: SearchEngineMode.DUCKDUCKGO,
            autoSearchOrder = prefs[Keys.AUTO_SEARCH_ORDER]?.let { raw ->
                val parsed = raw.split(',').mapNotNull { token ->
                    runCatching { ConcreteSearchEngine.valueOf(token.trim()) }.getOrNull()
                }
                if (parsed.isNotEmpty()) parsed.distinct() else null
            } ?: AssistantSettings.DEFAULT_AUTO_ENGINE_ORDER,
            enabledSearchEngines = prefs[Keys.ENABLED_SEARCH_ENGINES]?.let { raw ->
                val parsed = raw.split(',').mapNotNull { token ->
                    runCatching { ConcreteSearchEngine.valueOf(token.trim()) }.getOrNull()
                }.toSet()
                parsed.ifEmpty { AssistantSettings.DEFAULT_ENABLED_SEARCH_ENGINES }
            } ?: AssistantSettings.DEFAULT_ENABLED_SEARCH_ENGINES,
            fetchPageExcerpts = prefs[Keys.FETCH_PAGE_EXCERPTS] ?: true,
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
                topP = prefs[Keys.LLM_TOP_P] ?: 0.90f,
                topK = prefs[Keys.LLM_TOP_K] ?: 40,
                minP = prefs[Keys.LLM_MIN_P] ?: 0.05f,
                repeatPenalty = prefs[Keys.LLM_REPEAT_PENALTY] ?: 1.10f,
                seed = prefs[Keys.LLM_SEED] ?: -1,
                batchSize = prefs[Keys.LLM_BATCH_SIZE] ?: 256,
                cpuThreads = prefs[Keys.LLM_CPU_THREADS] ?: defaultThreads,
                chatTemplateRaw = prefs[Keys.LLM_CHAT_TEMPLATE_RAW].orEmpty(),
                chatTemplateFamily = prefs[Keys.LLM_CHAT_TEMPLATE_FAMILY]?.let {
                    runCatching { ChatTemplateFamily.valueOf(it) }.getOrNull()
                } ?: ChatTemplateFamily.GENERIC_UNKNOWN,
                chatTemplateOverride = prefs[Keys.LLM_CHAT_TEMPLATE_OVERRIDE]?.let {
                    runCatching { ChatTemplateOverride.valueOf(it) }.getOrNull()
                } ?: ChatTemplateOverride.AUTO,
                customSystemPrompt = prefs[Keys.LLM_CUSTOM_SYSTEM_PROMPT].orEmpty(),
                responseStyle = prefs[Keys.LLM_RESPONSE_STYLE]?.let {
                    runCatching { ResponseStyle.valueOf(it) }.getOrNull()
                } ?: ResponseStyle.BALANCED,
                spokenMaxChars = prefs[Keys.LLM_SPOKEN_MAX_CHARS] ?: 240,
                aiLanguagePreference = prefs[Keys.LLM_LANGUAGE_PREF]?.let {
                    runCatching { AiLanguagePreference.valueOf(it) }.getOrNull()
                } ?: AiLanguagePreference.EGYPTIAN_ARABIC,
                stopSequences = prefs[Keys.LLM_STOP_SEQUENCES]?.let { raw ->
                    decodeStopSequences(raw)
                } ?: LocalLlmConfig.DEFAULT_STOP_SEQUENCES,
                stripReasoningTags = prefs[Keys.LLM_STRIP_REASONING] ?: true,
                activePreset = prefs[Keys.LLM_ACTIVE_PRESET]?.let {
                    runCatching { AiPreset.valueOf(it) }.getOrNull()
                } ?: AiPreset.BALANCED,
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
        micAskedOnce: Boolean? = null,
        notifAskedOnce: Boolean? = null
    ) {
        context.cheetahDataStore.edit { prefs ->
            micEnabled?.let { prefs[Keys.MIC_FEATURE_ENABLED] = it }
            notifEnabled?.let { prefs[Keys.NOTIF_FEATURE_ENABLED] = it }
            batteryEnabled?.let { prefs[Keys.BATTERY_FEATURE_ENABLED] = it }
            accessibilityEnabled?.let { prefs[Keys.ACCESSIBILITY_FEATURE_ENABLED] = it }
            micAskedOnce?.let { prefs[Keys.MIC_ASKED_ONCE] = it }
            notifAskedOnce?.let { prefs[Keys.NOTIF_ASKED_ONCE] = it }
        }
    }

    suspend fun setInternetSearch(
        enabled: Boolean,
        customSearxngUrl: String? = null,
        engineMode: SearchEngineMode? = null,
        autoSearchOrder: List<ConcreteSearchEngine>? = null,
        enabledSearchEngines: Set<ConcreteSearchEngine>? = null,
        fetchPageExcerpts: Boolean? = null
    ) {
        context.cheetahDataStore.edit { prefs ->
            prefs[Keys.INTERNET_SEARCH_ENABLED] = enabled
            if (customSearxngUrl != null) {
                prefs[Keys.CUSTOM_SEARXNG_URL] = customSearxngUrl.trim()
            }
            if (engineMode != null) {
                prefs[Keys.SEARCH_ENGINE_MODE] = engineMode.name
            }
            if (autoSearchOrder != null) {
                val normalized = autoSearchOrder.distinct().ifEmpty { AssistantSettings.DEFAULT_AUTO_ENGINE_ORDER }
                prefs[Keys.AUTO_SEARCH_ORDER] = normalized.joinToString(",") { it.name }
            }
            if (enabledSearchEngines != null) {
                val nonEmpty = enabledSearchEngines.ifEmpty { AssistantSettings.DEFAULT_ENABLED_SEARCH_ENGINES }
                prefs[Keys.ENABLED_SEARCH_ENGINES] = nonEmpty.joinToString(",") { it.name }
            }
            if (fetchPageExcerpts != null) {
                prefs[Keys.FETCH_PAGE_EXCERPTS] = fetchPageExcerpts
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
        val sanitized = sanitizeLocalLlmConfig(config)
        context.cheetahDataStore.edit { prefs ->
            prefs[Keys.LLM_URI] = sanitized.modelUri
            prefs[Keys.LLM_INTERNAL_PATH] = sanitized.internalPath
            prefs[Keys.LLM_DISPLAY_NAME] = sanitized.displayName
            prefs[Keys.LLM_FILE_SIZE] = sanitized.fileSizeBytes
            prefs[Keys.LLM_QUANTIZATION] = sanitized.quantization
            prefs[Keys.LLM_ARCH] = sanitized.architecture
            prefs[Keys.LLM_STORAGE_MODE] = sanitized.storageMode.name
            prefs[Keys.LLM_CONTEXT_LEN] = sanitized.contextLength
            prefs[Keys.LLM_MAX_TOKENS] = sanitized.maxTokens
            prefs[Keys.LLM_TEMPERATURE] = sanitized.temperature
            prefs[Keys.LLM_TOP_P] = sanitized.topP
            prefs[Keys.LLM_TOP_K] = sanitized.topK
            prefs[Keys.LLM_MIN_P] = sanitized.minP
            prefs[Keys.LLM_REPEAT_PENALTY] = sanitized.repeatPenalty
            prefs[Keys.LLM_SEED] = sanitized.seed
            prefs[Keys.LLM_BATCH_SIZE] = sanitized.batchSize
            prefs[Keys.LLM_CPU_THREADS] = sanitized.cpuThreads
            prefs[Keys.LLM_CHAT_TEMPLATE_RAW] = sanitized.chatTemplateRaw.take(4096)
            prefs[Keys.LLM_CHAT_TEMPLATE_FAMILY] = sanitized.chatTemplateFamily.name
            prefs[Keys.LLM_CHAT_TEMPLATE_OVERRIDE] = sanitized.chatTemplateOverride.name
            prefs[Keys.LLM_CUSTOM_SYSTEM_PROMPT] = sanitized.customSystemPrompt
            prefs[Keys.LLM_RESPONSE_STYLE] = sanitized.responseStyle.name
            prefs[Keys.LLM_SPOKEN_MAX_CHARS] = sanitized.spokenMaxChars
            prefs[Keys.LLM_LANGUAGE_PREF] = sanitized.aiLanguagePreference.name
            prefs[Keys.LLM_STOP_SEQUENCES] = encodeStopSequences(sanitized.stopSequences)
            prefs[Keys.LLM_STRIP_REASONING] = sanitized.stripReasoningTags
            prefs[Keys.LLM_ACTIVE_PRESET] = sanitized.activePreset.name
            prefs[Keys.LLM_USE_FOR_GENERAL] = sanitized.useAiForGeneral
            prefs[Keys.LLM_AI_MODE] = sanitized.aiMode.name
            prefs[Keys.LLM_JUDGE_SEARCH] = sanitized.judgeSearchWithAi
            prefs[Keys.LLM_SEARCH_EVAL_COUNT] = sanitized.searchEvalCount
            prefs[Keys.LLM_SHOW_REASONING] = sanitized.showReasoning
            prefs[Keys.LLM_SHOW_SOURCES] = sanitized.showSources
            prefs[Keys.LLM_MAX_THINKING_SEC] = sanitized.maxThinkingSeconds
            prefs[Keys.LLM_PERFORMANCE_PROFILE] = sanitized.performanceProfile.name
            prefs[Keys.LLM_AUTO_UNLOAD_MINUTES] = sanitized.autoUnloadMinutes
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

    companion object {
        const val MAX_CUSTOM_SYSTEM_PROMPT_CHARS = 1200

        private val jsonCodec = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        fun sanitizeSystemPrompt(raw: String): String {
            return raw
                .replace("\u0000", "")
                .trim()
                .take(MAX_CUSTOM_SYSTEM_PROMPT_CHARS)
        }

        fun sanitizeStopSequences(list: List<String>): List<String> {
            return list.map { it.trim() }
                .filter { it.isNotEmpty() && it.length <= 48 }
                .distinct()
                .take(12)
        }

        fun encodeStopSequences(list: List<String>): String {
            return runCatching { jsonCodec.encodeToString(sanitizeStopSequences(list)) }
                .getOrDefault("[]")
        }

        fun decodeStopSequences(raw: String): List<String> {
            if (raw.isBlank()) return emptyList()
            return runCatching {
                sanitizeStopSequences(jsonCodec.decodeFromString<List<String>>(raw))
            }.getOrElse {
                sanitizeStopSequences(raw.split(','))
            }
        }

        fun sanitizeLocalLlmConfig(config: LocalLlmConfig): LocalLlmConfig {
            return config.copy(
                contextLength = config.contextLength.coerceIn(256, 4096),
                maxTokens = config.maxTokens.coerceIn(32, 1024),
                temperature = config.temperature.coerceIn(0.0f, 1.5f),
                topP = config.topP.coerceIn(0.10f, 1.0f),
                topK = config.topK.coerceIn(1, 200),
                minP = config.minP.coerceIn(0.0f, 0.50f),
                repeatPenalty = config.repeatPenalty.coerceIn(0.90f, 1.60f),
                seed = if (config.seed < 0) -1 else config.seed.coerceAtMost(Int.MAX_VALUE),
                batchSize = config.batchSize.coerceIn(32, 1024),
                cpuThreads = config.cpuThreads.coerceIn(1, 8),
                customSystemPrompt = sanitizeSystemPrompt(config.customSystemPrompt),
                spokenMaxChars = config.spokenMaxChars.coerceIn(80, 600),
                stopSequences = sanitizeStopSequences(config.stopSequences),
                searchEvalCount = config.searchEvalCount.coerceIn(2, 10),
                maxThinkingSeconds = config.maxThinkingSeconds.coerceIn(5, 120),
                autoUnloadMinutes = config.autoUnloadMinutes.coerceIn(0, 120)
            )
        }

        fun applyPreset(base: LocalLlmConfig, preset: AiPreset): LocalLlmConfig {
            return when (preset) {
                AiPreset.FAST -> sanitizeLocalLlmConfig(
                    base.copy(
                        activePreset = AiPreset.FAST,
                        aiMode = AiMode.FAST,
                        performanceProfile = PerformanceProfile.BATTERY_SAVER,
                        responseStyle = ResponseStyle.SHORT,
                        temperature = 0.45f,
                        topP = 0.85f,
                        topK = 24,
                        minP = 0.08f,
                        repeatPenalty = 1.08f,
                        maxTokens = 144,
                        contextLength = 768,
                        batchSize = 128,
                        spokenMaxChars = 160,
                        maxThinkingSeconds = 12
                    )
                )
                AiPreset.BALANCED -> sanitizeLocalLlmConfig(
                    base.copy(
                        activePreset = AiPreset.BALANCED,
                        aiMode = AiMode.BALANCED,
                        performanceProfile = PerformanceProfile.BALANCED,
                        responseStyle = ResponseStyle.BALANCED,
                        temperature = 0.65f,
                        topP = 0.90f,
                        topK = 40,
                        minP = 0.05f,
                        repeatPenalty = 1.10f,
                        maxTokens = 192,
                        contextLength = 1024,
                        batchSize = 256,
                        spokenMaxChars = 240,
                        maxThinkingSeconds = 25
                    )
                )
                AiPreset.DEEP -> sanitizeLocalLlmConfig(
                    base.copy(
                        activePreset = AiPreset.DEEP,
                        aiMode = AiMode.DEEP_THINKING,
                        performanceProfile = PerformanceProfile.PERFORMANCE,
                        responseStyle = ResponseStyle.DETAILED,
                        temperature = 0.55f,
                        topP = 0.92f,
                        topK = 50,
                        minP = 0.04f,
                        repeatPenalty = 1.14f,
                        maxTokens = 384,
                        contextLength = 2048,
                        batchSize = 512,
                        spokenMaxChars = 320,
                        maxThinkingSeconds = 45
                    )
                )
                AiPreset.CUSTOM -> sanitizeLocalLlmConfig(base.copy(activePreset = AiPreset.CUSTOM))
            }
        }

        fun resetAiDefaultsPreservingModel(current: LocalLlmConfig): LocalLlmConfig {
            val fresh = LocalLlmConfig()
            return sanitizeLocalLlmConfig(
                fresh.copy(
                    modelUri = current.modelUri,
                    internalPath = current.internalPath,
                    displayName = current.displayName,
                    fileSizeBytes = current.fileSizeBytes,
                    quantization = current.quantization,
                    architecture = current.architecture,
                    storageMode = current.storageMode,
                    chatTemplateRaw = current.chatTemplateRaw,
                    chatTemplateFamily = current.chatTemplateFamily
                )
            )
        }

        fun exportAiConfigToJson(config: LocalLlmConfig): String {
            val c = sanitizeLocalLlmConfig(config)
            val payload = ExportableAiConfigPayload(
                schemaVersion = 1,
                contextLength = c.contextLength,
                maxTokens = c.maxTokens,
                temperature = c.temperature,
                topP = c.topP,
                topK = c.topK,
                minP = c.minP,
                repeatPenalty = c.repeatPenalty,
                seed = c.seed,
                batchSize = c.batchSize,
                cpuThreads = c.cpuThreads,
                chatTemplateOverride = c.chatTemplateOverride.name,
                customSystemPrompt = c.customSystemPrompt,
                responseStyle = c.responseStyle.name,
                spokenMaxChars = c.spokenMaxChars,
                aiLanguagePreference = c.aiLanguagePreference.name,
                stopSequences = c.stopSequences,
                stripReasoningTags = c.stripReasoningTags,
                activePreset = c.activePreset.name,
                useAiForGeneral = c.useAiForGeneral,
                aiMode = c.aiMode.name,
                judgeSearchWithAi = c.judgeSearchWithAi,
                searchEvalCount = c.searchEvalCount,
                showReasoning = c.showReasoning,
                showSources = c.showSources,
                maxThinkingSeconds = c.maxThinkingSeconds,
                performanceProfile = c.performanceProfile.name,
                autoUnloadMinutes = c.autoUnloadMinutes
            )
            return jsonCodec.encodeToString(payload)
        }

        fun importAiConfigFromJson(rawJson: String, current: LocalLlmConfig): Result<LocalLlmConfig> {
            return runCatching {
                require(rawJson.isNotBlank() && rawJson.length <= 32_768) {
                    "ملف الإعدادات فارغ أو أكبر من المسموح."
                }
                val payload = jsonCodec.decodeFromString<ExportableAiConfigPayload>(rawJson)
                require(payload.schemaVersion in 1..5) {
                    "إصدار ملف الإعدادات غير مدعوم (${payload.schemaVersion})."
                }
                sanitizeLocalLlmConfig(
                    current.copy(
                        contextLength = payload.contextLength,
                        maxTokens = payload.maxTokens,
                        temperature = payload.temperature,
                        topP = payload.topP,
                        topK = payload.topK,
                        minP = payload.minP,
                        repeatPenalty = payload.repeatPenalty,
                        seed = payload.seed,
                        batchSize = payload.batchSize,
                        cpuThreads = payload.cpuThreads,
                        chatTemplateOverride = runCatching {
                            ChatTemplateOverride.valueOf(payload.chatTemplateOverride)
                        }.getOrDefault(ChatTemplateOverride.AUTO),
                        customSystemPrompt = payload.customSystemPrompt,
                        responseStyle = runCatching {
                            ResponseStyle.valueOf(payload.responseStyle)
                        }.getOrDefault(ResponseStyle.BALANCED),
                        spokenMaxChars = payload.spokenMaxChars,
                        aiLanguagePreference = runCatching {
                            AiLanguagePreference.valueOf(payload.aiLanguagePreference)
                        }.getOrDefault(AiLanguagePreference.EGYPTIAN_ARABIC),
                        stopSequences = payload.stopSequences,
                        stripReasoningTags = payload.stripReasoningTags,
                        activePreset = runCatching {
                            AiPreset.valueOf(payload.activePreset)
                        }.getOrDefault(AiPreset.CUSTOM),
                        useAiForGeneral = payload.useAiForGeneral,
                        aiMode = runCatching {
                            AiMode.valueOf(payload.aiMode)
                        }.getOrDefault(AiMode.BALANCED),
                        judgeSearchWithAi = payload.judgeSearchWithAi,
                        searchEvalCount = payload.searchEvalCount,
                        showReasoning = payload.showReasoning,
                        showSources = payload.showSources,
                        maxThinkingSeconds = payload.maxThinkingSeconds,
                        performanceProfile = runCatching {
                            PerformanceProfile.valueOf(payload.performanceProfile)
                        }.getOrDefault(PerformanceProfile.BALANCED),
                        autoUnloadMinutes = payload.autoUnloadMinutes
                    )
                )
            }
        }
    }
}
