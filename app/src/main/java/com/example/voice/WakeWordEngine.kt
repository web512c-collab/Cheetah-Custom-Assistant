package com.example.voice

import com.example.core.model.AssistantSettings
import com.example.core.model.VoiceTemplateSample
import com.example.data.VoiceTemplateStore

data class WakeEvaluationResult(
    val detected: Boolean,
    val wakeConfidence: Float,
    val thresholdUsed: Float,
    val voiceMatchScore: Float?,
    val voiceMatchPassed: Boolean,
    val usedLowConfidenceFallback: Boolean,
    val detectionSource: String,
    val inferenceTimeUs: Long,
    val diagnosticSummary: String
)

/**
 * Local acoustic wake-word inference engine:
 * 1) Primary: User-enrolled local MFCC-DTW templates with dynamic intra-speaker calibration
 *    (recommended >= 5 samples in VoiceTemplateStore).
 * 2) Weak auxiliary prior only: Bundled CHKW v1 ('يا شيتا') binary acoustic model contributes
 *    at most <= 0.10f tie-breaker prior and NEVER triggers wake activation on its own.
 * 3) Voice Match speaker verification is optional (OFF by default) and NEVER blocks ordinary
 *    wake detection or standard commands.
 */
class WakeWordEngine(
    private val templateStore: VoiceTemplateStore,
    private val bundledModel: BundledYaSheetaAcousticModel? = null
) {

    fun currentCalibratedThreshold(sensitivity: Float): Float {
        return templateStore.getCalibratedThreshold(sensitivity).threshold
    }

    fun sensitivityToDtwThreshold(sensitivity: Float): Float {
        return currentCalibratedThreshold(sensitivity)
    }

    fun hasAcousticModel(): Boolean {
        return templateStore.samples.value.any { it.mfccSequence.isNotEmpty() }
    }

    fun isUsingDefaultYaSheetaPhrase(settings: AssistantSettings): Boolean {
        val norm = EgyptianPhoneticNormalizer.normalizeForNlu(settings.wakePhrase)
        return norm.isEmpty() ||
            norm.contains("شيتا") ||
            norm.contains("شيطا") ||
            norm.contains("cheetah")
    }

    fun evaluate(
        pcmUtterance: ShortArray,
        noiseFloorRms: Float,
        settings: AssistantSettings
    ): WakeEvaluationResult {
        val samples: List<VoiceTemplateSample> = templateStore.samples.value
        val mfccTemplates = samples.filter { it.mfccSequence.isNotEmpty() }
        val calibration = WakeThresholdCalibrator.calibrate(mfccTemplates, settings.wakeSensitivity)
        val activeThreshold = calibration.threshold

        // Weak optional CHKW-v1 tie-breaker prior (capped <= 0.10f, cannot detect standalone)
        val bundledResult = if (bundledModel != null && isUsingDefaultYaSheetaPhrase(settings)) {
            bundledModel.evaluate(
                pcmUtterance = pcmUtterance,
                noiseFloorRms = noiseFloorRms,
                sensitivityOverride = settings.wakeSensitivity
            )
        } else {
            null
        }
        val chkwPriorBonus = if (bundledResult?.detected == true) {
            (bundledResult.confidence * MAX_CHKW_TIE_BREAKER_PRIOR).coerceIn(0f, MAX_CHKW_TIE_BREAKER_PRIOR)
        } else {
            0f
        }
        val inferenceTimeUs = bundledResult?.inferenceTimeUs ?: 0L

        if (mfccTemplates.isEmpty()) {
            return WakeEvaluationResult(
                detected = false,
                wakeConfidence = chkwPriorBonus,
                thresholdUsed = activeThreshold,
                voiceMatchScore = null,
                voiceMatchPassed = true,
                usedLowConfidenceFallback = false,
                detectionSource = "NEEDS_ENROLLMENT",
                inferenceTimeUs = inferenceTimeUs,
                diagnosticSummary = "مطلوب تسجيل ${VoiceTemplateStore.MIN_REQUIRED_SAMPLES} عينات لكلمة التنبيه بصوتك لتفعيل الكشف التلقائي"
            )
        }

        val liveMfcc = AudioFeatureExtractor.extractMfccSequence(pcmUtterance, noiseFloorRms)
        if (liveMfcc == null) {
            return WakeEvaluationResult(
                detected = false,
                wakeConfidence = 0f,
                thresholdUsed = activeThreshold,
                voiceMatchScore = null,
                voiceMatchPassed = true,
                usedLowConfidenceFallback = false,
                detectionSource = "NO_SPEECH_FEATURES",
                inferenceTimeUs = inferenceTimeUs,
                diagnosticSummary = "المقطع قصير أو غير واضح لاستخراج خصائص MFCC"
            )
        }

        val dtwScores = mfccTemplates.map { tpl ->
            AudioFeatureExtractor.dtwConfidence(liveMfcc, tpl.mfccSequence)
        }.sortedDescending()

        val bestDtw = dtwScores.firstOrNull() ?: 0f
        val topAvgDtw = when {
            dtwScores.size >= 3 -> (dtwScores[0] * 0.50f) + (dtwScores[1] * 0.30f) + (dtwScores[2] * 0.20f)
            dtwScores.size == 2 -> (dtwScores[0] * 0.65f) + (dtwScores[1] * 0.35f)
            else -> bestDtw
        }

        // Apply <= 0.10f CHKW-v1 tie-breaker ONLY when MFCC-DTW is already near threshold
        val nearThreshold = topAvgDtw >= (activeThreshold - MAX_CHKW_TIE_BREAKER_PRIOR) ||
            bestDtw >= activeThreshold
        val combinedConfidence = if (nearThreshold) {
            (topAvgDtw + chkwPriorBonus).coerceIn(0f, 1f)
        } else {
            topAvgDtw
        }

        val matched = (combinedConfidence >= activeThreshold) || (bestDtw >= (activeThreshold + 0.04f))
        val sourceTag = when {
            matched && chkwPriorBonus > 0f -> "USER_MFCC_DTW+CHKW_PRIOR"
            matched -> "USER_MFCC_DTW"
            else -> "REJECTED"
        }

        if (!matched) {
            return WakeEvaluationResult(
                detected = false,
                wakeConfidence = combinedConfidence,
                thresholdUsed = activeThreshold,
                voiceMatchScore = null,
                voiceMatchPassed = true,
                usedLowConfidenceFallback = false,
                detectionSource = sourceTag,
                inferenceTimeUs = inferenceTimeUs,
                diagnosticSummary = "مرفوض: ثقة DTW ${(combinedConfidence * 100).toInt()}% < العتبة المعايرة ${(activeThreshold * 100).toInt()}%"
            )
        }

        // Compute speaker cosine similarity for diagnostics / optional sensitive-command guard;
        // NEVER block wake trigger or ordinary voice commands based on speaker similarity.
        val speakerCentroid = templateStore.getAveragedSpeakerCentroid()
        val liveSpeakerVec = AudioFeatureExtractor.extractSpeakerVector(pcmUtterance)
        val speakerSim = if (speakerCentroid != null && liveSpeakerVec != null) {
            AudioFeatureExtractor.cosineSimilarity(liveSpeakerVec, speakerCentroid)
        } else {
            null
        }
        val voiceMatchPassed = if (!settings.voiceMatchEnabled || speakerSim == null) {
            true
        } else {
            speakerSim >= settings.voiceMatchThreshold || settings.allowOwnerFallbackOnLowVoiceMatch
        }

        return WakeEvaluationResult(
            detected = true,
            wakeConfidence = combinedConfidence,
            thresholdUsed = activeThreshold,
            voiceMatchScore = speakerSim,
            voiceMatchPassed = voiceMatchPassed,
            usedLowConfidenceFallback = settings.voiceMatchEnabled && speakerSim != null && speakerSim < settings.voiceMatchThreshold,
            detectionSource = sourceTag,
            inferenceTimeUs = inferenceTimeUs,
            diagnosticSummary = "تم الكشف [$sourceTag]: ${(combinedConfidence * 100).toInt()}% ≥ عتبة ${(activeThreshold * 100).toInt()}% (${mfccTemplates.size} عينات)"
        )
    }

    companion object {
        const val MAX_CHKW_TIE_BREAKER_PRIOR = 0.10f
    }
}
