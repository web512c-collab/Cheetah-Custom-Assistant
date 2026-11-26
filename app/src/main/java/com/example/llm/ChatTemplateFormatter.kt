package com.example.llm

import com.example.core.model.ChatTemplateFamily
import com.example.core.model.ConversationTurnMessage

/**
 * Inspects GGUF `tokenizer.chat_template` / architecture metadata and formats multi-turn
 * prompts cleanly for ChatML, Llama 3, Gemma, Mistral, Qwen, Phi, or a safe generic format.
 */
object ChatTemplateFormatter {

    fun detectTemplateFamily(
        rawTemplate: String?,
        architecture: String? = null
    ): ChatTemplateFamily {
        val tmpl = rawTemplate.orEmpty()
        val arch = architecture.orEmpty().lowercase()

        if (tmpl.isNotBlank()) {
            return when {
                tmpl.contains("<|start_header_id|>") && tmpl.contains("<|eot_id|>") ->
                    ChatTemplateFamily.LLAMA3
                tmpl.contains("<start_of_turn>") && tmpl.contains("<end_of_turn>") ->
                    ChatTemplateFamily.GEMMA
                tmpl.contains("[INST]") && tmpl.contains("[/INST]") ->
                    ChatTemplateFamily.MISTRAL
                tmpl.contains("<|system|>") && tmpl.contains("<|user|>") && tmpl.contains("<|end|>") ->
                    ChatTemplateFamily.PHI
                tmpl.contains("<|im_start|>") && tmpl.contains("<|im_end|>") -> {
                    if (arch.contains("qwen")) ChatTemplateFamily.QWEN else ChatTemplateFamily.CHATML
                }
                else -> inferFromArchitectureOnly(arch)
            }
        }
        return inferFromArchitectureOnly(arch)
    }

    private fun inferFromArchitectureOnly(arch: String): ChatTemplateFamily {
        return when {
            arch.contains("qwen") -> ChatTemplateFamily.QWEN
            arch.contains("gemma") -> ChatTemplateFamily.GEMMA
            arch.contains("phi") -> ChatTemplateFamily.PHI
            arch.contains("mistral") -> ChatTemplateFamily.MISTRAL
            arch.contains("llama3") -> ChatTemplateFamily.LLAMA3
            else -> ChatTemplateFamily.GENERIC_UNKNOWN
        }
    }

    fun buildTemplateWarning(rawTemplate: String?, family: ChatTemplateFamily): String? {
        return when {
            rawTemplate.isNullOrBlank() && family == ChatTemplateFamily.GENERIC_UNKNOWN ->
                "لم يتم العثور على قالب محادثة (chat_template) داخل ملف GGUF — سيتم استخدام تنسيق عام آمن وقد تختلف جودة الردود."
            rawTemplate.isNullOrBlank() ->
                "ملف GGUF لا يحتوي على tokenizer.chat_template صريح؛ تم استنتاج قالب ${family.displayName} من البنية."
            !family.isKnown ->
                "قالب المحادثة داخل ملف GGUF غير قياسي — تم تفعيل التنسيق العام الآمن."
            else -> null
        }
    }

    fun formatPrompt(
        family: ChatTemplateFamily,
        systemPrompt: String,
        messages: List<ConversationTurnMessage>
    ): String {
        val cleanSystem = systemPrompt.trim()
        val cleanMessages = messages.filter { it.content.isNotBlank() }

        return when (family) {
            ChatTemplateFamily.CHATML,
            ChatTemplateFamily.QWEN -> formatChatMl(cleanSystem, cleanMessages)

            ChatTemplateFamily.LLAMA3 -> formatLlama3(cleanSystem, cleanMessages)

            ChatTemplateFamily.GEMMA -> formatGemma(cleanSystem, cleanMessages)

            ChatTemplateFamily.MISTRAL -> formatMistral(cleanSystem, cleanMessages)

            ChatTemplateFamily.PHI -> formatPhi(cleanSystem, cleanMessages)

            ChatTemplateFamily.GENERIC_UNKNOWN -> formatGeneric(cleanSystem, cleanMessages)
        }
    }

    private fun formatChatMl(system: String, messages: List<ConversationTurnMessage>): String = buildString {
        if (system.isNotEmpty()) {
            append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        }
        messages.forEach { msg ->
            val role = if (msg.role == "assistant") "assistant" else "user"
            append("<|im_start|>").append(role).append('\n').append(msg.content.trim()).append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    private fun formatLlama3(system: String, messages: List<ConversationTurnMessage>): String = buildString {
        if (system.isNotEmpty()) {
            append("<|start_header_id|>system<|end_header_id|>\n\n")
            append(system).append("<|eot_id|>")
        }
        messages.forEach { msg ->
            val role = if (msg.role == "assistant") "assistant" else "user"
            append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n")
            append(msg.content.trim()).append("<|eot_id|>")
        }
        append("<|start_header_id|>assistant<|end_header_id|>\n\n")
    }

    private fun formatGemma(system: String, messages: List<ConversationTurnMessage>): String = buildString {
        var injectedSystem = false
        messages.forEach { msg ->
            val isUser = msg.role != "assistant"
            val role = if (isUser) "user" else "model"
            append("<start_of_turn>").append(role).append('\n')
            if (isUser && !injectedSystem && system.isNotEmpty()) {
                append(system).append("\n\n")
                injectedSystem = true
            }
            append(msg.content.trim()).append("<end_of_turn>\n")
        }
        if (!injectedSystem && system.isNotEmpty()) {
            append("<start_of_turn>user\n").append(system).append("<end_of_turn>\n")
        }
        append("<start_of_turn>model\n")
    }

    private fun formatMistral(system: String, messages: List<ConversationTurnMessage>): String = buildString {
        var injectedSystem = false
        var i = 0
        while (i < messages.size) {
            val cur = messages[i]
            if (cur.role != "assistant") {
                append("[INST] ")
                if (!injectedSystem && system.isNotEmpty()) {
                    append(system).append("\n\n")
                    injectedSystem = true
                }
                append(cur.content.trim()).append(" [/INST]")
                val next = messages.getOrNull(i + 1)
                if (next != null && next.role == "assistant") {
                    append(' ').append(next.content.trim()).append("</s>")
                    i += 2
                    continue
                }
            } else {
                append(cur.content.trim()).append("</s>")
            }
            i++
        }
        if (messages.none { it.role != "assistant" } && system.isNotEmpty()) {
            append("[INST] ").append(system).append(" [/INST]")
        }
    }

    private fun formatPhi(system: String, messages: List<ConversationTurnMessage>): String = buildString {
        if (system.isNotEmpty()) {
            append("<|system|>\n").append(system).append("<|end|>\n")
        }
        messages.forEach { msg ->
            val tag = if (msg.role == "assistant") "<|assistant|>" else "<|user|>"
            append(tag).append('\n').append(msg.content.trim()).append("<|end|>\n")
        }
        append("<|assistant|>\n")
    }

    private fun formatGeneric(system: String, messages: List<ConversationTurnMessage>): String = buildString {
        if (system.isNotEmpty()) {
            append("### System:\n").append(system).append("\n\n")
        }
        messages.forEach { msg ->
            val label = if (msg.role == "assistant") "### Assistant:" else "### User:"
            append(label).append('\n').append(msg.content.trim()).append("\n\n")
        }
        append("### Assistant:\n")
    }
}
