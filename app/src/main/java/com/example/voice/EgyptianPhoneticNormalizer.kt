package com.example.voice

import kotlin.math.min

/**
 * Normalizes Egyptian Colloquial Arabic text and speech transcripts so wake-word
 * matching and local NLU remain resilient to dialectal spelling and STT phonetic drift.
 */
object EgyptianPhoneticNormalizer {

    private val DIACRITICS_REGEX = Regex("[\u064B-\u065F\u0670\u0640]")
    private val PUNCTUATION_REGEX = Regex("[^\\p{L}\\p{N}\\s]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    private val WAKE_PREFIXES = listOf("يا ", "يا", "هاي ", "اهلا ", "الو ", "hey ", "hi ", "ok ")

    fun normalizeForNlu(input: String): String {
        val stripped = DIACRITICS_REGEX.replace(input.trim().lowercase(), "")
        val sb = StringBuilder(stripped.length)
        for (ch in stripped) {
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ة' -> sb.append('ه')
                'ى' -> sb.append('ي')
                'ؤ' -> sb.append('و')
                'ئ' -> sb.append('ي')
                else -> sb.append(ch)
            }
        }
        val cleaned = PUNCTUATION_REGEX.replace(sb.toString(), " ")
        return MULTI_SPACE_REGEX.replace(cleaned, " ").trim()
    }

    /**
     * Applies Egyptian phonetic equivalence folding (e.g., ث->س/ت, ذ->ز/د, ظ->ز, تش/ش)
     * for tolerant wake-word and keyword matching against offline STT output.
     */
    fun toEgyptianPhoneticKey(input: String): String {
        var norm = normalizeForNlu(input)
        // Common Egyptian colloquial STT swaps & English transliterations
        norm = norm
            .replace("cheetah", "شيتا")
            .replace("shetah", "شيتا")
            .replace("shita", "شيتا")
            .replace("تشيتا", "شيتا")
            .replace("شيطا", "شيتا")
            .replace("شيته", "شيتا")
            .replace("جوجل", "جوجل")
            .replace("غوغل", "جوجل")
            .replace("گوگل", "جوجل")
            .replace("واتس اب", "واتساب")
            .replace("واتس", "واتساب")
            .replace("يوتوب", "يوتيوب")
            .replace("فيس بوك", "فيسبوك")
            .replace("انستغرام", "انستجرام")
            .replace("تليغرام", "تليجرام")

        val out = StringBuilder(norm.length)
        for (ch in norm) {
            when (ch) {
                'ث' -> out.append('س')
                'ذ' -> out.append('ز')
                'ظ' -> out.append('ز')
                'ق' -> out.append('ا') // Egyptian glottal stop /q/ -> /ʔ/
                'ض' -> out.append('د')
                'ط' -> out.append('ت')
                'ص' -> out.append('س')
                else -> out.append(ch)
            }
        }
        return MULTI_SPACE_REGEX.replace(out.toString(), " ").trim()
    }

    fun matchesWakePhrase(transcript: String, configuredWakePhrase: String, sensitivity: Float): Boolean {
        val normTranscript = normalizeForNlu(transcript)
        val normWake = normalizeForNlu(configuredWakePhrase)
        if (normTranscript.isEmpty() || normWake.isEmpty()) return false

        if (normTranscript.contains(normWake)) return true

        val phoneticTranscript = toEgyptianPhoneticKey(transcript)
        val phoneticWake = toEgyptianPhoneticKey(configuredWakePhrase)
        if (phoneticTranscript.contains(phoneticWake)) return true

        // Strip common vocative prefix ("يا ") to match core name
        val coreWake = stripVocativePrefix(phoneticWake)
        if (coreWake.length >= 3 && phoneticTranscript.contains(coreWake)) return true

        // Fuzzy word-window Levenshtein check tuned by sensitivity slider
        val maxAllowedDistance = when {
            sensitivity >= 0.8f -> 2
            sensitivity >= 0.5f -> 1
            else -> 0
        }
        if (maxAllowedDistance == 0) return false

        val words = phoneticTranscript.split(" ").filter { it.isNotBlank() }
        val wakeWords = phoneticWake.split(" ").filter { it.isNotBlank() }
        val windowSize = wakeWords.size.coerceAtLeast(1)
        for (i in 0..(words.size - windowSize).coerceAtLeast(0)) {
            val slice = words.subList(i, min(i + windowSize, words.size)).joinToString(" ")
            if (levenshtein(slice, phoneticWake) <= maxAllowedDistance) return true
        }
        return words.any { it.length >= 3 && levenshtein(it, coreWake) <= maxAllowedDistance }
    }

    fun stripWakePhrase(transcript: String, configuredWakePhrase: String): String {
        val normWake = normalizeForNlu(configuredWakePhrase)
        var result = transcript.trim()
        val lower = normalizeForNlu(result)
        if (normWake.isNotEmpty() && lower.startsWith(normWake)) {
            val tokensToDrop = normWake.split(" ").size
            val origTokens = result.split(MULTI_SPACE_REGEX)
            if (origTokens.size > tokensToDrop) {
                result = origTokens.drop(tokensToDrop).joinToString(" ")
            }
        }
        return result.trim()
    }

    private fun stripVocativePrefix(text: String): String {
        var current = text.trim()
        for (prefix in WAKE_PREFIXES) {
            if (current.startsWith(prefix)) {
                current = current.removePrefix(prefix).trim()
            }
        }
        return current
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = i - 1
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[b.length]
    }
}
