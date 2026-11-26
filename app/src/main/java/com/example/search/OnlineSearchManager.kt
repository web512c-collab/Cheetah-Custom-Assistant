package com.example.search

import com.example.core.model.AssistantSettings
import com.example.core.model.ConcreteSearchEngine
import com.example.core.model.SearchEngineMode
import com.example.core.model.SearchEngineTestResult
import com.example.core.model.WebSearchOutcome
import com.example.core.model.WebSearchSource
import com.example.voice.CheetahRuntimeBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.LinkedHashMap

/**
 * Keyless HTTPS internet search manager (Part D + v0.4.1 Search Engine Selector).
 *
 * Privacy & security guarantees:
 * 1. Strictly gated behind settings.internetSearchEnabled (OFF by default).
 * 2. Sends ONLY the cleaned search query text (never mic audio, conversation memory, or PII).
 * 3. HTTPS-only with explicit connect/read timeouts, User-Agent, LRU TTL cache, and rate limiter.
 * 4. Honest endpoint disclosure:
 *    - DuckDuckGo: Instant Answer public JSON API + DuckDuckGo HTML public endpoint (scraped gracefully).
 *    - Wikipedia: Official MediaWiki Search JSON API & REST Summary API (no key required).
 *    - SearXNG: User-configured HTTPS instance `/search?format=json` JSON endpoint.
 *    - Auto: User-ordered fallback across DuckDuckGo, Wikipedia, and SearXNG.
 */
object OnlineSearchManager {

    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val CACHE_TTL_MS = 15 * 60_000L
    private const val MAX_CACHE_SIZE = 24
    private const val MIN_REQUEST_INTERVAL_MS = 1_200L
    private const val USER_AGENT = "CheetahCustomAssistant/0.4.1 (Android; KeylessSearch)"

    private val DDG_HTML_LINK_REGEX = Regex(
        """(?is)<a[^>]*class=["'][^"']*result__a[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>"""
    )
    private val DDG_HTML_SNIPPET_REGEX = Regex(
        """(?is)<(?:a|div)[^>]*class=["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</(?:a|div)>"""
    )
    private val ISO_DATE_PREFIX_REGEX = Regex("""^(20\d{2}-\d{2}-\d{2})""")

    private data class CacheEntry(
        val outcome: WebSearchOutcome,
        val cachedAtMs: Long
    )

    private val cacheLock = Any()
    private val lruCache = object : LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    @Volatile
    private var lastNetworkCallMs = 0L

    fun isValidHttpsUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        return runCatching {
            val u = URL(trimmed)
            u.protocol.equals("https", ignoreCase = true) && !u.host.isNullOrBlank()
        }.getOrDefault(false)
    }

    fun clearCache() {
        synchronized(cacheLock) { lruCache.clear() }
    }

    fun extractDomain(url: String): String {
        return runCatching {
            URL(url).host.lowercase().removePrefix("www.")
        }.getOrDefault(
            url.lowercase().substringAfter("://").substringBefore('/').removePrefix("www.")
        )
    }

    suspend fun search(
        query: String,
        internetSearchEnabled: Boolean,
        customSearxngUrl: String = "",
        maxResults: Int = 5,
        searchEngineMode: SearchEngineMode = SearchEngineMode.AUTO,
        autoSearchOrder: List<ConcreteSearchEngine> = AssistantSettings.DEFAULT_AUTO_ENGINE_ORDER,
        bypassCache: Boolean = false,
        clockMs: () -> Long = { System.currentTimeMillis() },
        fetcher: (String) -> String = { url -> httpGetHttps(url) }
    ): WebSearchOutcome = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (!internetSearchEnabled) {
            return@withContext WebSearchOutcome(
                query = cleanQuery,
                summaryText = "",
                sources = emptyList(),
                errorAr = "البحث على الإنترنت مقفول من الإعدادات."
            )
        }
        if (cleanQuery.isBlank()) {
            return@withContext WebSearchOutcome(
                query = cleanQuery,
                summaryText = "",
                errorAr = "عبارة البحث فارغة."
            )
        }

        val requestedCap = maxResults.coerceIn(1, 8)
        val effectiveOrder = normalizeOrder(autoSearchOrder)
        val cacheKey = buildString {
            append(cleanQuery.lowercase())
            append('|').append(searchEngineMode.name)
            append('|').append(effectiveOrder.joinToString(",") { it.name })
            append('|').append(customSearxngUrl.trim())
            append('|').append(requestedCap)
        }

        if (!bypassCache) {
            val now = clockMs()
            synchronized(cacheLock) {
                val hit = lruCache[cacheKey]
                if (hit != null && (now - hit.cachedAtMs) <= CACHE_TTL_MS) {
                    return@withContext hit.outcome.copy(fromCache = true, responseTimeMs = 0L)
                }
            }
        }

        val nowBeforeLimit = clockMs()
        val elapsedSinceLast = nowBeforeLimit - lastNetworkCallMs
        if (elapsedSinceLast in 0 until MIN_REQUEST_INTERVAL_MS) {
            kotlinx.coroutines.delay(MIN_REQUEST_INTERVAL_MS - elapsedSinceLast)
        }
        val startTimeMs = clockMs()
        lastNetworkCallMs = startTimeMs

        CheetahRuntimeBus.setNetworkSearching(true)
        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val isArabic = cleanQuery.any { it in '\u0600'..'\u06FF' }

            val enginesToTry: List<ConcreteSearchEngine> = when (searchEngineMode) {
                SearchEngineMode.DUCKDUCKGO -> listOf(ConcreteSearchEngine.DUCKDUCKGO)
                SearchEngineMode.WIKIPEDIA -> listOf(ConcreteSearchEngine.WIKIPEDIA)
                SearchEngineMode.SEARXNG -> listOf(ConcreteSearchEngine.SEARXNG)
                SearchEngineMode.AUTO -> effectiveOrder
            }

            val errors = mutableListOf<String>()
            var winningEngine = ""
            val collected = mutableListOf<WebSearchSource>()

            for (engine in enginesToTry) {
                if (engine == ConcreteSearchEngine.SEARXNG && !isValidHttpsUrl(customSearxngUrl)) {
                    if (searchEngineMode == SearchEngineMode.SEARXNG) {
                        val elapsed = (clockMs() - startTimeMs).coerceAtLeast(0L)
                        return@withContext WebSearchOutcome(
                            query = cleanQuery,
                            summaryText = "",
                            sources = emptyList(),
                            errorAr = "رابط خادم SearXNG غير مضبوط أو غير صالح (يجب أن يبدأ بـ https://).",
                            engineUsed = "SearXNG",
                            responseTimeMs = elapsed
                        )
                    } else {
                        errors.add("SearXNG: رابط غير مضبوط")
                        continue
                    }
                }

                val attempt = runCatching {
                    executeEngine(
                        engine = engine,
                        cleanQuery = cleanQuery,
                        encodedQuery = encoded,
                        isArabic = isArabic,
                        customSearxngUrl = customSearxngUrl,
                        maxItems = requestedCap,
                        fetcher = fetcher
                    )
                }

                attempt.onSuccess { hits ->
                    if (hits.isNotEmpty()) {
                        collected.addAll(hits)
                        if (winningEngine.isBlank()) winningEngine = engine.labelAr
                    } else {
                        errors.add("${engine.labelAr}: لا توجد نتائج")
                    }
                }.onFailure { err ->
                    errors.add("${engine.labelAr}: ${err.message ?: "تعذر الاتصال"}")
                }

                // Primary engine succeeded -> stop in single or Auto fallback mode
                if (collected.isNotEmpty()) break
            }

            val deduped = collected
                .filter { it.snippet.isNotBlank() || it.title.isNotBlank() }
                .distinctBy { it.url.ifBlank { it.title } }
                .take(requestedCap)

            val totalElapsedMs = (clockMs() - startTimeMs).coerceAtLeast(1L)

            val outcome = if (deduped.isEmpty()) {
                val reason = if (errors.isNotEmpty()) " (${errors.joinToString("؛ ")})" else ""
                WebSearchOutcome(
                    query = cleanQuery,
                    summaryText = "ملقتش نتائج متاحة عن «$cleanQuery»$reason.",
                    sources = emptyList(),
                    errorAr = "محرك البحث غير متاح أو مرجعش نتائج حالياً$reason.",
                    engineUsed = winningEngine.ifBlank { enginesToTry.first().labelAr },
                    responseTimeMs = totalElapsedMs
                )
            } else {
                val top = deduped.first()
                val summary = if (top.snippet.isNotBlank()) {
                    "${top.title}: ${top.snippet}"
                } else {
                    top.title
                }
                WebSearchOutcome(
                    query = cleanQuery,
                    summaryText = summary.take(520),
                    sources = deduped,
                    fromCache = false,
                    engineUsed = winningEngine,
                    responseTimeMs = totalElapsedMs
                )
            }

            if (deduped.isNotEmpty()) {
                synchronized(cacheLock) {
                    lruCache[cacheKey] = CacheEntry(outcome, clockMs())
                }
            }
            outcome
        } catch (t: Throwable) {
            val totalElapsedMs = (clockMs() - startTimeMs).coerceAtLeast(0L)
            WebSearchOutcome(
                query = cleanQuery,
                summaryText = "",
                sources = emptyList(),
                errorAr = "تعذر الاتصال بمصدر البحث (${t.message ?: "network error"}).",
                responseTimeMs = totalElapsedMs
            )
        } finally {
            CheetahRuntimeBus.setNetworkSearching(false)
        }
    }

    suspend fun testSearxngConnection(
        customSearxngUrl: String,
        internetSearchEnabled: Boolean,
        clockMs: () -> Long = { System.currentTimeMillis() },
        fetcher: (String) -> String = { url -> httpGetHttps(url) }
    ): SearchEngineTestResult = withContext(Dispatchers.IO) {
        if (!internetSearchEnabled) {
            return@withContext SearchEngineTestResult(
                success = false,
                messageAr = "فعّل «السماح بالبحث على الإنترنت» أولاً لاختبار الاتصال.",
                engineUsed = "SearXNG"
            )
        }
        val cleanUrl = customSearxngUrl.trim().trimEnd('/')
        if (!isValidHttpsUrl(cleanUrl)) {
            return@withContext SearchEngineTestResult(
                success = false,
                messageAr = "الرابط غير صالح: يجب أن يبدأ بـ https:// ويحتوي على نطاق صحيح.",
                engineUsed = "SearXNG"
            )
        }
        val start = clockMs()
        return@withContext runCatching {
            val body = fetcher("$cleanUrl/search?q=cairo&format=json")
            val root = SimpleJson.parseObject(body)
                ?: error("الاستجابة ليست JSON صالحاً (تأكد أن تنسيق JSON مفعّل على خادم SearXNG).")
            val resultsArr = SimpleJson.asArray(root["results"])
                ?: error("الخادم لم يُرجع حقل results المتوقع.")
            val elapsed = (clockMs() - start).coerceAtLeast(1L)
            val hits = parseSearxngJson(body, maxItems = 5)
            SearchEngineTestResult(
                success = true,
                messageAr = "تم الاتصال بخادم SearXNG بنجاح (${resultsArr.size} نتيجة أولية خلال $elapsed مللي ثانية).",
                resultCount = hits.size.coerceAtLeast(resultsArr.size),
                responseTimeMs = elapsed,
                engineUsed = "SearXNG"
            )
        }.getOrElse { err ->
            val elapsed = (clockMs() - start).coerceAtLeast(0L)
            SearchEngineTestResult(
                success = false,
                messageAr = "فشل الاتصال بخادم SearXNG: ${err.message ?: "تعذر الوصول للخادم"}.",
                responseTimeMs = elapsed,
                engineUsed = "SearXNG"
            )
        }
    }

    suspend fun testEngineSearch(
        settings: AssistantSettings,
        sampleQuery: String = "القاهرة",
        clockMs: () -> Long = { System.currentTimeMillis() },
        fetcher: (String) -> String = { url -> httpGetHttps(url) }
    ): SearchEngineTestResult {
        if (!settings.internetSearchEnabled) {
            return SearchEngineTestResult(
                success = false,
                messageAr = "البحث مقفول من الإعدادات. فعّل المفتاح أولاً لتجربة البحث."
            )
        }
        val start = clockMs()
        val outcome = search(
            query = sampleQuery,
            internetSearchEnabled = true,
            customSearxngUrl = settings.customSearxngUrl,
            maxResults = 5,
            searchEngineMode = settings.searchEngineMode,
            autoSearchOrder = settings.autoSearchOrder,
            bypassCache = true,
            clockMs = clockMs,
            fetcher = fetcher
        )
        val elapsed = outcome.responseTimeMs.takeIf { it > 0L } ?: (clockMs() - start).coerceAtLeast(1L)
        return if (outcome.sources.isNotEmpty()) {
            SearchEngineTestResult(
                success = true,
                messageAr = "نجح البحث عبر ${outcome.engineUsed}: ${outcome.sources.size} نتيجة خلال $elapsed مللي ثانية.",
                resultCount = outcome.sources.size,
                responseTimeMs = elapsed,
                engineUsed = outcome.engineUsed
            )
        } else {
            SearchEngineTestResult(
                success = false,
                messageAr = outcome.errorAr ?: "لم ترجع نتائج للمعاينة ($elapsed مللي ثانية).",
                resultCount = 0,
                responseTimeMs = elapsed,
                engineUsed = outcome.engineUsed
            )
        }
    }

    fun normalizeOrder(order: List<ConcreteSearchEngine>): List<ConcreteSearchEngine> {
        val unique = order.distinct().toMutableList()
        for (e in ConcreteSearchEngine.entries) {
            if (e !in unique) unique.add(e)
        }
        return unique
    }

    private fun executeEngine(
        engine: ConcreteSearchEngine,
        cleanQuery: String,
        encodedQuery: String,
        isArabic: Boolean,
        customSearxngUrl: String,
        maxItems: Int,
        fetcher: (String) -> String
    ): List<WebSearchSource> {
        return when (engine) {
            ConcreteSearchEngine.DUCKDUCKGO -> queryDuckDuckGo(encodedQuery, maxItems, fetcher)
            ConcreteSearchEngine.WIKIPEDIA -> queryWikipediaAutoLang(encodedQuery, isArabic, fetcher)
            ConcreteSearchEngine.SEARXNG -> {
                val base = customSearxngUrl.trim().trimEnd('/')
                val lang = if (isArabic) "ar" else "en"
                val url = "$base/search?q=$encodedQuery&format=json&language=$lang"
                parseSearxngJson(fetcher(url), maxItems = maxItems)
            }
        }
    }

    private fun queryDuckDuckGo(
        encodedQuery: String,
        maxItems: Int,
        fetcher: (String) -> String
    ): List<WebSearchSource> {
        val out = mutableListOf<WebSearchSource>()
        val apiUrl = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"
        runCatching {
            parseDuckDuckGoJson(fetcher(apiUrl))
        }.onSuccess { out.addAll(it) }

        if (out.size < minOf(2, maxItems)) {
            val htmlUrl = "https://html.duckduckgo.com/html/?q=$encodedQuery"
            runCatching {
                parseDuckDuckGoHtml(fetcher(htmlUrl), maxItems = maxItems)
            }.onSuccess { out.addAll(it) }
        }
        return out.distinctBy { it.url.ifBlank { it.title } }.take(maxItems)
    }

    private fun queryWikipediaAutoLang(
        encodedQuery: String,
        isArabic: Boolean,
        fetcher: (String) -> String
    ): List<WebSearchSource> {
        val primaryLang = if (isArabic) "ar" else "en"
        val primary = queryWikipedia(primaryLang, encodedQuery, fetcher)
        if (primary.isNotEmpty() || !isArabic) return primary
        return queryWikipedia("en", encodedQuery, fetcher)
    }

    private fun queryWikipedia(
        lang: String,
        encodedQuery: String,
        fetcher: (String) -> String
    ): List<WebSearchSource> {
        val searchUrl = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encodedQuery&srlimit=3&utf8=&format=json"
        val body = fetcher(searchUrl)
        val root = SimpleJson.parseObject(body) ?: return emptyList()
        val queryObj = SimpleJson.asObject(root["query"]) ?: return emptyList()
        val searchArr = SimpleJson.asArray(queryObj["search"]) ?: return emptyList()
        val out = mutableListOf<WebSearchSource>()
        for (i in searchArr.indices) {
            val item = SimpleJson.asObject(searchArr[i]) ?: continue
            val title = SimpleJson.asString(item["title"]).trim()
            val snippetHtml = SimpleJson.asString(item["snippet"])
            val cleanSnippet = stripHtmlTags(snippetHtml)
            var dateHint = ISO_DATE_PREFIX_REGEX.find(SimpleJson.asString(item["timestamp"]))?.groupValues?.getOrNull(1)
            if (title.isNotEmpty()) {
                val pageUrl = "https://$lang.wikipedia.org/wiki/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8")}"
                val fullExtract = if (i == 0) {
                    val restUrl = "https://$lang.wikipedia.org/api/rest_v1/page/summary/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8")}"
                    runCatching {
                        val restObj = SimpleJson.parseObject(fetcher(restUrl))
                        if (dateHint.isNullOrBlank()) {
                            dateHint = ISO_DATE_PREFIX_REGEX.find(SimpleJson.asString(restObj?.get("timestamp")))?.groupValues?.getOrNull(1)
                        }
                        SimpleJson.asString(restObj?.get("extract")).trim()
                    }.getOrDefault("")
                } else ""
                out.add(
                    WebSearchSource(
                        title = title,
                        snippet = fullExtract.ifBlank { cleanSnippet },
                        url = pageUrl,
                        provider = "Wikipedia ($lang)",
                        sourceDomain = "$lang.wikipedia.org",
                        publishedDate = dateHint,
                        engineName = "Wikipedia"
                    )
                )
            }
        }
        return out
    }

    fun parseDuckDuckGoJson(jsonText: String): List<WebSearchSource> {
        if (jsonText.isBlank()) return emptyList()
        val root = SimpleJson.parseObject(jsonText) ?: return emptyList()
        val out = mutableListOf<WebSearchSource>()
        val abstractText = SimpleJson.asString(root["AbstractText"]).trim()
        val heading = SimpleJson.asString(root["Heading"]).trim()
        val abstractUrl = SimpleJson.asString(root["AbstractURL"]).trim()
        if (abstractText.isNotBlank()) {
            val safeUrl = if (abstractUrl.startsWith("https://", ignoreCase = true)) abstractUrl else "https://duckduckgo.com"
            val abstractSource = SimpleJson.asString(root["AbstractSource"]).trim()
            out.add(
                WebSearchSource(
                    title = heading.ifBlank { "DuckDuckGo" },
                    snippet = abstractText,
                    url = safeUrl,
                    provider = if (abstractSource.isNotBlank()) "DuckDuckGo ($abstractSource)" else "DuckDuckGo",
                    sourceDomain = extractDomain(safeUrl),
                    publishedDate = null,
                    engineName = "DuckDuckGo"
                )
            )
        }
        val related = SimpleJson.asArray(root["RelatedTopics"])
        if (related != null) {
            for (i in 0 until minOf(related.size, 3)) {
                val obj = SimpleJson.asObject(related[i]) ?: continue
                val text = SimpleJson.asString(obj["Text"]).trim()
                val firstUrl = SimpleJson.asString(obj["FirstURL"]).trim()
                if (text.isNotBlank()) {
                    val safeUrl = if (firstUrl.startsWith("https://", ignoreCase = true)) firstUrl else "https://duckduckgo.com"
                    out.add(
                        WebSearchSource(
                            title = text.substringBefore(" - ").take(64),
                            snippet = text,
                            url = safeUrl,
                            provider = "DuckDuckGo",
                            sourceDomain = extractDomain(safeUrl),
                            publishedDate = null,
                            engineName = "DuckDuckGo"
                        )
                    )
                }
            }
        }
        return out
    }

    /**
     * Graceful parser for DuckDuckGo public HTML results endpoint (`html.duckduckgo.com/html/`).
     * Returns emptyList() safely if page layout changes or markup is malformed.
     */
    fun parseDuckDuckGoHtml(html: String, maxItems: Int = 5): List<WebSearchSource> {
        if (html.isBlank()) return emptyList()
        val linkMatches = DDG_HTML_LINK_REGEX.findAll(html).toList()
        if (linkMatches.isEmpty()) return emptyList()
        val snippetMatches = DDG_HTML_SNIPPET_REGEX.findAll(html).toList()

        val out = mutableListOf<WebSearchSource>()
        for (i in linkMatches.indices) {
            if (out.size >= maxItems) break
            val rawHref = linkMatches[i].groupValues.getOrNull(1).orEmpty().trim()
            val resolvedUrl = resolveDuckDuckGoHref(rawHref) ?: continue
            val title = stripHtmlTags(linkMatches[i].groupValues.getOrNull(2).orEmpty())
            val snippet = stripHtmlTags(snippetMatches.getOrNull(i)?.groupValues?.getOrNull(1).orEmpty())
            if (title.isBlank() && snippet.isBlank()) continue
            out.add(
                WebSearchSource(
                    title = title.ifBlank { extractDomain(resolvedUrl) },
                    snippet = snippet.ifBlank { title },
                    url = resolvedUrl,
                    provider = "DuckDuckGo",
                    sourceDomain = extractDomain(resolvedUrl),
                    publishedDate = null,
                    engineName = "DuckDuckGo"
                )
            )
        }
        return out
    }

    private fun resolveDuckDuckGoHref(rawHref: String): String? {
        if (rawHref.isBlank()) return null
        val decodedHref = if (rawHref.contains("uddg=")) {
            val encodedTarget = rawHref.substringAfter("uddg=").substringBefore('&')
            runCatching { URLDecoder.decode(encodedTarget, "UTF-8") }.getOrDefault(rawHref)
        } else if (rawHref.startsWith("//")) {
            "https:$rawHref"
        } else {
            rawHref
        }
        return decodedHref.takeIf { isValidHttpsUrl(it) }
    }

    fun parseSearxngJson(jsonText: String, maxItems: Int = 8): List<WebSearchSource> {
        if (jsonText.isBlank()) return emptyList()
        val root = SimpleJson.parseObject(jsonText) ?: return emptyList()
        val results = SimpleJson.asArray(root["results"]) ?: return emptyList()
        val out = mutableListOf<WebSearchSource>()
        for (i in 0 until minOf(results.size, maxItems)) {
            val item = SimpleJson.asObject(results[i]) ?: continue
            val url = SimpleJson.asString(item["url"]).trim()
            if (!url.startsWith("https://", ignoreCase = true)) continue
            val dateRaw = SimpleJson.asString(item["publishedDate"]).trim()
            val dateHint = ISO_DATE_PREFIX_REGEX.find(dateRaw)?.groupValues?.getOrNull(1)
                ?: dateRaw.takeIf { it.isNotBlank() }?.take(16)
            val cleanedSnippet = stripHtmlTags(SimpleJson.asString(item["content"]))
            val withDate = if (!dateHint.isNullOrBlank()) "[$dateHint] $cleanedSnippet" else cleanedSnippet
            val subEngine = SimpleJson.asString(item["engine"]).trim()
            out.add(
                WebSearchSource(
                    title = SimpleJson.asString(item["title"]).trim(),
                    snippet = withDate.take(700),
                    url = url,
                    provider = if (subEngine.isNotBlank()) "SearXNG ($subEngine)" else "SearXNG",
                    sourceDomain = extractDomain(url),
                    publishedDate = dateHint,
                    engineName = "SearXNG"
                )
            )
        }
        return out
    }

    fun stripHtmlTags(html: String): String {
        return html
            .replace(Regex("(?is)<(script|style|nav|footer|aside|noscript)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]*>"), " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun httpGetHttps(rawUrl: String): String {
        val url = URL(rawUrl)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "Only HTTPS search endpoints are permitted."
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json,text/html;q=0.8")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error("HTTP $code")
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                var total = 0
                while (total < 262_144) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                    total += n
                }
                return sb.toString()
            }
        } finally {
            conn.disconnect()
        }
    }

    internal object SimpleJson {
        fun parseObject(text: String): Map<String, Any?>? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("{")) return null
            val parser = Parser(trimmed)
            return asObject(parser.readValue())
        }

        @Suppress("UNCHECKED_CAST")
        fun asObject(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun asArray(v: Any?): List<Any?>? = v as? List<Any?>

        fun asString(v: Any?): String = when (v) {
            is String -> v
            null -> ""
            else -> v.toString()
        }

        private class Parser(private val src: String) {
            private var pos = 0

            fun readValue(): Any? {
                skipWs()
                if (pos >= src.length) return null
                return when (src[pos]) {
                    '{' -> readObject()
                    '[' -> readArray()
                    '"' -> readString()
                    't', 'f' -> readBoolean()
                    'n' -> readNull()
                    else -> readNumber()
                }
            }

            private fun readObject(): Map<String, Any?> {
                val map = LinkedHashMap<String, Any?>()
                pos++ // '{'
                skipWs()
                if (pos < src.length && src[pos] == '}') {
                    pos++
                    return map
                }
                while (pos < src.length) {
                    skipWs()
                    if (pos >= src.length || src[pos] != '"') break
                    val key = readString()
                    skipWs()
                    if (pos < src.length && src[pos] == ':') pos++
                    val value = readValue()
                    map[key] = value
                    skipWs()
                    if (pos < src.length && src[pos] == ',') {
                        pos++
                    } else {
                        if (pos < src.length && src[pos] == '}') pos++
                        break
                    }
                }
                return map
            }

            private fun readArray(): List<Any?> {
                val list = ArrayList<Any?>()
                pos++ // '['
                skipWs()
                if (pos < src.length && src[pos] == ']') {
                    pos++
                    return list
                }
                while (pos < src.length) {
                    list.add(readValue())
                    skipWs()
                    if (pos < src.length && src[pos] == ',') {
                        pos++
                    } else {
                        if (pos < src.length && src[pos] == ']') pos++
                        break
                    }
                }
                return list
            }

            private fun readString(): String {
                pos++ // '"'
                val sb = StringBuilder()
                while (pos < src.length) {
                    val ch = src[pos++]
                    if (ch == '"') return sb.toString()
                    if (ch == '\\' && pos < src.length) {
                        when (val esc = src[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 <= src.length) {
                                    val hex = src.substring(pos, pos + 4)
                                    pos += 4
                                    hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                }
                            }
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(ch)
                    }
                }
                return sb.toString()
            }

            private fun readBoolean(): Boolean {
                return if (src.startsWith("true", pos)) {
                    pos += 4
                    true
                } else {
                    if (src.startsWith("false", pos)) pos += 5 else pos++
                    false
                }
            }

            private fun readNull(): Any? {
                if (src.startsWith("null", pos)) pos += 4 else pos++
                return null
            }

            private fun readNumber(): String {
                val start = pos
                while (pos < src.length && src[pos] !in charArrayOf(',', '}', ']', ' ', '\n', '\r', '\t')) {
                    pos++
                }
                return src.substring(start, pos)
            }

            private fun skipWs() {
                while (pos < src.length && src[pos].isWhitespace()) {
                    pos++
                }
            }
        }
    }
}
