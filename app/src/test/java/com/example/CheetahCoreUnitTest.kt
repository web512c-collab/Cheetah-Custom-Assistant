package com.example

import com.example.core.model.ActiveAppContext
import com.example.core.model.AssistantSettings
import com.example.core.model.ConversationContextState
import com.example.core.model.EgyptianIntent
import com.example.core.model.EndLineRule
import com.example.core.model.GuardrailVerdict
import com.example.core.model.MediaAction
import com.example.core.model.PermissionHealthState
import com.example.core.model.PersonaTone
import com.example.core.model.PersonalShortcut
import com.example.guardrails.CommandGuardrailValidator
import com.example.intelligence.EgyptianColloquialNluParser
import com.example.intelligence.JarvisEgyptianResponder
import com.example.voice.AudioFeatureExtractor
import com.example.voice.EgyptianPhoneticNormalizer
import com.example.voice.EgyptianTtsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CheetahCoreUnitTest {

    private fun synthesizeSpeechLikeWave(
        durationMs: Int,
        baseHz: Double,
        modHz: Double
    ): ShortArray {
        val sampleRate = AudioFeatureExtractor.SAMPLE_RATE
        val n = (sampleRate * durationMs) / 1000
        return ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val env = 0.65 + 0.35 * sin(2.0 * PI * 3.5 * t)
            val carrier = 0.55 * sin(2.0 * PI * (baseHz + 120.0 * sin(2.0 * PI * modHz * t)) * t) +
                0.35 * sin(2.0 * PI * (baseHz * 2.1) * t)
            ((carrier * env) * 14000.0).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

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
    fun parsesContextualFollowUpsMemoryRecallCloseAndCompounds() {
        val ytContext = ConversationContextState(activeAppContext = ActiveAppContext.YOUTUBE)
        val (_, followUp) = EgyptianColloquialNluParser.parse("دور على ملخص ماتش الأهلي", "يا شيتا", ytContext)
        assertTrue(followUp is EgyptianIntent.YouTubeSearchPlay)
        assertEquals("ملخص ماتش الاهلي", (followUp as EgyptianIntent.YouTubeSearchPlay).query)

        val (_, recall) = EgyptianColloquialNluParser.parse("فاكر كنا بنتكلم في إيه؟", "يا شيتا", ytContext)
        assertTrue(recall is EgyptianIntent.RecallConversationContext)

        val (_, closeApp) = EgyptianColloquialNluParser.parse("اقفل التطبيق ده", "يا شيتا", ytContext)
        assertTrue(closeApp is EgyptianIntent.CloseCurrentApp)

        val (_, compound) = EgyptianColloquialNluParser.parse("وطي الصوت وبعدين افتح واتساب", "يا شيتا", ytContext)
        assertTrue(compound is EgyptianIntent.CompoundCommands)
        assertEquals(2, (compound as EgyptianIntent.CompoundCommands).steps.size)
    }

    @Test
    fun resolvesPersonalShortcutsSafelyAndBlocksUnsafeOnes() {
        val shortcutCtx = ConversationContextState(
            shortcuts = listOf(
                PersonalShortcut("1", "وضع التركيز", "وطي الصوت شوية"),
                PersonalShortcut("2", "اختصار خطر", "حول 500 جنيه انستا باي")
            )
        )
        val (_, safeShortcut) = EgyptianColloquialNluParser.parse("وضع التركيز", "يا شيتا", shortcutCtx)
        assertTrue(safeShortcut is EgyptianIntent.MediaControl)

        val (_, unsafeShortcut) = EgyptianColloquialNluParser.parse("اختصار خطر", "يا شيتا", shortcutCtx)
        assertTrue(unsafeShortcut is EgyptianIntent.BlockedOrUnsafe)
    }

    @Test
    fun enforcesDeterministicEndLineBlocksAndCompoundGuardrail() {
        val (_, unsafe) = EgyptianColloquialNluParser.parse("حول 500 جنيه انستا باي", "يا شيتا")
        val verdict = CommandGuardrailValidator.validate(
            intent = unsafe,
            settings = AssistantSettings(),
            permissions = PermissionHealthState(hasMicPermission = true),
            voiceMatchPassed = true
        )
        assertTrue(verdict is GuardrailVerdict.Denied)
        assertEquals(EndLineRule.RULE_BLOCK_FINANCIAL, (verdict as GuardrailVerdict.Denied).ruleId)

        val (_, unsafeCreds) = EgyptianColloquialNluParser.parse("اقرا الباسورد", "يا شيتا")
        assertTrue(unsafeCreds is EgyptianIntent.BlockedOrUnsafe)
        assertEquals(EndLineRule.RULE_BLOCK_CREDENTIAL_HARVEST, (unsafeCreds as EgyptianIntent.BlockedOrUnsafe).ruleId)

        val (_, compoundWithDenied) = EgyptianColloquialNluParser.parse(
            "وطي الصوت وبعدين اسكرول لتحت",
            "يا شيتا"
        )
        val compoundVerdict = CommandGuardrailValidator.validate(
            intent = compoundWithDenied,
            settings = AssistantSettings(allowAccessibilityAutomation = false),
            permissions = PermissionHealthState(isAccessibilityEnabled = false),
            voiceMatchPassed = true
        )
        assertTrue(compoundVerdict is GuardrailVerdict.Denied)
    }

    @Test
    fun blocksAccessibilityAndCloseAppWhenDisabledInSettings() {
        val (_, scrollIntent) = EgyptianColloquialNluParser.parse("اسكرول لتحت", "يا شيتا")
        val verdict = CommandGuardrailValidator.validate(
            intent = scrollIntent,
            settings = AssistantSettings(allowAccessibilityAutomation = false),
            permissions = PermissionHealthState(isAccessibilityEnabled = true),
            voiceMatchPassed = true
        )
        assertTrue(verdict is GuardrailVerdict.Denied)

        val (_, closeIntent) = EgyptianColloquialNluParser.parse("اقفل التطبيق ده", "يا شيتا")
        val closeVerdict = CommandGuardrailValidator.validate(
            intent = closeIntent,
            settings = AssistantSettings(allowAccessibilityAutomation = false),
            permissions = PermissionHealthState(isAccessibilityEnabled = false),
            voiceMatchPassed = true
        )
        assertTrue(closeVerdict is GuardrailVerdict.Denied)
    }

    @Test
    fun mfccDtwRanksSameTimeWarpedPhraseHigherThanDifferentSignal() {
        val refWave = synthesizeSpeechLikeWave(durationMs = 800, baseHz = 260.0, modHz = 4.0)
        val slightlyFasterSame = synthesizeSpeechLikeWave(durationMs = 680, baseHz = 265.0, modHz = 4.5)
        val unrelatedHighTone = synthesizeSpeechLikeWave(durationMs = 800, baseHz = 2100.0, modHz = 18.0)

        val refMfcc = AudioFeatureExtractor.extractMfccSequence(refWave)
        val fastMfcc = AudioFeatureExtractor.extractMfccSequence(slightlyFasterSame)
        val otherMfcc = AudioFeatureExtractor.extractMfccSequence(unrelatedHighTone)

        assertNotNull(refMfcc)
        assertNotNull(fastMfcc)
        assertNotNull(otherMfcc)

        val selfScore = AudioFeatureExtractor.dtwConfidence(refMfcc!!, refMfcc)
        val warpedScore = AudioFeatureExtractor.dtwConfidence(fastMfcc!!, refMfcc)
        val unrelatedScore = AudioFeatureExtractor.dtwConfidence(otherMfcc!!, refMfcc)

        assertTrue("Expected self DTW >= 0.90, got $selfScore", selfScore >= 0.90f)
        assertTrue("Expected warped ($warpedScore) > unrelated ($unrelatedScore)", warpedScore > unrelatedScore)
    }

    @Test
    fun generatesAuthenticEgyptianJarvisReplyAndNormalizesTtsText() {
        val reply = JarvisEgyptianResponder.formatSuccessReply(
            EgyptianIntent.OpenApp("الواتساب"),
            PersonaTone.JARVIS_FORMAL_EGYPTIAN
        )
        assertTrue(reply.contains("يا فندم"))
        assertTrue(reply.contains("الواتساب"))

        val cleaned = EgyptianTtsManager.prepareForEgyptianSpeech("«الواتساب» | جاهز • J.A.R.V.I.S.")
        assertFalse(cleaned.contains("«"))
        assertFalse(cleaned.contains("|"))
        assertTrue(cleaned.contains("جارفيس"))
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
