package com.example.llm

import com.example.core.model.ChatTemplateFamily
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GgufParsedHeader(
    val version: Int,
    val tensorCount: Long,
    val kvCount: Long,
    val architecture: String,
    val modelName: String,
    val quantizationGuess: String,
    val chatTemplateRaw: String = "",
    val chatTemplateFamily: ChatTemplateFamily = ChatTemplateFamily.GENERIC_UNKNOWN
)

data class RamCheckResult(
    val isSafeToLoad: Boolean,
    val estimatedRequiredMb: Long,
    val availableRamMb: Long,
    val warningMessageAr: String?
)

/**
 * Pure-Kotlin GGUF v2/v3 header parser & low-RAM safety estimator.
 * Validates the GGUF magic bytes ("GGUF") and reads architecture, name, and tokenizer.chat_template
 * metadata without loading tensor weights.
 */
object GgufHeaderParser {

    private val QUANT_REGEX = Regex(
        "(IQ[1-4]_[A-Z0-9]+|Q[2-8]_[A-Z0-9_]+|F16|BF16|F32)",
        RegexOption.IGNORE_CASE
    )

    fun extractQuantizationFromFilename(fileName: String): String {
        val match = QUANT_REGEX.find(fileName)
        return match?.value?.uppercase() ?: "GGUF"
    }

    fun evaluateRamSafety(
        fileSizeBytes: Long,
        availableRamMb: Long,
        totalRamMb: Long,
        contextLength: Int
    ): RamCheckResult {
        val modelMb = (fileSizeBytes / (1024L * 1024L)).coerceAtLeast(1L)
        val kvOverheadMb = ((contextLength / 512L) * 48L).coerceAtLeast(64L)
        val runtimeOverheadMb = 180L
        val estimatedMb = modelMb + kvOverheadMb + runtimeOverheadMb

        return when {
            availableRamMb <= 0L -> RamCheckResult(
                isSafeToLoad = true,
                estimatedRequiredMb = estimatedMb,
                availableRamMb = availableRamMb,
                warningMessageAr = null
            )
            estimatedMb > (availableRamMb * 1.15).toLong() -> RamCheckResult(
                isSafeToLoad = false,
                estimatedRequiredMb = estimatedMb,
                availableRamMb = availableRamMb,
                warningMessageAr = "حجم النموذج التقريبي ($estimatedMb MB مع الكونتكست) أكبر من الرام المتاح حالياً ($availableRamMb MB من إجمالي $totalRamMb MB). اختر نموذج أصغر (مثل Q4_K_M 0.5B–1.5B) أو اقفل تطبيقات الخلفية."
            )
            estimatedMb > (availableRamMb * 0.80).toLong() -> RamCheckResult(
                isSafeToLoad = true,
                estimatedRequiredMb = estimatedMb,
                availableRamMb = availableRamMb,
                warningMessageAr = "تنبيه رام: النموذج يحتاج ~$estimatedMb MB والمتاح $availableRamMb MB — يفضل إبقاء context <= 1024."
            )
            else -> RamCheckResult(
                isSafeToLoad = true,
                estimatedRequiredMb = estimatedMb,
                availableRamMb = availableRamMb,
                warningMessageAr = null
            )
        }
    }

    fun parseHeader(inputStream: InputStream, fileNameHint: String = ""): Result<GgufParsedHeader> {
        return runCatching {
            val dis = DataInputStream(BufferedInputStream(inputStream, 16384))
            val magicBytes = ByteArray(4)
            dis.readFully(magicBytes)
            val magic = String(magicBytes, Charsets.US_ASCII)
            require(magic == "GGUF") { "الملف ليس بصيغة GGUF صالحة (magic=$magic)." }

            val version = readLeInt(dis)
            require(version in 1..10) { "إصدار GGUF غير مدعوم: v$version" }

            val tensorCount = readLeLong(dis)
            val kvCount = readLeLong(dis)

            var architecture = ""
            var modelName = ""
            var chatTemplate = ""

            // Inspect up to 256 metadata KVs safely so we capture tokenizer.chat_template even after vocab arrays
            val inspectCap = kvCount.coerceIn(0L, 256L).toInt()
            for (i in 0 until inspectCap) {
                val key = readGgufString(dis, maxLen = 512) ?: break
                val valType = readLeInt(dis)
                when {
                    key == "general.architecture" && valType == 8 -> {
                        architecture = readGgufString(dis, maxLen = 128).orEmpty()
                    }
                    key == "general.name" && valType == 8 -> {
                        modelName = readGgufString(dis, maxLen = 256).orEmpty()
                    }
                    (key == "tokenizer.chat_template" || key.startsWith("tokenizer.chat_template.")) && valType == 8 -> {
                        val candidate = readGgufString(dis, maxLen = 16384).orEmpty()
                        if (chatTemplate.isEmpty() || key == "tokenizer.chat_template") {
                            chatTemplate = candidate
                        }
                    }
                    else -> {
                        if (!skipGgufValue(dis, valType)) break
                    }
                }
                if (architecture.isNotEmpty() && modelName.isNotEmpty() && chatTemplate.isNotEmpty()) {
                    break
                }
            }

            val resolvedArch = architecture.ifBlank { "llama/gguf" }
            val family = ChatTemplateFormatter.detectTemplateFamily(chatTemplate, resolvedArch)

            GgufParsedHeader(
                version = version,
                tensorCount = tensorCount,
                kvCount = kvCount,
                architecture = resolvedArch,
                modelName = modelName.ifBlank { fileNameHint.substringBeforeLast('.') },
                quantizationGuess = extractQuantizationFromFilename(fileNameHint),
                chatTemplateRaw = chatTemplate,
                chatTemplateFamily = family
            )
        }
    }

    private fun readLeInt(dis: DataInputStream): Int {
        val b = ByteArray(4)
        dis.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readLeLong(dis: DataInputStream): Long {
        val b = ByteArray(8)
        dis.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun readGgufString(dis: DataInputStream, maxLen: Int): String? {
        val len = readLeLong(dis)
        if (len < 0 || len > 4L * 1024L * 1024L) return null
        if (len > maxLen) {
            skipFully(dis, len)
            return ""
        }
        val buf = ByteArray(len.toInt())
        dis.readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    private fun skipGgufValue(dis: DataInputStream, type: Int): Boolean {
        return when (type) {
            0, 1, 7 -> { skipFully(dis, 1); true } // u8, i8, bool
            2, 3 -> { skipFully(dis, 2); true }    // u16, i16
            4, 5, 6 -> { skipFully(dis, 4); true } // u32, i32, f32
            8 -> readGgufString(dis, maxLen = 0) != null
            9 -> {
                val elemType = readLeInt(dis)
                val count = readLeLong(dis)
                if (count < 0 || count > 500_000L) return false
                val fixedSize = when (elemType) {
                    0, 1, 7 -> 1L
                    2, 3 -> 2L
                    4, 5, 6 -> 4L
                    10, 11, 12 -> 8L
                    else -> -1L
                }
                if (fixedSize > 0L) {
                    skipFully(dis, count * fixedSize)
                    true
                } else {
                    for (j in 0 until count.toInt()) {
                        if (!skipGgufValue(dis, elemType)) return false
                    }
                    true
                }
            }
            10, 11, 12 -> { skipFully(dis, 8); true } // u64, i64, f64
            else -> false
        }
    }

    private fun skipFully(dis: DataInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = dis.skip(remaining)
            if (skipped <= 0) {
                if (dis.read() < 0) throw EOFException("Unexpected EOF in GGUF header")
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }
}
