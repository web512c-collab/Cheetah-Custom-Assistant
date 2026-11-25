package com.example.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin 16kHz PCM DSP engine providing:
 * 1) RMS / VAD energy estimation
 * 2) 13-coefficient Mel-Frequency Cepstral Coefficients (MFCC) per 25ms frame with CMVN
 * 3) Sakoe-Chiba banded Dynamic Time Warping (DTW) for temporal wake-word sequence matching
 * 4) 14-D spectral + ZCR speaker vector & cosine similarity for local Voice Match
 */
object AudioFeatureExtractor {

    const val SAMPLE_RATE = 16000
    private const val FRAME_SIZE = 400 // 25ms at 16kHz
    private const val FRAME_HOP = 160  // 10ms hop at 16kHz
    private const val FFT_SIZE = 512
    private const val NUM_MEL_FILTERS = 24
    const val NUM_MFCC = 13

    private val BAND_FREQS_HZ = floatArrayOf(
        140f, 230f, 340f, 480f, 660f, 900f,
        1250f, 1700f, 2300f, 3100f, 4200f, 5600f
    )

    private val HAMMING_WINDOW = FloatArray(FRAME_SIZE) { i ->
        (0.54 - 0.46 * cos((2.0 * PI * i) / (FRAME_SIZE - 1))).toFloat()
    }

    private val MEL_FILTERBANK: Array<FloatArray> by lazy { buildMelFilterbank() }
    private val DCT_MATRIX: Array<FloatArray> by lazy { buildDctMatrix() }

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
     * Extracts a sequence of CMVN-normalized 13-D MFCC frames from active speech segments.
     * Returns null if speech contains fewer than 10 active frames (~100ms).
     */
    fun extractMfccSequence(pcm: ShortArray, noiseFloorRms: Float = 0.010f): List<FloatArray>? {
        if (pcm.size < FRAME_SIZE * 2) return null

        // Pre-emphasis: y[n] = x[n] - 0.97 * x[n-1]
        val preEmph = FloatArray(pcm.size)
        preEmph[0] = pcm[0] / 32768.0f
        for (i in 1 until pcm.size) {
            preEmph[i] = (pcm[i] - 0.97f * pcm[i - 1]) / 32768.0f
        }

        val gateThreshold = max(0.011f, noiseFloorRms * 1.6f)
        val rawFrames = ArrayList<FloatArray>()
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)
        val powerSpec = FloatArray(FFT_SIZE / 2 + 1)
        val melLog = FloatArray(NUM_MEL_FILTERS)

        var offset = 0
        while (offset + FRAME_SIZE <= pcm.size) {
            var rawEnergy = 0.0
            for (i in 0 until FRAME_SIZE) {
                val orig = pcm[offset + i] / 32768.0f
                rawEnergy += orig * orig
            }
            val frameRms = sqrt(rawEnergy / FRAME_SIZE).toFloat()

            if (frameRms >= gateThreshold) {
                real.fill(0f)
                imag.fill(0f)
                for (i in 0 until FRAME_SIZE) {
                    real[i] = preEmph[offset + i] * HAMMING_WINDOW[i]
                }
                fftInPlace(real, imag)

                for (k in 0..FFT_SIZE / 2) {
                    powerSpec[k] = (real[k] * real[k] + imag[k] * imag[k]) / FFT_SIZE
                }

                for (m in 0 until NUM_MEL_FILTERS) {
                    var sum = 1e-7f
                    val filter = MEL_FILTERBANK[m]
                    for (k in 0..FFT_SIZE / 2) {
                        sum += powerSpec[k] * filter[k]
                    }
                    melLog[m] = ln(sum)
                }

                val mfcc = FloatArray(NUM_MFCC)
                for (c in 0 until NUM_MFCC) {
                    var sum = 0f
                    val dctRow = DCT_MATRIX[c]
                    for (m in 0 until NUM_MEL_FILTERS) {
                        sum += melLog[m] * dctRow[m]
                    }
                    mfcc[c] = sum
                }
                rawFrames.add(mfcc)
            }
            offset += FRAME_HOP
        }

        if (rawFrames.size < 10) return null

        // Cepstral Mean & Variance Normalization (CMVN) across utterance
        // Makes matching resilient to microphone distance and quiet vs. loud voice.
        val mean = FloatArray(NUM_MFCC)
        for (f in rawFrames) {
            for (c in 0 until NUM_MFCC) mean[c] += f[c]
        }
        val n = rawFrames.size.toFloat()
        for (c in 0 until NUM_MFCC) mean[c] /= n

        val std = FloatArray(NUM_MFCC)
        for (f in rawFrames) {
            for (c in 0 until NUM_MFCC) {
                val d = f[c] - mean[c]
                std[c] += d * d
            }
        }
        for (c in 0 until NUM_MFCC) {
            std[c] = sqrt(std[c] / n).coerceAtLeast(1e-4f)
        }

        return rawFrames.map { f ->
            FloatArray(NUM_MFCC) { c -> (f[c] - mean[c]) / std[c] }
        }
    }

    /**
     * Extracts a fixed 24x16 temporal acoustic tensor (13 CMVN MFCCs + RMS + ZCR + High/Low spectral tilt)
     * matching the bundled CHKW v1 binary model input contract for 'يا شيتا'.
     */
    fun extractKwsFeatureTensor(
        pcm: ShortArray,
        noiseFloorRms: Float = 0.010f,
        timeSteps: Int = 24
    ): Array<FloatArray>? {
        if (pcm.size < FRAME_SIZE * 2) return null

        val preEmph = FloatArray(pcm.size)
        preEmph[0] = pcm[0] / 32768.0f
        for (i in 1 until pcm.size) {
            preEmph[i] = (pcm[i] - 0.97f * pcm[i - 1]) / 32768.0f
        }

        val gateThreshold = max(0.011f, noiseFloorRms * 1.6f)
        val rawMfcc = ArrayList<FloatArray>()
        val rawAux = ArrayList<FloatArray>() // [rms, zcr, tilt]
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)
        val powerSpec = FloatArray(FFT_SIZE / 2 + 1)
        val melLog = FloatArray(NUM_MEL_FILTERS)

        val lowBinEnd = (1200f * FFT_SIZE / SAMPLE_RATE).toInt()
        val highBinStart = (2200f * FFT_SIZE / SAMPLE_RATE).toInt()
        val highBinEnd = (6800f * FFT_SIZE / SAMPLE_RATE).toInt()

        var offset = 0
        while (offset + FRAME_SIZE <= pcm.size) {
            var rawEnergy = 0.0
            var zeroCrossings = 0
            for (i in 0 until FRAME_SIZE) {
                val orig = pcm[offset + i] / 32768.0f
                rawEnergy += orig * orig
                if (i > 0) {
                    val prevS = pcm[offset + i - 1]
                    val currS = pcm[offset + i]
                    if ((prevS >= 0 && currS < 0) || (prevS < 0 && currS >= 0)) {
                        zeroCrossings++
                    }
                }
            }
            val frameRms = sqrt(rawEnergy / FRAME_SIZE).toFloat()

            if (frameRms >= gateThreshold) {
                real.fill(0f)
                imag.fill(0f)
                for (i in 0 until FRAME_SIZE) {
                    real[i] = preEmph[offset + i] * HAMMING_WINDOW[i]
                }
                fftInPlace(real, imag)

                for (k in 0..FFT_SIZE / 2) {
                    powerSpec[k] = (real[k] * real[k] + imag[k] * imag[k]) / FFT_SIZE
                }

                for (m in 0 until NUM_MEL_FILTERS) {
                    var sum = 1e-7f
                    val filter = MEL_FILTERBANK[m]
                    for (k in 0..FFT_SIZE / 2) {
                        sum += powerSpec[k] * filter[k]
                    }
                    melLog[m] = ln(sum)
                }

                val mfcc = FloatArray(NUM_MFCC)
                for (c in 0 until NUM_MFCC) {
                    var sum = 0f
                    val dctRow = DCT_MATRIX[c]
                    for (m in 0 until NUM_MEL_FILTERS) {
                        sum += melLog[m] * dctRow[m]
                    }
                    mfcc[c] = sum
                }
                var lowEnergy = 1e-7f
                for (k in 2 until lowBinEnd) lowEnergy += powerSpec[k]
                var highEnergy = 1e-7f
                for (k in highBinStart until highBinEnd) highEnergy += powerSpec[k]
                val tilt = ln(highEnergy / lowEnergy)

                rawMfcc.add(mfcc)
                rawAux.add(
                    floatArrayOf(
                        min(3.0f, frameRms * 8.0f),
                        min(3.0f, (zeroCrossings.toFloat() / FRAME_SIZE) * 6.0f),
                        (tilt * 0.45f).coerceIn(-3.0f, 3.0f)
                    )
                )
            }
            offset += FRAME_HOP
        }

        if (rawMfcc.size < 10) return null

        val mean = FloatArray(NUM_MFCC)
        for (f in rawMfcc) {
            for (c in 0 until NUM_MFCC) mean[c] += f[c]
        }
        val n = rawMfcc.size.toFloat()
        for (c in 0 until NUM_MFCC) mean[c] /= n

        val std = FloatArray(NUM_MFCC)
        for (f in rawMfcc) {
            for (c in 0 until NUM_MFCC) {
                val d = f[c] - mean[c]
                std[c] += d * d
            }
        }
        for (c in 0 until NUM_MFCC) {
            std[c] = sqrt(std[c] / n).coerceAtLeast(1e-4f)
        }

        val normFrames = ArrayList<FloatArray>(rawMfcc.size)
        for (idx in rawMfcc.indices) {
            val row = FloatArray(NUM_MFCC + 3)
            for (c in 0 until NUM_MFCC) {
                row[c] = (rawMfcc[idx][c] - mean[c]) / std[c]
            }
            row[NUM_MFCC] = rawAux[idx][0]
            row[NUM_MFCC + 1] = rawAux[idx][1]
            row[NUM_MFCC + 2] = rawAux[idx][2]
            normFrames.add(row)
        }

        return Array(timeSteps) { t ->
            val srcIdx = (((t + 0.5f) * normFrames.size) / timeSteps).toInt()
                .coerceIn(0, normFrames.size - 1)
            normFrames[srcIdx]
        }
    }

    fun poolSegments64(tensor: Array<FloatArray>, segments: Int = 4): FloatArray {
        val timeSteps = tensor.size
        val featDim = tensor.firstOrNull()?.size ?: 16
        val segLen = (timeSteps / segments).coerceAtLeast(1)
        val out = FloatArray(segments * featDim)
        for (s in 0 until segments) {
            val start = s * segLen
            val end = ((s + 1) * segLen).coerceAtMost(timeSteps)
            val count = (end - start).coerceAtLeast(1).toFloat()
            for (t in start until end) {
                for (c in 0 until featDim) {
                    out[s * featDim + c] += tensor[t][c]
                }
            }
            for (c in 0 until featDim) {
                out[s * featDim + c] /= count
            }
        }
        return out
    }

    /**
     * Computes similarity confidence in [0f..1f] between two MFCC frame sequences
     * using Sakoe-Chiba banded Dynamic Time Warping (DTW).
     */
    fun dtwConfidence(live: List<FloatArray>, template: List<FloatArray>): Float {
        if (live.isEmpty() || template.isEmpty()) return 0f
        val n = live.size
        val m = template.size
        val ratio = n.toFloat() / m.toFloat()
        if (ratio < 0.42f || ratio > 2.35f) return 0f

        val band = max(abs(n - m) + 4, (max(n, m) * 0.28f).toInt())
        val inf = 1e9f
        var prev = FloatArray(m + 1) { inf }
        var curr = FloatArray(m + 1) { inf }
        prev[0] = 0f

        for (i in 1..n) {
            curr.fill(inf)
            val jCenter = (i * m) / n
            val jStart = max(1, jCenter - band)
            val jEnd = min(m, jCenter + band)
            val liveFrame = live[i - 1]

            for (j in jStart..jEnd) {
                val dist = euclideanDistance(liveFrame, template[j - 1])
                val bestPrev = minOf(prev[j], curr[j - 1], prev[j - 1])
                curr[j] = dist + bestPrev
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }

        val totalCost = prev[m]
        if (totalCost >= inf * 0.5f) return 0f

        val avgStepDist = totalCost / (n + m).toFloat()
        // Calibrated for CMVN 13-MFCC vectors:
        // Same phrase by same/similar speaker: ~1.1 - 1.8 -> confidence 0.65 - 0.85
        // Unrelated speech / noise: ~2.6 - 4.2 -> confidence 0.15 - 0.42
        return exp(-max(0f, avgStepDist - 0.7f) / 1.9f).coerceIn(0f, 1f)
    }

    /**
     * Extracts 14-D spectral timbre + ZCR speaker vector for local Voice Match verification.
     */
    fun extractSpeakerVector(pcm: ShortArray): FloatArray? {
        if (pcm.size < SAMPLE_RATE / 4) return null

        val activeFrames = ArrayList<FloatArray>()
        var zcrSum = 0f
        var centroidSum = 0f

        var offset = 0
        while (offset + FRAME_SIZE <= pcm.size) {
            val frame = FloatArray(FRAME_SIZE)
            var energy = 0.0
            var zeroCrossings = 0
            for (i in 0 until FRAME_SIZE) {
                val s = pcm[offset + i] / 32768.0f
                frame[i] = s * HAMMING_WINDOW[i]
                energy += s * s
                if (i > 0) {
                    val prevSample = pcm[offset + i - 1]
                    val currSample = pcm[offset + i]
                    if ((prevSample >= 0 && currSample < 0) || (prevSample < 0 && currSample >= 0)) {
                        zeroCrossings++
                    }
                }
            }
            val rms = sqrt(energy / FRAME_SIZE).toFloat()
            if (rms > 0.012f) {
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
                zcrSum += zeroCrossings.toFloat() / FRAME_SIZE
                centroidSum += (weightedSum / totalMag) / BAND_FREQS_HZ.size
            }
            offset += FRAME_HOP
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

    private fun euclideanDistance(a: FloatArray, b: FloatArray): Float {
        val len = min(a.size, b.size)
        var sum = 0f
        for (i in 0 until len) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }

    private fun fftInPlace(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wLenReal = cos(ang).toFloat()
            val wLenImag = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wReal = 1f
                var wImag = 0f
                for (k in 0 until len / 2) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + len / 2] * wReal - imag[i + k + len / 2] * wImag
                    val vI = real[i + k + len / 2] * wImag + imag[i + k + len / 2] * wReal
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + len / 2] = uR - vR
                    imag[i + k + len / 2] = uI - vI
                    val nextWReal = wReal * wLenReal - wImag * wLenImag
                    wImag = wReal * wLenImag + wImag * wLenReal
                    wReal = nextWReal
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun hzToMel(hz: Float): Float = (2595.0 * log10(1.0 + hz / 700.0)).toFloat()
    private fun melToHz(mel: Float): Float = (700.0 * (Math.pow(10.0, (mel / 2595.0).toDouble()) - 1.0)).toFloat()

    private fun buildMelFilterbank(): Array<FloatArray> {
        val lowMel = hzToMel(100f)
        val highMel = hzToMel(6800f)
        val melPoints = FloatArray(NUM_MEL_FILTERS + 2) { i ->
            lowMel + i * (highMel - lowMel) / (NUM_MEL_FILTERS + 1)
        }
        val binPoints = IntArray(NUM_MEL_FILTERS + 2) { i ->
            ((FFT_SIZE + 1) * melToHz(melPoints[i]) / SAMPLE_RATE).toInt()
                .coerceIn(0, FFT_SIZE / 2)
        }
        return Array(NUM_MEL_FILTERS) { m ->
            val filter = FloatArray(FFT_SIZE / 2 + 1)
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]
            if (center > left) {
                for (k in left until center) {
                    filter[k] = (k - left).toFloat() / (center - left).toFloat()
                }
            }
            if (right > center) {
                for (k in center..right) {
                    filter[k] = (right - k).toFloat() / (right - center).toFloat()
                }
            }
            filter
        }
    }

    private fun buildDctMatrix(): Array<FloatArray> {
        val scale = sqrt(2.0 / NUM_MEL_FILTERS).toFloat()
        return Array(NUM_MFCC) { c ->
            FloatArray(NUM_MEL_FILTERS) { m ->
                scale * cos(PI * c * (m + 0.5) / NUM_MEL_FILTERS).toFloat()
            }
        }
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
