package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.model.AssistantSettings
import com.example.core.model.EndLineRule
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
    }

    val settingsFlow: Flow<AssistantSettings> = context.cheetahDataStore.data.map { prefs ->
        AssistantSettings(
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
            openDialerInsteadOfDirectCall = prefs[Keys.DIALER_INSTEAD_OF_DIRECT_CALL] ?: true
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
