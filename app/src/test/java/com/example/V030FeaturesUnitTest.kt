package com.example

import com.example.core.model.AssistantGender
import com.example.core.model.AssistantSettings
import com.example.core.model.EgyptianIntent
import com.example.core.model.PersonaTone
import com.example.intelligence.EgyptianColloquialNluParser
import com.example.intelligence.JarvisEgyptianResponder
import com.example.llm.GgufHeaderParser
import com.example.search.OnlineSearchManager
import com.example.voice.EgyptianTtsManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class V030FeaturesUnitTest {

    @Before
    fun clearSearchCache() {
        OnlineSearchManager.clearCache()
    }

    // --- Part B: GGUF Header Parsing & RAM Safety ---

    @Test
    fun `gguf parser parses valid v3 header and architecture metadata`() {
        val bytes = buildMinimalGgufHeader(
            version = 3,
            tensorCount = 120L,
            architecture = "qwen2",
            name = "Qwen2.5-0.5B-Instruct"
        )
        val res = GgufHeaderParser.parseHeader(
            ByteArrayInputStream(bytes),
            fileNameHint = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        )
        assertTrue(res.isSuccess)
        val parsed = res.getOrThrow()
        assertEquals(3, parsed.version)
        assertEquals(120L, parsed.tensorCount)
        assertEquals("qwen2", parsed.architecture)
        assertEquals("Qwen2.5-0.5B-Instruct", parsed.modelName)
        assertEquals("Q4_K_M", parsed.quantizationGuess)
    }

    @Test
    fun `gguf parser rejects invalid magic header`() {
        val res = GgufHeaderParser.parseHeader(
            ByteArrayInputStream("NOT_GGUF_1234567890".toByteArray()),
            fileNameHint = "fake.gguf"
        )
        assertTrue(res.isFailure)
    }

    @Test
    fun `ram safety estimator rejects oversized model and warns on tight ram`() {
        val tooBig = GgufHeaderParser.evaluateRamSafety(
            fileSizeBytes = 3_500L * 1024L * 1024L,
            availableRamMb = 1_800L,
            totalRamMb = 4_096L,
            contextLength = 1024
        )
        assertFalse(tooBig.isSafeToLoad)
        assertNotNull(tooBig.warningMessageAr)

        val safe = GgufHeaderParser.evaluateRamSafety(
            fileSizeBytes = 380L * 1024L * 1024L,
            availableRamMb = 2_400L,
            totalRamMb = 6_144L,
            contextLength = 1024
        )
        assertTrue(safe.isSafeToLoad)
        assertNull(safe.warningMessageAr)
    }

    // --- Part C: Voice Gender & Grammatical Gender Independence ---

    @Test
    fun `responder uses female vs male egyptian grammar independently of tts voice`() {
        val maleGreeting = JarvisEgyptianResponder.formatSuccessReply(
            intent = EgyptianIntent.SmallTalkOrPersona("greeting"),
            tone = PersonaTone.JARVIS_FORMAL_EGYPTIAN,
            assistantName = "شيتا",
            gender = AssistantGender.MALE
        )
        val femaleGreeting = JarvisEgyptianResponder.formatSuccessReply(
            intent = EgyptianIntent.SmallTalkOrPersona("greeting"),
            tone = PersonaTone.JARVIS_FORMAL_EGYPTIAN,
            assistantName = "ليلى",
            gender = AssistantGender.FEMALE
        )

        assertTrue(maleGreeting.contains("جاهز") && maleGreeting.contains("شغالة").not())
        assertTrue(femaleGreeting.contains("ليلى") && femaleGreeting.contains("جاهزة") && femaleGreeting.contains("شغالة"))

        val femalePrompt = JarvisEgyptianResponder.buildLocalLlmSystemPrompt(
            AssistantSettings(assistantName = "نور", assistantGender = AssistantGender.FEMALE)
        )
        assertTrue(femalePrompt.contains("مساعدة شخصية") && femalePrompt.contains("المؤنث"))
    }

    @Test
    fun `tts voice gender heuristic and pitch fallback behave honestly`() {
        assertEquals(
            AssistantGender.FEMALE,
            EgyptianTtsManager.detectVoiceGenderFromMetadata("ar-xa-x-arz-local")
        )
        assertEquals(
            AssistantGender.MALE,
            EgyptianTtsManager.detectVoiceGenderFromMetadata("ar-xa-x-arc-network")
        )
        assertNull(EgyptianTtsManager.detectVoiceGenderFromMetadata("ar-eg-voice-01"))

        val femPitch = EgyptianTtsManager.computeEffectivePitch(
            basePitch = 1.0f,
            wantedGender = AssistantGender.FEMALE,
            usedPitchFallback = true
        )
        val malePitch = EgyptianTtsManager.computeEffectivePitch(
            basePitch = 1.0f,
            wantedGender = AssistantGender.MALE,
            usedPitchFallback = true
        )
        assertTrue(femPitch >= 1.10f)
        assertTrue(malePitch <= 0.92f)
    }

    // --- Part D: Keyless HTTPS Search ---

    @Test
    fun `online search stays strictly disabled when toggle is OFF`() = runTest {
        var networkCalled = false
        val out = OnlineSearchManager.search(
            query = "عاصمة مصر",
            internetSearchEnabled = false,
            fetcher = {
                networkCalled = true
                "{}"
            }
        )
        assertFalse(networkCalled)
        assertTrue(out.sources.isEmpty())
        assertNotNull(out.errorAr)
    }

    @Test
    fun `online search enforces https url validation and caches results`() = runTest {
        assertTrue(OnlineSearchManager.isValidHttpsUrl("https://searx.example.org"))
        assertFalse(OnlineSearchManager.isValidHttpsUrl("http://insecure.example.org"))
        assertFalse(OnlineSearchManager.isValidHttpsUrl("not a url"))

        var fetchCount = 0
        val fakeFetcher: (String) -> String = { url ->
            fetchCount++
            when {
                url.contains("wikipedia.org/w/api.php") -> """
                    {"query":{"search":[{"title":"القاهرة","snippet":"عاصمة جمهورية مصر العربية"}]}}
                """.trimIndent()
                url.contains("wikipedia.org/api/rest_v1") -> """
                    {"extract":"القاهرة هي عاصمة جمهورية مصر العربية وأكبر مدنها."}
                """.trimIndent()
                else -> """{"AbstractText":"","RelatedTopics":[]}"""
            }
        }

        val first = OnlineSearchManager.search(
            query = "القاهرة",
            internetSearchEnabled = true,
            clockMs = { 100_000L },
            fetcher = fakeFetcher
        )
        assertFalse(first.fromCache)
        assertEquals(1, first.sources.size)
        assertTrue(first.summaryText.contains("القاهرة"))

        val fetchesAfterFirst = fetchCount
        val cached = OnlineSearchManager.search(
            query = "القاهرة",
            internetSearchEnabled = true,
            clockMs = { 105_000L },
            fetcher = fakeFetcher
        )
        assertTrue(cached.fromCache)
        assertEquals(fetchesAfterFirst, fetchCount)
    }

    @Test
    fun `nlu distinguishes explicit google browser intent from general knowledge query`() {
        val (_, browserIntent) = EgyptianColloquialNluParser.parse(
            rawInput = "دورلي على حالة الطقس في جوجل",
            configuredWakePhrase = "يا شيتا"
        )
        assertTrue(browserIntent is EgyptianIntent.WebSearch)

        val (_, knowledgeIntent) = EgyptianColloquialNluParser.parse(
            rawInput = "مين هو أحمد زويل",
            configuredWakePhrase = "يا شيتا"
        )
        assertTrue(knowledgeIntent is EgyptianIntent.KnowledgeOrChatQuery)

        val (_, onlineLookupIntent) = EgyptianColloquialNluParser.parse(
            rawInput = "دورلي على سعر الذهب النهاردة",
            configuredWakePhrase = "يا شيتا"
        )
        assertTrue(
            onlineLookupIntent is EgyptianIntent.KnowledgeOrChatQuery &&
                onlineLookupIntent.explicitlyRequestsOnlineSearch
        )
    }

    private fun buildMinimalGgufHeader(
        version: Int,
        tensorCount: Long,
        architecture: String,
        name: String
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        fun writeLeInt(v: Int) {
            baos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        }
        fun writeLeLong(v: Long) {
            baos.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        }
        fun writeGgufStr(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            writeLeLong(b.size.toLong())
            baos.write(b)
        }

        baos.write("GGUF".toByteArray(Charsets.US_ASCII))
        writeLeInt(version)
        writeLeLong(tensorCount)
        writeLeLong(2L) // 2 KVs

        writeGgufStr("general.architecture")
        writeLeInt(8) // GGUF_TYPE_STRING
        writeGgufStr(architecture)

        writeGgufStr("general.name")
        writeLeInt(8)
        writeGgufStr(name)
        return baos.toByteArray()
    }
}
