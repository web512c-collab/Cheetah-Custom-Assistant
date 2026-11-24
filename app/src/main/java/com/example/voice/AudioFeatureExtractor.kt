package com.example.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Pure-Kotlin 16kHz PCM DSP engine for Voice Activity Detection (VAD),
 * 12-band Goertzel spectral energy extraction, and cosine-similarity Voice Match.
 * Runs 100% on-device with zero native cloud dependencies.
 */
object AudioFeatureExtractor {

    const val SAMPLE_RATE = 16000
    private val BAND_FREQS_HZ = floatArrayOf(
        140f, 230f, 340f, 480f, 660f, 900f,
        1250f, 1700f, 2300f, 3100f, 4200f, 5600f
    )

    fun computeRms(samples: ShortArray, length: Int = samples.size): Float {
        if (length <= 0) return 0f
        var sumSq = 0.0
        for (i in 0 until length) {
            val norm = samples[i] / 32768.0
            sumSq += norm * norm
        }
        return sqrt(sumSq / length).toFloat()
    }

    /**
     * Trims leading/trailing silence using adaptive energy gating and returns
     * a normalized 14-dimensional feature vector:
     * [0..11] = L2-normalized log Goertzel spectral bands
     * [12]    = Zero-Crossing Rate (ZCR)
     * [13]    = Spectral Centroid proxy
     */
    fun extractSpeakerVector(pcm: ShortArray): FloatArray? {
        if (pcm.size < SAMPLE_RATE / 4) return null

        val frameSize = 400 // 25ms at 16kHz
        val hop = 160       // 10ms hop
        val activeFrames = ArrayList<FloatArray>()
        var zcrSum = 0f
        var centroidSum = 0f

        var offset = 0
        while (offset + frameSize <= pcm.size) {
            val frame = FloatArray(frameSize)
            var energy = 0.0
            var zeroCrossings = 0
            for (i in 0 until frameSize) {
                val s = pcm[offset + i] / 32768.0f
                // Hamming window
                val w = 0.54f - 0.46f * cos((2.0 * PI * i) / (frameSize - 1)).toFloat()
                frame[i] = s * w
                energy += s * s
                if (i > 0) {
                    val prev = pcm[offset + i - 1]
                    val curr = pcm[offset + i]
                    if ((prev >= 0 && curr < 0) || (prev < 0 && curr >= 0)) {
                        zeroCrossings++
                    }
                }
            }
            val rms = sqrt(energy / frameSize).toFloat()
            if (rms > 0.015f) {
                val bandEnergies = FloatArray(BAND_FREQS_HZ.size)
                var weightedSum = 0f
                var totalMag = 1e-6f
                for (b in BAND_FREQS_HZ.indices) {
                    val power = goertzelPower(frame, BAND_FREQS_HZ[b], SAMPLE_RATE)
                    val logPower = ln(1f + power * 100f)
                    bandEnergies[b] = logPower
                    weightedSum += logPower * (b + 1)
                    totalMag += logPower
                }
                activeFrames.add(bandEnergies)
                zcrSum += zeroCrossings.toFloat() / frameSize
                centroidSum += (weightedSum / totalMag) / BAND_FREQS_HZ.size
            }
            offset += hop
        }

        if (activeFrames.size < 6) return null

        val vector = FloatArray(BAND_FREQS_HZ.size + 2)
        for (f in activeFrames) {
            for (b in BAND_FREQS_HZ.indices) {
                vector[b] += f[b]
            }
        }
        val count = activeFrames.size.toFloat()
        for (b in BAND_FREQS_HZ.indices) {
            vector[b] /= count
        }
        vector[BAND_FREQS_HZ.size] = zcrSum / count
        vector[BAND_FREQS_HZ.size + 1] = centroidSum / count

        // L2 normalize
        var norm = 0f
        for (v in vector) norm += v * v
        val denom = sqrt(norm).coerceAtLeast(1e-6f)
        for (i in vector.indices) {
            vector[i] /= denom
        }
        return vector
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom < 1e-6f) return 0f
        return (dot / denom).coerceIn(-1f, 1f)
    }

    private fun goertzelPower(samples: FloatArray, targetHz: Float, sampleRate: Int): Float {
        val k = (0.5f + (samples.size * targetHz) / sampleRate).toInt()
        val omega = (2.0 * PI * k) / samples.size
        val coeff = 2.0 * cos(omega)
        var q0 = 0.0
        var q1 = 0.0
        var q2 = 0.0
        for (s in samples) {
            q0 = coeff * q1 - q2 + s
            q2 = q1
            q1 = q0
        }
        return ((q1 * q1 + q2 * q2 - q1 * q2 * coeff) / samples.size).toFloat().coerceAtLeast(0f)
    }
}
