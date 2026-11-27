package com.example

import com.example.core.model.AssistantSettings
import com.example.core.model.ConcreteSearchEngine
import com.example.core.model.EgyptianIntent
import com.example.core.model.SearchConfidence
import com.example.core.model.SearchEngineMode
import com.example.intelligence.EgyptianColloquialNluParser
import com.example.search.OnlineSearchManager
import com.example.search.SearchJudgePipeline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class V041SearchEnginesUnitTest {

    @Before
    fun resetSearchCache() {
        OnlineSearchManager.clearCache()
    }

    @Test
    fun `duckduckgo engine parses instant answer json and falls back gracefully to html results`() = runTest {
        val fakeHtml = """
            <html><body>
              <div class="result">
                <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.reuters.com%2Fcairo-weather">Cairo Sunny Today</a>
                <a class="result__snippet" href="#">Temperature reaches 29C in Cairo today with clear skies [2026-05-02].</a>
              </div>
              <div class="result">
                <a class="result__a" href="https://en.wikipedia.org/wiki/Cairo">Cairo - Wikipedia</a>
                <div class="result__snippet">Capital of Egypt along the Nile river delta.</div>
              </div>
            </body></html>
        """.trimIndent()

        val outcome = OnlineSearchManager.search(
            query = "Cairo weather today",
            internetSearchEnabled = true,
            searchEngineMode = SearchEngineMode.DUCKDUCKGO,
            bypassCache = true,
            clockMs = { 50_000L },
            fetcher = { url ->
                when {
                    url.contains("api.duckduckgo.com") -> """{"AbstractText":"","RelatedTopics":[]}"""
                    url.contains("html.duckduckgo.com") -> fakeHtml
                    else -> error("Unexpected engine URL: $url")
                }
            }
        )

        assertEquals("DuckDuckGo", outcome.engineUsed)
        assertEquals(2, outcome.sources.size)
        assertEquals("reuters.com", outcome.sources[0].sourceDomain)
        assertEquals("DuckDuckGo", outcome.sources[0].engineName)
        assertEquals("https://www.reuters.com/cairo-weather", outcome.sources[0].url)
    }

    @Test
    fun `wikipedia engine follows query language ar vs en and falls back from ar to en when empty`() = runTest {
        val arOutcome = OnlineSearchManager.search(
            query = "أحمد زويل",
            internetSearchEnabled = true,
            searchEngineMode = SearchEngineMode.WIKIPEDIA,
            bypassCache = true,
            clockMs = { 60_000L },
            fetcher = { url ->
                when {
                    url.startsWith("https://ar.wikipedia.org/w/api.php") -> """
                        {"query":{"search":[{"title":"أحمد زويل","snippet":"عالم كيمياء مصري حصل على نوبل","timestamp":"2026-04-12T10:00:00Z"}]}}
                    """.trimIndent()
                    url.startsWith("https://ar.wikipedia.org/api/rest_v1") -> """
                        {"extract":"أحمد حسن زويل عالم كيميائي مصري وأمريكي حائز على جائزة نوبل في الكيمياء."}
                    """.trimIndent()
                    else -> error("Should not hit non-Arabic or non-Wiki URL: $url")
                }
            }
        )
        assertEquals("Wikipedia", arOutcome.engineUsed)
        assertEquals(1, arOutcome.sources.size)
        assertEquals("ar.wikipedia.org", arOutcome.sources.first().sourceDomain)
        assertEquals("2026-04-12", arOutcome.sources.first().publishedDate)

        // Arabic query with empty Arabic Wikipedia falls back to English Wikipedia cleanly
        val fallbackOutcome = OnlineSearchManager.search(
            query = "الذكاء الاصطناعي الكمي",
            internetSearchEnabled = true,
            searchEngineMode = SearchEngineMode.WIKIPEDIA,
            bypassCache = true,
            clockMs = { 65_000L },
            fetcher = { url ->
                when {
                    url.startsWith("https://ar.wikipedia.org/w/api.php") -> """{"query":{"search":[]}}"""
                    url.startsWith("https://en.wikipedia.org/w/api.php") -> """
                        {"query":{"search":[{"title":"Quantum artificial intelligence","snippet":"Interdisciplinary field combining quantum computing and AI","timestamp":"2026-03-01T00:00:00Z"}]}}
                    """.trimIndent()
                    url.startsWith("https://en.wikipedia.org/api/rest_v1") -> """
                        {"extract":"Quantum artificial intelligence combines quantum algorithms with machine learning."}
                    """.trimIndent()
                    else -> ""
                }
            }
        )
        assertEquals(1, fallbackOutcome.sources.size)
        assertEquals("en.wikipedia.org", fallbackOutcome.sources.first().sourceDomain)
    }

    @Test
    fun `searxng engine validates https only, parses json with date, and tests connection`() = runTest {
        val rejectedHttp = OnlineSearchManager.testSearxngConnection(
            customSearxngUrl = "http://insecure.local",
            internetSearchEnabled = true
        )
        assertFalse(rejectedHttp.success)
        assertTrue(rejectedHttp.messageAr.contains("https://"))

        val searxJson = """
            {
              "results": [
                {
                  "title": "Nile Water Report",
                  "url": "https://www.bbc.com/news/nile-report",
                  "content": "<b>Updated</b> report on Nile basin measurements.",
                  "publishedDate": "2026-05-10T09:00:00",
                  "engine": "duckduckgo"
                }
              ]
            }
        """.trimIndent()

        val testOk = OnlineSearchManager.testSearxngConnection(
            customSearxngUrl = "https://searx.example.org/",
            internetSearchEnabled = true,
            clockMs = { 70_000L },
            fetcher = { searxJson }
        )
        assertTrue(testOk.success)
        assertEquals(1, testOk.resultCount)

        val outcome = OnlineSearchManager.search(
            query = "Nile report",
            internetSearchEnabled = true,
            customSearxngUrl = "https://searx.example.org",
            searchEngineMode = SearchEngineMode.SEARXNG,
            bypassCache = true,
            clockMs = { 75_000L },
            fetcher = { searxJson }
        )
        assertEquals("SearXNG", outcome.engineUsed)
        assertEquals(1, outcome.sources.size)
        assertEquals("bbc.com", outcome.sources.first().sourceDomain)
        assertEquals("2026-05-10", outcome.sources.first().publishedDate)
        assertEquals("SearXNG", outcome.sources.first().engineName)
    }

    @Test
    fun `auto mode follows user-editable fallback order when primary fails or returns empty`() = runTest {
        val visited = java.util.Collections.synchronizedList(mutableListOf<String>())
        val outcome = OnlineSearchManager.search(
            query = "Egypt solar energy",
            internetSearchEnabled = true,
            customSearxngUrl = "https://searx.example.org",
            searchEngineMode = SearchEngineMode.AUTO,
            autoSearchOrder = listOf(
                ConcreteSearchEngine.SEARXNG,
                ConcreteSearchEngine.WIKIPEDIA,
                ConcreteSearchEngine.DUCKDUCKGO
            ),
            bypassCache = true,
            clockMs = { 80_000L },
            fetcher = { url ->
                visited.add(url)
                when {
                    url.startsWith("https://searx.example.org") -> error("HTTP 503")
                    url.startsWith("https://en.wikipedia.org/w/api.php") -> """{"query":{"search":[]}}"""
                    url.contains("api.duckduckgo.com") -> """
                        {"Heading":"Solar power in Egypt","AbstractText":"Benban Solar Park is one of the largest solar installations.","AbstractURL":"https://en.wikipedia.org/wiki/Benban_Solar_Park","AbstractSource":"Wikipedia"}
                    """.trimIndent()
                    url.contains("html.duckduckgo.com") -> ""
                    else -> ""
                }
            }
        )

        assertTrue(visited.any { it.startsWith("https://searx.example.org") })
        assertTrue(visited.any { it.contains("wikipedia.org") })
        assertEquals("SearXNG", outcome.perEngineReports.first().engineName)
        assertEquals("DuckDuckGo", outcome.engineUsed)
        assertEquals(1, outcome.sources.size)
    }

    @Test
    fun `malformed responses and all engines failing produce graceful error without crashing`() = runTest {
        val malformedDdg = OnlineSearchManager.parseDuckDuckGoJson("{{{not json")
        val malformedHtml = OnlineSearchManager.parseDuckDuckGoHtml("<html><div>layout changed completely</div></html>")
        val malformedSearx = OnlineSearchManager.parseSearxngJson("<html/>")
        assertTrue(malformedDdg.isEmpty())
        assertTrue(malformedHtml.isEmpty())
        assertTrue(malformedSearx.isEmpty())

        val allFailed = OnlineSearchManager.search(
            query = "something rare",
            internetSearchEnabled = true,
            searchEngineMode = SearchEngineMode.AUTO,
            autoSearchOrder = listOf(ConcreteSearchEngine.DUCKDUCKGO, ConcreteSearchEngine.WIKIPEDIA),
            bypassCache = true,
            clockMs = { 90_000L },
            fetcher = { error("Connection timed out") }
        )
        assertTrue(allFailed.sources.isEmpty())
        assertNotNull(allFailed.errorAr)
    }

    @Test
    fun `testEngineSearch reports result count and elapsed response time and feeds SearchJudgePipeline cleanly`() = runTest {
        var tick = 100_000L
        val settings = AssistantSettings(
            internetSearchEnabled = true,
            searchEngineMode = SearchEngineMode.WIKIPEDIA
        )
        val testRes = OnlineSearchManager.testEngineSearch(
            settings = settings,
            sampleQuery = "القاهرة",
            clockMs = {
                tick += 45L
                tick
            },
            fetcher = { url ->
                when {
                    url.contains("w/api.php") -> """
                        {"query":{"search":[{"title":"القاهرة","snippet":"القاهرة هي عاصمة مصر وأكبر مدينة في العالم العربي."}]}}
                    """.trimIndent()
                    else -> """{"extract":"القاهرة هي عاصمة جمهورية مصر العربية وتقع على نهر النيل."}"""
                }
            }
        )
        assertTrue(testRes.success)
        assertEquals(1, testRes.resultCount)
        assertTrue(testRes.responseTimeMs > 0L)
        assertEquals("Wikipedia", testRes.engineUsed)

        // Verify SearchJudgePipeline consumes any engine's WebSearchSource cleanly
        val outcome = OnlineSearchManager.search(
            query = "القاهرة",
            internetSearchEnabled = true,
            searchEngineMode = SearchEngineMode.WIKIPEDIA,
            bypassCache = true,
            clockMs = { 101_000L },
            fetcher = { url ->
                when {
                    url.contains("w/api.php") -> """
                        {"query":{"search":[{"title":"القاهرة","snippet":"القاهرة هي عاصمة جمهورية مصر العربية وتقع على ضفاف النيل."}]}}
                    """.trimIndent()
                    else -> """{"extract":"القاهرة هي عاصمة جمهورية مصر العربية وأكبر مدنها."}"""
                }
            }
        )
        val judged = SearchJudgePipeline.evaluateAndSynthesize(
            query = "القاهرة",
            rawSources = outcome.sources,
            settings = settings,
            modelLoaded = false
        )
        assertEquals(SearchConfidence.LOW, judged.confidence)
        assertTrue(judged.judgedSources.isNotEmpty())
        assertEquals("Wikipedia", judged.judgedSources.first().source.engineName)
    }

    @Test
    fun `explicit google and youtube Android intent commands still parse unchanged`() {
        val (_, googleIntent) = EgyptianColloquialNluParser.parse(
            rawInput = "ابحث في جوجل عن طقس الإسكندرية",
            configuredWakePhrase = "يا شيتا"
        )
        assertTrue(googleIntent is EgyptianIntent.WebSearch)

        val (_, youtubeIntent) = EgyptianColloquialNluParser.parse(
            rawInput = "شغل سورة الكهف على يوتيوب",
            configuredWakePhrase = "يا شيتا"
        )
        assertTrue(youtubeIntent is EgyptianIntent.YouTubeSearchPlay)
    }
}
