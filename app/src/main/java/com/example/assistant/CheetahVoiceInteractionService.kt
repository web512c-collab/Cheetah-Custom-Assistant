package com.example.assistant

import android.service.voice.VoiceInteractionService

/**
 * System Digital Assistant service registered under BIND_VOICE_INTERACTION.
 * Android activates this service when the user selects Cheetah as Default Assistant.
 */
class CheetahVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        // System assistant binding is active and ready for Home long-press / Assist triggers.
    }
}
