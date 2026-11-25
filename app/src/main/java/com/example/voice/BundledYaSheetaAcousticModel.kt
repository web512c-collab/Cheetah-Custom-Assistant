package com.example.voice

import android.content.Context
import java.io.Closeable
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.CRC32
import kotlin.math.exp
import kotlin.math.max

data class BundledModelInferenceResult(
    val detected: Boolean,
    val confidence: Float,
    val neuralProbability: Float,
    val templateDtwScore: Float,
    val thresholdUsed: Float,
    val inferenceTimeUs: Long,
    val modelId: String
)

/**
 * Local binary keyword-spotting acoustic model loader & inference engine for the
 * Egyptian Arabic wake phrase 'يا شيتا' (/jaː ˈʃiː.ta/).
 *
 * Reads the bundled CHKW v1 Int8-quantized binary model from assets/models/cheetah_ya_sheeta_kws.bin
 * using zero-copy MappedByteBuffer (when uncompressed in APK) or InputStream fallback,
 * verifies its CRC32 integrity checksum, and runs purely offline on-device inference.
 */
class BundledYaSheetaAcousticModel private constructor(
    val sampleRateHz: Int,
    val timeSteps: Int,
    val numMfcc: Int,
    val inputDim: Int,
    val hiddenDim: Int,
    val numClasses: Int,
    val recommendedThreshold: Float,
    private val w1: FloatArray,
    private val b1: FloatArray,
    private val w2: FloatArray,
    private val b2: FloatArray,
    private val refMfccSequence: List<FloatArray>,
    val modelSizeBytes: Int,
    val checksumVerified: Boolean
) : Closeable {

    val modelId: String = "CHKW-v1:ya_sheeta (${modelSizeBytes}B, Int8)"

    fun evaluate(
        pcmUtterance: ShortArray,
        noiseFloorRms: Float = 0.010f,
        sensitivityOverride: Float? = null
    ): BundledModelInferenceResult {
        val startNs = System.nanoTime()
        val threshold = if (sensitivityOverride != null) {
            (0.84f - (sensitivityOverride.coerceIn(0.2f, 0.95f) * 0.42f)).coerceIn(0.42f, 0.76f)
        } else {
            recommendedThreshold
        }

        val tensor = AudioFeatureExtractor.extractKwsFeatureTensor(
            pcm = pcmUtterance,
            noiseFloorRms = noiseFloorRms,
            timeSteps = timeSteps
        ) ?: return BundledModelInferenceResult(
            detected = false,
            confidence = 0f,
            neuralProbability = 0f,
            templateDtwScore = 0f,
            thresholdUsed = threshold,
            inferenceTimeUs = (System.nanoTime() - startNs) / 1000L,
            modelId = modelId
        )

        val x = AudioFeatureExtractor.poolSegments64(tensor, segments = 4)

        // Layer 1: 64 -> 32 + ReLU
        val hidden = FloatArray(hiddenDim)
        for (j in 0 until hiddenDim) {
            var sum = b1[j]
            for (i in 0 until inputDim) {
                sum += x[i] * w1[i * hiddenDim + j]
            }
            hidden[j] = max(0f, sum)
        }

        // Layer 2: 32 -> 2 Softmax
        val logits = FloatArray(numClasses)
        for (k in 0 until numClasses) {
            var sum = b2[k]
            for (j in 0 until hiddenDim) {
                sum += hidden[j] * w2[j * numClasses + k]
            }
            logits[k] = sum
        }
        val maxLogit = max(logits[0], logits[1])
        val expBg = exp((logits[0] - maxLogit).toDouble())
        val expWake = exp((logits[1] - maxLogit).toDouble())
        val neuralProb = (expWake / (expBg + expWake)).toFloat().coerceIn(0f, 1f)

        // Temporal MFCC alignment against bundled canonical 'يا شيتا' contour
        val liveMfccOnly = tensor.map { row -> FloatArray(numMfcc) { c -> row[c] } }
        val dtwScore = AudioFeatureExtractor.dtwConfidence(liveMfccOnly, refMfccSequence)

        val combined = (neuralProb * 0.68f + dtwScore * 0.32f).coerceIn(0f, 1f)
        val elapsedUs = (System.nanoTime() - startNs) / 1000L

        return BundledModelInferenceResult(
            detected = combined >= threshold && neuralProb >= 0.45f,
            confidence = combined,
            neuralProbability = neuralProb,
            templateDtwScore = dtwScore,
            thresholdUsed = threshold,
            inferenceTimeUs = elapsedUs,
            modelId = modelId
        )
    }

    override fun close() = Unit

    companion object {
        const val ASSET_MODEL_PATH = "models/cheetah_ya_sheeta_kws.bin"
        private const val MAGIC_BYTES = "CHKW"

        fun loadFromAssets(
            context: Context,
            assetPath: String = ASSET_MODEL_PATH
        ): Result<BundledYaSheetaAcousticModel> = runCatching {
            val assets = context.assets
            val buffer = runCatching {
                assets.openFd(assetPath).use { afd ->
                    FileInputStream(afd.fileDescriptor).use { fis ->
                        fis.channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            afd.startOffset,
                            afd.declaredLength
                        )
                    }
                }
            }.getOrElse {
                assets.open(assetPath).use { stream ->
                    val bytes = stream.readBytes()
                    ByteBuffer.wrap(bytes)
                }
            }
            parseBuffer(buffer)
        }

        fun loadFromStream(stream: InputStream): Result<BundledYaSheetaAcousticModel> = runCatching {
            val bytes = stream.readBytes()
            parseBuffer(ByteBuffer.wrap(bytes))
        }

        fun parseBuffer(rawBuffer: ByteBuffer): BundledYaSheetaAcousticModel {
            val buf = rawBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            buf.rewind()
            val totalSize = buf.remaining()
            require(totalSize >= 44) { "CHKW model buffer too small: $totalSize bytes" }

            // Verify CRC32 footer first
            val allBytes = ByteArray(totalSize)
            buf.get(allBytes)
            val crc = CRC32().apply { update(allBytes, 0, totalSize - 4) }.value.toInt()
            buf.rewind()
            val expectedCrc = buf.getInt(totalSize - 4)
            require(crc == expectedCrc) { "CHKW model CRC32 mismatch" }

            val magic = ByteArray(4)
            buf.get(magic)
            require(String(magic, Charsets.US_ASCII) == MAGIC_BYTES) {
                "Invalid CHKW magic header"
            }

            val version = buf.short.toInt() and 0xFFFF
            require(version == 1) { "Unsupported CHKW version: $version" }

            val sampleRate = buf.short.toInt() and 0xFFFF
            val timeSteps = buf.get().toInt() and 0xFF
            val numMfcc = buf.get().toInt() and 0xFF
            val inputDim = buf.short.toInt() and 0xFFFF
            val hiddenDim = buf.short.toInt() and 0xFFFF
            val numClasses = buf.short.toInt() and 0xFFFF

            val recThreshold = buf.float
            val w1Scale = buf.float
            val w2Scale = buf.float
            val refScale = buf.float

            val w1 = FloatArray(inputDim * hiddenDim) { buf.get().toFloat() * w1Scale }
            val b1 = FloatArray(hiddenDim) { buf.float }
            val w2 = FloatArray(hiddenDim * numClasses) { buf.get().toFloat() * w2Scale }
            val b2 = FloatArray(numClasses) { buf.float }
            val refMfcc = List(timeSteps) {
                FloatArray(numMfcc) { buf.get().toFloat() * refScale }
            }

            return BundledYaSheetaAcousticModel(
                sampleRateHz = sampleRate,
                timeSteps = timeSteps,
                numMfcc = numMfcc,
                inputDim = inputDim,
                hiddenDim = hiddenDim,
                numClasses = numClasses,
                recommendedThreshold = recThreshold,
                w1 = w1,
                b1 = b1,
                w2 = w2,
                b2 = b2,
                refMfccSequence = refMfcc,
                modelSizeBytes = totalSize,
                checksumVerified = true
            )
        }
    }
}
