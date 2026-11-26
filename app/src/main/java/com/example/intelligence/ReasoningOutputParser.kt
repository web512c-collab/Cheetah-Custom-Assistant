package com.example.intelligence

import com.example.core.model.ReasoningSplitResult

/**
 * Separates hidden model reasoning (<think>...</think>, <thought>...</thought>,
 * <|thought|>...</|thought|>, [THOUGHT]...[/THOUGHT], <reasoning>...</reasoning>)
 * from final user-facing answers, safely suppresses partial tags during streaming,
 * and formats concise spoken versions for long replies with voice continuation support.
 */
object ReasoningOutputParser {

    private data class TagPair(val open: String, val close: String)

    private val TAG_PAIRS = listOf(
        TagPair("<think>", "</think>"),
        TagPair("<thought>", "</thought>"),
        TagPair("<|thought|>", "</|thought|>"),
        TagPair("<|thought|>", "<|/thought|>"),
        TagPair("[THOUGHT]", "[/THOUGHT]"),
        TagPair("<reasoning>", "</reasoning>")
    )

    private val ACTION_TAG_REGEX = Regex("\\[\\[PROPOSE_ACTION:([^\\]]+)\\]\\]")

    private val STREAM_GUARD_OPENERS = listOf(
        "<think", "<thought", "<|thought", "[THOUGHT", "<reasoning", "[[PROPOSE_ACTION"
    )

    fun splitReasoningAndAnswer(rawOutput: String): ReasoningSplitResult {
        if (rawOutput.isBlank()) {
            return ReasoningSplitResult(finalAnswer = "", reasoningText = "")
        }

        val reasoningChunks = mutableListOf<String>()
        val cleanBuilder = StringBuilder()
        var idx = 0
        var hasOpen = false
        var truncatedByTag = false
        val lower = rawOutput.lowercase()

        while (idx < rawOutput.length) {
            var earliestStart = -1
            var matchedPair: TagPair? = null

            for (pair in TAG_PAIRS) {
                val pos = lower.indexOf(pair.open.lowercase(), startIndex = idx)
                if (pos >= 0 && (earliestStart == -1 || pos < earliestStart)) {
                    earliestStart = pos
                    matchedPair = pair
                }
            }

            if (earliestStart == -1 || matchedPair == null) {
                cleanBuilder.append(rawOutput.substring(idx))
                break
            }

            if (earliestStart > idx) {
                cleanBuilder.append(rawOutput.substring(idx, earliestStart))
            }

            val contentStart = earliestStart + matchedPair.open.length
            val closePos = lower.indexOf(matchedPair.close.lowercase(), startIndex = contentStart)
            if (closePos >= 0) {
                val thought = rawOutput.substring(contentStart, closePos).trim()
                if (thought.isNotEmpty()) {
                    reasoningChunks.add(thought)
                }
                idx = closePos + matchedPair.close.length
            } else {
                // Unclosed reasoning tag (e.g. cut off mid-thought or while streaming)
                hasOpen = true
                truncatedByTag = true
                val partialThought = rawOutput.substring(contentStart).trim()
                if (partialThought.isNotEmpty()) {
                    reasoningChunks.add(partialThought)
                }
                break
            }
        }

        val finalVisible = cleanBuilder.toString()
            .replace(ACTION_TAG_REGEX, "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        return ReasoningSplitResult(
            finalAnswer = finalVisible,
            reasoningText = reasoningChunks.joinToString("\n\n").trim(),
            hasOpenThinkTag = hasOpen,
            wasTruncatedByTag = truncatedByTag
        )
    }

    /**
     * Streaming-safe filter: returns ONLY visible final-answer text emitted so far,
     * hiding closed or currently open reasoning blocks and trailing partial opening tags
     * so TTS never speaks internal thoughts.
     */
    fun extractVisibleStreamText(rawSoFar: String): String {
        val split = splitReasoningAndAnswer(rawSoFar)
        var visible = split.finalAnswer
        if (split.hasOpenThinkTag) {
            return visible
        }
        // Trim any incomplete tag prefix at the very end (e.g. "<th" or "[[PROPOSE")
        val lastLt = visible.lastIndexOf('<')
        val lastBr = visible.lastIndexOf('[')
        val tailStart = maxOf(lastLt, lastBr)
        if (tailStart >= 0 && visible.length - tailStart <= 20) {
            val tail = visible.substring(tailStart).lowercase()
            if (STREAM_GUARD_OPENERS.any { opener ->
                    opener.lowercase().startsWith(tail) || tail.startsWith(opener.lowercase())
                }
            ) {
                visible = visible.substring(0, tailStart)
            }
        }
        return visible.trimEnd()
    }

    fun extractProposedActionRawTags(rawOutput: String): List<String> {
        return ACTION_TAG_REGEX.findAll(rawOutput)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    data class SpeechSummary(
        val spokenText: String,
        val hasContinuation: Boolean,
        val remainingText: String
    )

    fun buildConciseSpeechVersion(
        fullAnswer: String,
        maxSentences: Int = 3,
        maxChars: Int = 240,
        continuePromptAr: String = "تحب أكمل الباقي؟"
    ): SpeechSummary {
        val clean = fullAnswer.trim()
        if (clean.isEmpty()) {
            return SpeechSummary("", false, "")
        }
        val sentences = clean.split(Regex("(?<=[.!?؟\\n])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (clean.length <= maxChars && sentences.size <= maxSentences) {
            return SpeechSummary(spokenText = clean, hasContinuation = false, remainingText = "")
        }

        val spokenParts = mutableListOf<String>()
        var charCount = 0
        for (s in sentences) {
            if (spokenParts.isNotEmpty() && (spokenParts.size >= maxSentences || charCount + s.length > maxChars)) {
                break
            }
            spokenParts.add(s)
            charCount += s.length + 1
        }

        val prefix = if (spokenParts.isNotEmpty()) {
            spokenParts.joinToString(" ")
        } else {
            clean.take(maxChars).substringBeforeLast(' ', clean.take(maxChars))
        }

        val remaining = if (spokenParts.size in 1 until sentences.size) {
            sentences.drop(spokenParts.size).joinToString(" ").trim()
        } else {
            clean.removePrefix(prefix).trim()
        }

        if (remaining.length < 25) {
            return SpeechSummary(spokenText = clean, hasContinuation = false, remainingText = "")
        }

        val isEnglish = prefix.count { it in 'a'..'z' || it in 'A'..'Z' } >
            prefix.count { it in '\u0600'..'\u06FF' } * 2
        val offer = if (isEnglish) "Would you like me to continue?" else continuePromptAr
        return SpeechSummary(
            spokenText = "$prefix $offer",
            hasContinuation = true,
            remainingText = remaining
        )
    }
}
