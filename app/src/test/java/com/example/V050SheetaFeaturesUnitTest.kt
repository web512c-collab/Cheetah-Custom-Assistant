package com.example

import com.example.core.model.AiLanguagePreference
import com.example.core.model.AiMode
import com.example.core.model.AiPreset
import com.example.core.model.AiTaskType
import com.example.core.model.AssistantSettings
import com.example.core.model.ChatTemplateFamily
import com.example.core.model.ChatTemplateOverride
import com.example.core.model.ConcreteSearchEngine
import com.example.core.model.LocalLlmConfig
import com.example.core.model.LocalLlmStorageMode
import com.example.core.model.PerformanceProfile
import com.example.core.model.ResponseStyle
import com.example.core.model.SearchConfidence
import com.example.core.model.SearchEngineMode
import com.example.data.SettingsRepository
import com.example.intelligence.PromptTemplateEngine
import com.example.llm.ChatTemplateFormatter
import com.example.search.OnlineSearchManager
import com.example.search.SearchJudgePipeline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class V050SheetaFeaturesUnitTest {

    @Before
    fun resetSearchCache() {
        OnlineSearchManager.clearCache()
    }

    @Test
    fun `presets FAST BALANCED DEEP configure sampling batch and context cleanly`() {
        val base = LocalLlmConfig(
            modelUri = "content://models/qwen.gguf",
            displayName = "Qwen2.5-1.5B-Q4_K_M.gguf",
            quantization = "Q4_K_M",
            architecture = "qwen2",
            storageMode = LocalLlmStorageMode.COPIED_INTERNAL
        )

        val fast = SettingsRepository.applyPreset(base, AiPreset.FAST)
        assertEquals(AiPreset.FAST, fast.activePreset)
        assertEquals(AiMode.FAST, fast.aiMode)
        assertEquals(ResponseStyle.SHORT, fast.responseStyle)
        assertEquals(PerformanceProfile.BATTERY_SAVER, fast.performanceProfile)
        assertEquals(128, fast.batchSize)
        assertEquals(768, fast.contextLength)

        val deep = SettingsRepository.applyPreset(fast, AiPreset.DEEP)
        assertEquals(AiPreset.DEEP, deep.activePreset)
        assertEquals(AiMode.DEEP_THINKING, deep.aiMode)
        assertEquals(ResponseStyle.DETAILED, deep.responseStyle)
        assertEquals(512, deep.batchSize)
        assertEquals(2048, deep.contextLength)

        val reset = SettingsRepository.resetAiDefaultsPreservingModel(deep)
        assertEquals(AiPreset.BALANCED, reset.activePreset)
        assertEquals(base.modelUri, reset.modelUri)
        assertEquals(base.displayName, reset.displayName)
        assertEquals(base.quantization, reset.quantization)
        assertEquals(base.storageMode, reset.storageMode)
        assertEquals("", reset.customSystemPrompt)
        assertEquals(ChatTemplateOverride.AUTO, reset.chatTemplateOverride)
    }

    @Test
    fun `json export and import round-trip preserves settings rejects malformed json and clamps out of bounds values`() {
        val baseModel = LocalLlmConfig(
            modelUri = "content://models/llama.gguf",
            displayName = "Llama-3.2-1B.gguf",
            quantization = "Q4_K_M",
            architecture = "llama",
            storageMode = LocalLlmStorageMode.IN_PLACE_URI
        )
        val configured = baseModel.copy(
            contextLength = 1536,
            maxTokens = 320,
            temperature = 0.55f,
            topP = 0.88f,
            topK = 55,
            minP = 0.06f,
            repeatPenalty = 1.15f,
            seed = 12345,
            batchSize = 512,
            cpuThreads = 6,
            chatTemplateOverride = ChatTemplateOverride.LLAMA3,
            customSystemPrompt = "جاوب بنقاط منظمة ومختصرة.",
            responseStyle = ResponseStyle.DETAILED,
            spokenMaxChars = 340,
            aiLanguagePreference = AiLanguagePreference.MATCH_USER,
            stopSequences = listOf("<|eot_id|>", "</s>"),
            stripReasoningTags = false,
            activePreset = AiPreset.CUSTOM
        )

        val json = SettingsRepository.exportAiConfigToJson(configured)
        val imported = SettingsRepository.importAiConfigFromJson(json, baseModel).getOrThrow()

        assertEquals(1536, imported.contextLength)
        assertEquals(320, imported.maxTokens)
        assertEquals(0.88f, imported.topP, 0.001f)
        assertEquals(55, imported.topK)
        assertEquals(12345, imported.seed)
        assertEquals(512, imported.batchSize)
        assertEquals(ChatTemplateOverride.LLAMA3, imported.chatTemplateOverride)
        assertEquals("جاوب بنقاط منظمة ومختصرة.", imported.customSystemPrompt)
        assertEquals(ResponseStyle.DETAILED, imported.responseStyle)
        assertEquals(340, imported.spokenMaxChars)
        assertEquals(AiLanguagePreference.MATCH_USER, imported.aiLanguagePreference)
        assertEquals(listOf("<|eot_id|>", "</s>"), imported.stopSequences)
        assertFalse(imported.stripReasoningTags)
        assertEquals(baseModel.modelUri, imported.modelUri)

        // Malformed JSON rejected cleanly
        val malformed = SettingsRepository.importAiConfigFromJson("{not valid json", baseModel)
        assertTrue(malformed.isFailure)

        // Unsupported schema version rejected cleanly
        val badSchema = SettingsRepository.importAiConfigFromJson("""{"schemaVersion":99}""", baseModel)
        assertTrue(badSchema.isFailure)

        // Out-of-bounds values get clamped safely
        val extremeJson = """
            {
              "schemaVersion": 1,
              "contextLength": 999999,
              "maxTokens": 50000,
              "temperature": 9.9,
              "topP": 5.0,
              "topK": 9999,
              "minP": 2.0,
              "repeatPenalty": 5.0,
              "batchSize": 8192,
              "cpuThreads": 64,
              "spokenMaxChars": 9999
            }
        """.trimIndent()
        val clamped = SettingsRepository.importAiConfigFromJson(extremeJson, baseModel).getOrThrow()
        assertEquals(4096, clamped.contextLength)
        assertEquals(1024, clamped.maxTokens)
        assertEquals(1.5f, clamped.temperature, 0.001f)
        assertEquals(1.0f, clamped.topP, 0.001f)
        assertEquals(200, clamped.topK)
        assertEquals(0.50f, clamped.minP, 0.001f)
        assertEquals(1.60f, clamped.repeatPenalty, 0.001f)
        assertEquals(1024, clamped.batchSize)
        assertEquals(8, clamped.cpuThreads)
        assertEquals(600, clamped.spokenMaxChars)
    }

    @Test
    fun `chat template override resolves effective family and warns on mismatch`() {
        assertEquals(
            ChatTemplateFamily.CHATML,
            ChatTemplateFormatter.resolveEffectiveFamily(ChatTemplateFamily.CHATML, ChatTemplateOverride.AUTO)
        )
        assertEquals(
            ChatTemplateFamily.LLAMA3,
            ChatTemplateFormatter.resolveEffectiveFamily(ChatTemplateFamily.CHATML, ChatTemplateOverride.LLAMA3)
        )

        val mismatchWarn = ChatTemplateFormatter.buildTemplateWarning(
            rawTemplate = "<|im_start|>user\n<|im_end|>",
            family = ChatTemplateFamily.CHATML,
            override = ChatTemplateOverride.GEMMA
        )
        assertNotNull(mismatchWarn)
        assertTrue(mismatchWarn!!.contains("Gemma"))
        assertTrue(mismatchWarn.contains("ChatML"))

        val matching = ChatTemplateFormatter.buildTemplateWarning(
            rawTemplate = "<|im_start|>user\n<|im_end|>",
            family = ChatTemplateFamily.CHATML,
            override = ChatTemplateOverride.CHATML
        )
        assertNull(matching)
    }

    @Test
    fun `prompt template engine injects sanitized custom system prompt response style and language preference`() {
        val longCustom = "أنت خبير برمجة كوتلن.\u0000 " + "x".repeat(1500)
        val settings = AssistantSettings(
            localLlmConfig = LocalLlmConfig(
                customSystemPrompt = longCustom,
                responseStyle = ResponseStyle.SHORT,
                aiLanguagePreference = AiLanguagePreference.ENGLISH
            )
        )
        val sysPrompt = PromptTemplateEngine.buildSystemPrompt(
            settings = settings,
            taskType = AiTaskType.CODE_HELP,
            aiMode = AiMode.BALANCED,
            nonArabicInput = true
        )
        assertTrue(sysPrompt.contains("natural English"))
        assertTrue(sysPrompt.contains("رد مختصر"))
        assertTrue(sysPrompt.contains("أنت خبير برمجة كوتلن."))
        assertFalse(sysPrompt.contains("\u0000"))
        assertFalse(sysPrompt.contains("x".repeat(1250)))
    }

    @Test
    fun `mojeek html parser parses valid results and ignores malformed markup safely`() {
        val validMojeekHtml = """
            <html><body>
              <ul class="results-standard">
                <li>
                  <a class="ob" href="https://www.nature.com/articles/quantum-2026">Quantum Breakthrough 2026</a>
                  <p class="s">Researchers published verified benchmarks [2026-04-19] across superconducting qubits.</p>
                </li>
                <li>
                  <a class="title" href="http://insecure.example.com/skip">Insecure HTTP</a>
                  <p class="s">Should be ignored because only HTTPS links are allowed.</p>
                </li>
                <li>
                  <a class="title" href="https://en.wikipedia.org/wiki/Egypt">Egypt - Wikipedia</a>
                  <p class="s">Egypt is a transcontinental country spanning North Africa and Sinai.</p>
                </li>
              </ul>
            </body></html>
        """.trimIndent()

        val parsed = OnlineSearchManager.parseMojeekHtml(validMojeekHtml, maxItems = 5)
        assertEquals(2, parsed.size)
        assertEquals("https://www.nature.com/articles/quantum-2026", parsed[0].url)
        assertEquals("nature.com", parsed[0].sourceDomain)
        assertEquals("Mojeek", parsed[0].engineName)
        assertEquals("2026-04-19", parsed[0].publishedDate)
        assertEquals("https://en.wikipedia.org/wiki/Egypt", parsed[1].url)

        val broken = OnlineSearchManager.parseMojeekHtml("<html><div>unclosed <p>nothing useful", maxItems = 5)
        assertTrue(broken.isEmpty())
    }

    @Test
    fun `auto parallel search fuses results via RRF deduplicates tracking params and boosts multi-engine consensus`() = runTest {
        val sharedConsensusUrlRaw = "https://www.reuters.com/cairo-metro-expansion?utm_source=ddg"
        val sharedConsensusUrlMojeek = "https://reuters.com/cairo-metro-expansion/"
        val ddgOnlyUrl = "https://example.org/ddg-first-only"
        val mojeekOnlyUrl = "https://example.net/mojeek-first-only"

        val outcome = OnlineSearchManager.search(
            query = "Cairo metro expansion 2026",
            internetSearchEnabled = true,
            searchEngineMode = SearchEngineMode.AUTO,
            autoSearchOrder = listOf(
                ConcreteSearchEngine.DUCKDUCKGO,
                ConcreteSearchEngine.MOJEEK,
                ConcreteSearchEngine.WIKIPEDIA
            ),
            enabledEngines = setOf(
                ConcreteSearchEngine.DUCKDUCKGO,
                ConcreteSearchEngine.MOJEEK,
                ConcreteSearchEngine.WIKIPEDIA
            ),
            fetchPageExcerpts = true,
            bypassCache = true,
            clockMs = { 120_000L },
            fetcher = { url ->
                when {
                    url.contains("api.duckduckgo.com") -> """{"AbstractText":"","RelatedTopics":[]}"""
                    url.contains("html.duckduckgo.com") -> """
                        <div class="result">
                          <a class="result__a" href="$ddgOnlyUrl">DDG First Only Result</a>
                          <div class="result__snippet">Single-engine hit from DuckDuckGo at rank 1 for testing.</div>
                        </div>
                        <div class="result">
                          <a class="result__a" href="$sharedConsensusUrlRaw">Cairo Metro Expansion 2026</a>
                          <div class="result__snippet">Line 4 construction advances in Cairo with new stations [2026-05-01].</div>
                        </div>
                    """.trimIndent()
                    url.contains("mojeek.com/search") -> """
                        <ul>
                          <li>
                            <a class="title" href="$mojeekOnlyUrl">Mojeek First Only Result</a>
                            <p class="s">Single-engine hit from Mojeek at rank 1 for comparison.</p>
                          </li>
                          <li>
                            <a class="ob" href="$sharedConsensusUrlMojeek">Cairo Metro Expansion 2026</a>
                            <p class="s">Independent Reuters coverage of Cairo metro stations opening in 2026.</p>
                          </li>
                        </ul>
                    """.trimIndent()
                    url.contains("wikipedia.org") -> """{"query":{"search":[]}}"""
                    url.startsWith("https://www.reuters.com/cairo-metro-expansion") -> """
                        <html>
                          <head><script>console.log('tracker');</script><style>.nav{}</style></head>
                          <body>
                            <nav>Cookie Policy Accept All Cookies Home Menu</nav>
                            <article>Cairo Metro Line 4 and electric rail upgrades reached major tunnel milestones in 2026 according to engineering reports.</article>
                          </body>
                        </html>
                    """.trimIndent()
                    else -> ""
                }
            }
        )

        // All 3 enabled engines reported independently
        assertEquals(3, outcome.perEngineReports.size)
        // Consensus item (found by DuckDuckGo AND Mojeek) ranks #1 via RRF + multi-engine consensus boost
        assertTrue(outcome.sources.isNotEmpty())
        val top = outcome.sources.first()
        assertEquals("reuters.com", top.sourceDomain)
        assertTrue(top.contributingEngines.contains("DuckDuckGo"))
        assertTrue(top.contributingEngines.contains("Mojeek"))
        // Page excerpt enrichment stripped <script>/<nav>/cookies and updated snippet cleanly
        assertTrue(top.snippet.contains("Line 4") || top.snippet.contains("milestones"))
        assertFalse(top.snippet.contains("tracker"))
        // Deduplication merged utm_source + trailing slash variants into one canonical source
        assertEquals(1, outcome.sources.count { it.sourceDomain == "reuters.com" })

        // Verify SearchJudgePipeline records consensusCount & <<<UNTRUSTED_WEB_CONTENT>>> provenance
        val evaluated = SearchJudgePipeline.evaluateAndSynthesize(
            query = "Cairo metro expansion 2026",
            rawSources = outcome.sources,
            settings = AssistantSettings(internetSearchEnabled = true),
            modelLoaded = false
        )
        assertEquals(1, evaluated.crossCheck.consensusCount)
        assertTrue(evaluated.crossCheck.agreements.isNotEmpty())

        val wrapped = SearchJudgePipeline.wrapUntrustedWebContent(
            index = 1,
            title = top.title,
            domain = top.sourceDomain,
            dateHint = top.publishedDate,
            passage = top.snippet,
            engines = top.contributingEngines
        )
        assertTrue(wrapped.contains(SearchJudgePipeline.UNTRUSTED_START))
        assertTrue(wrapped.contains("Engines: DuckDuckGo+Mojeek"))
    }

    @Test
    fun `fetchReadablePageExcerpt enforces https strips boilerplate and caps payload at 120KB`() {
        assertNull(
            OnlineSearchManager.fetchReadablePageExcerpt(
                rawUrl = "http://insecure.example.com/article",
                fetcher = { "<html><body>Should never be fetched</body></html>" }
            )
        )

        val hugeBody = "<body><script>bad()</script><nav>Privacy Policy Menu</nav><p>" +
            "Verified clean scientific paragraph about solar turbines in Upper Egypt. " +
            "Z".repeat(200_000) + "</p></body>"
        val excerpt = OnlineSearchManager.fetchReadablePageExcerpt(
            rawUrl = "https://science.example.org/solar",
            fetcher = { hugeBody },
            maxExcerptChars = 300
        )
        assertNotNull(excerpt)
        assertTrue(excerpt!!.startsWith("Verified clean scientific paragraph"))
        assertFalse(excerpt.contains("bad()"))
        assertTrue(excerpt.length <= 300)
    }
}
