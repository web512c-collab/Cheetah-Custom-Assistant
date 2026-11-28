package com.example.voice

import com.example.core.model.VoiceTemplateSample
import kotlin.math.abs

data class WakeCalibrationResult(
    val threshold: Float,
    val intraSampleMeanDtw: Float,
    val intraSampleMinDtw: Float,
    val sampleCount: Int,
    val isCalibrated: Boolean
)

/**
 * Computes a dynamic MFCC-DTW wake detection threshold calibrated from the consistency
 * of the user's own enrolled samples, modulated by the user's sensitivity slider.
 */
object WakeThresholdCalibrator {

    const val MIN_CALIBRATED_THRESHOLD = 0.50f
    const val MAX_CALIBRATED_THRESHOLD = 0.84f

    fun calibrate(
        samples: List<VoiceTemplateSample>,
        sensitivity: Float
    ): WakeCalibrationResult {
        val mfccSamples = samples.filter { it.mfccSequence.isNotEmpty() }
        val sens = sensitivity.coerceIn(0.20f, 0.95f)
        // Higher sensitivity lowers threshold; lower sensitivity tightens threshold
        val sensitivityOffset = (0.65f - sens) * 0.16f

        if (mfccSamples.size < 2) {
            val baseThreshold = (0.76f - sens * 0.22f).coerceIn(MIN_CALIBRATED_THRESHOLD, 0.78f)
            return WakeCalibrationResult(
                threshold = baseThreshold,
                intraSampleMeanDtw = 0f,
                intraSampleMinDtw = 0f,
                sampleCount = mfccSamples.size,
                isCalibrated = false
            )
        }

        val pairwiseScores = mutableListOf<Float>()
        for (i in 0 until mfccSamples.size) {
            for (j in i + 1 until mfccSamples.size) {
                val sim = AudioFeatureExtractor.dtwConfidence(
                    mfccSamples[i].mfccSequence,
                    mfccSamples[j].mfccSequence
                )
                pairwiseScores.add(sim)
            }
        }

        val mean = pairwiseScores.average().toFloat().coerceIn(0f, 1f)
        val min = pairwiseScores.minOrNull()?.coerceIn(0f, 1f) ?: mean
        val spread = abs(mean - min)

        // Place calibrated threshold below intra-speaker minimum with margin for natural speech variation
        val anchor = (min * 0.58f + mean * 0.42f) - 0.085f - (spread * 0.12f)
        val calibrated = (anchor + sensitivityOffset).coerceIn(MIN_CALIBRATED_THRESHOLD, MAX_CALIBRATED_THRESHOLD)

        return WakeCalibrationResult(
            threshold = calibrated,
            intraSampleMeanDtw = mean,
            intraSampleMinDtw = min,
            sampleCount = mfccSamples.size,
            isCalibrated = mfccSamples.size >= 5
        )
    }
}
