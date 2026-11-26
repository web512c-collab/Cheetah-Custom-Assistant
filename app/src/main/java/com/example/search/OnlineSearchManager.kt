package com.example.search

import com.example.core.model.WebSearchOutcome
import com.example.core.model.WebSearchSource
import com.example.voice.CheetahRuntimeBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap

/**
 * Keyless HTTPS internet search manager (Part D).
 * Privacy & security guarantees:
 * 1. Strictly gated behind settings.internetSearchEnabled (OFF by default).
 * 2. Sends ONLY the cleaned search query text (never mic audio, conversation memory, or PII).
 * 3. HTTPS-only with explicit connect/read timeouts, User-Agent, LRU TTL cache, and rate limiter.
 * 4. Uses keyless public endpoints:
 *    - Optional user-configured HTTPS SearXNG JSON endpoint
 *    - Wikipedia REST / Search API (Arabic for Arabic queries, English fallback)
 *    - DuckDuckGo Instant Answer JSON API
 */
object OnlineSearchManager {

    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val CACHE_TTL_MS = 15 * 60_000L
    private const val MAX_CACHE_SIZE = 24
    private const val MIN_REQUEST_INTERVAL_MS = 1_200L
    private const val USER_AGENT = "CheetahCustomAssistant/0.4.0 (Android; KeylessSearch)"

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

    suspend fun search(
        query: String,
        internetSearchEnabled: Boolean,
        customSearxngUrl: String = "",
        maxResults: Int = 5,
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
        val now = clockMs()
        val cacheKey = "${cleanQuery.lowercase()}|${customSearxngUrl.trim()}|$requestedCap"
        synchronized(cacheLock) {
            val hit = lruCache[cacheKey]
            if (hit != null && (now - hit.cachedAtMs) <= CACHE_TTL_MS) {
                return@withContext hit.outcome.copy(fromCache = true)
            }
        }

        val elapsedSinceLast = now - lastNetworkCallMs
        if (elapsedSinceLast in 0 until MIN_REQUEST_INTERVAL_MS) {
            kotlinx.coroutines.delay(MIN_REQUEST_INTERVAL_MS - elapsedSinceLast)
        }
        lastNetworkCallMs = clockMs()

        CheetahRuntimeBus.setNetworkSearching(true)
        try {
            val sources = mutableListOf<WebSearchSource>()
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")

            // 1. Optional user-configured HTTPS SearXNG instance
            if (isValidHttpsUrl(customSearxngUrl)) {
                val base = customSearxngUrl.trim().trimEnd('/')
                val searxUrl = "$base/search?q=$encoded&format=json&language=ar"
                runCatching {
                    parseSearxngJson(fetcher(searxUrl), maxItems = requestedCap)
                }.onSuccess { list ->
                    sources.addAll(list.take(requestedCap))
                }
            }

            // 2. Wikipedia OpenSearch + Summary (keyless HTTPS, great for Arabic & general facts)
            if (sources.size < requestedCap) {
                val isArabic = cleanQuery.any { it in '\u0600'..'\u06FF' }
                val wikiLang = if (isArabic) "ar" else "en"
                runCatching {
                    queryWikipedia(wikiLang, encoded, fetcher)
                }.onSuccess { list ->
                    sources.addAll(list)
                }
                if (sources.isEmpty() && isArabic) {
                    runCatching {
                        queryWikipedia("en", encoded, fetcher)
                    }.onSuccess { list ->
                        sources.addAll(list)
                    }
                }
            }

            // 3. DuckDuckGo Instant Answer JSON API (keyless HTTPS)
            if (sources.size < requestedCap) {
                val ddgUrl = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
                runCatching {
                    parseDuckDuckGoJson(fetcher(ddgUrl))
                }.onSuccess { list ->
                    sources.addAll(list)
                }
            }

            val deduped = sources
                .filter { it.snippet.isNotBlank() || it.title.isNotBlank() }
                .distinctBy { it.url.ifBlank { it.title } }
                .take(requestedCap)

            val outcome = if (deduped.isEmpty()) {
                WebSearchOutcome(
                    query = cleanQuery,
                    summaryText = "ملقتش نتائج واضحة عن «$cleanQuery» في المصادر المتاحة.",
                    sources = emptyList()
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
                    fromCache = false
                )
            }

            if (deduped.isNotEmpty()) {
                synchronized(cacheLock) {
                    lruCache[cacheKey] = CacheEntry(outcome, clockMs())
                }
            }
            outcome
        } catch (t: Throwable) {
            WebSearchOutcome(
                query = cleanQuery,
                summaryText = "",
                sources = emptyList(),
                errorAr = "تعذر الاتصال بمصدر البحث (${t.message ?: "network error"})."
            )
        } finally {
            CheetahRuntimeBus.setNetworkSearching(false)
        }
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
            if (title.isNotEmpty()) {
                val pageUrl = "https://$lang.wikipedia.org/wiki/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8")}"
                // Try richer REST summary for top hit
                val fullExtract = if (i == 0) {
                    val restUrl = "https://$lang.wikipedia.org/api/rest_v1/page/summary/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8")}"
                    runCatching {
                        val restObj = SimpleJson.parseObject(fetcher(restUrl))
                        SimpleJson.asString(restObj?.get("extract")).trim()
                    }.getOrDefault("")
                } else ""
                out.add(
                    WebSearchSource(
                        title = title,
                        snippet = fullExtract.ifBlank { cleanSnippet },
                        url = pageUrl,
                        provider = "Wikipedia ($lang)"
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
            out.add(
                WebSearchSource(
                    title = heading.ifBlank { "DuckDuckGo" },
                    snippet = abstractText,
                    url = abstractUrl.ifBlank { "https://duckduckgo.com" },
                    provider = SimpleJson.asString(root["AbstractSource"]).ifBlank { "DuckDuckGo" }
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
                    out.add(
                        WebSearchSource(
                            title = text.substringBefore(" - ").take(64),
                            snippet = text,
                            url = firstUrl.ifBlank { "https://duckduckgo.com" },
                            provider = "DuckDuckGo"
                        )
                    )
                }
            }
        }
        return out
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
            val dateHint = SimpleJson.asString(item["publishedDate"]).trim()
            val cleanedSnippet = stripHtmlTags(SimpleJson.asString(item["content"]))
            val withDate = if (dateHint.isNotBlank()) "[$dateHint] $cleanedSnippet" else cleanedSnippet
            out.add(
                WebSearchSource(
                    title = SimpleJson.asString(item["title"]).trim(),
                    snippet = withDate.take(700),
                    url = url,
                    provider = SimpleJson.asString(item["engine"]).ifBlank { "SearXNG" }
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
            setRequestProperty("Accept", "application/json")
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
