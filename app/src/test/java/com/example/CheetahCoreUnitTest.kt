package com.example

import com.example.core.model.AssistantSettings
import com.example.core.model.EgyptianIntent
import com.example.core.model.EndLineRule
import com.example.core.model.GuardrailVerdict
import com.example.core.model.MediaAction
import com.example.core.model.PermissionHealthState
import com.example.core.model.PersonaTone
import com.example.guardrails.CommandGuardrailValidator
import com.example.intelligence.EgyptianColloquialNluParser
import com.example.intelligence.JarvisEgyptianResponder
import com.example.voice.AudioFeatureExtractor
import com.example.voice.EgyptianPhoneticNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheetahCoreUnitTest {

    @Test
    fun wakePhraseMatchesEgyptianPhoneticDrift() {
        assertTrue(EgyptianPhoneticNormalizer.matchesWakePhrase("يا شيطا افتح يوتيوب", "يا شيتا", 0.7f))
        assertTrue(EgyptianPhoneticNormalizer.matchesWakePhrase("hey cheetah وطي الصوت", "يا شيتا", 0.7f))
        assertFalse(EgyptianPhoneticNormalizer.matchesWakePhrase("افتح الواتساب بس", "يا شيتا", 0.7f))
    }

    @Test
    fun parsesEgyptianYouTubeAndVolumeCommands() {
        val (_, yt) = EgyptianColloquialNluParser.parse("يا شيتا افتح يوتيوب وشغل قرآن كريم", "يا شيتا")
        assertTrue(yt is EgyptianIntent.YouTubeSearchPlay)
        assertEquals("قران كريم", (yt as EgyptianIntent.YouTubeSearchPlay).query)

        val (_, vol) = EgyptianColloquialNluParser.parse("وطي الصوت شوية لو سمحت", "يا شيتا")
        assertTrue(vol is EgyptianIntent.MediaControl)
        assertEquals(MediaAction.VOLUME_DOWN, (vol as EgyptianIntent.MediaControl).action)
    }

    @Test
    fun enforcesDeterministicEndLineFinancialBlock() {
        val (_, unsafe) = EgyptianColloquialNluParser.parse("حول 500 جنيه انستا باي", "يا شيتا")
        val verdict = CommandGuardrailValidator.validate(
            intent = unsafe,
            settings = AssistantSettings(),
            permissions = PermissionHealthState(hasMicPermission = true),
            voiceMatchPassed = true
        )
        assertTrue(verdict is GuardrailVerdict.Denied)
        assertEquals(EndLineRule.RULE_BLOCK_FINANCIAL, (verdict as GuardrailVerdict.Denied).ruleId)
    }

    @Test
    fun blocksAccessibilityWhenDisabledInSettings() {
        val (_, scrollIntent) = EgyptianColloquialNluParser.parse("اسكرول لتحت", "يا شيتا")
        val verdict = CommandGuardrailValidator.validate(
            intent = scrollIntent,
            settings = AssistantSettings(allowAccessibilityAutomation = false),
            permissions = PermissionHealthState(isAccessibilityEnabled = true),
            voiceMatchPassed = true
        )
        assertTrue(verdict is GuardrailVerdict.Denied)
    }

    @Test
    fun generatesAuthenticEgyptianJarvisReply() {
        val reply = JarvisEgyptianResponder.formatSuccessReply(
            EgyptianIntent.OpenApp("الواتساب"),
            PersonaTone.JARVIS_FORMAL_EGYPTIAN
        )
        assertTrue(reply.contains("يا فندم"))
        assertTrue(reply.contains("الواتساب"))
    }

    @Test
    fun audioFeatureCosineSimilarityIsBoundedAndSymmetric() {
        val a = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        val b = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        val c = floatArrayOf(-0.5f, 0.5f, -0.5f, 0.5f)
        assertEquals(1.0f, AudioFeatureExtractor.cosineSimilarity(a, b), 0.001f)
        assertEquals(0.0f, AudioFeatureExtractor.cosineSimilarity(a, c), 0.001f)
    }
}
