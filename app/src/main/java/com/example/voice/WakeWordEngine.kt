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
 * Local acoustic wake-word inference engine combining:
 * 1) The bundled CHKW v1 binary model for Egyptian Arabic 'يا شيتا' (works out-of-the-box offline)
 * 2) User-enrolled local MFCC-DTW templates & Voice Match speaker verification (VoiceTemplateStore)
 *
 * Separates wake-phrase acoustic recognition from speaker verification so low-confidence
 * Voice Match never silently locks the owner out when fallback is enabled.
 */
class WakeWordEngine(
    private val templateStore: VoiceTemplateStore,
    private val bundledModel: BundledYaSheetaAcousticModel? = null
) {

    fun sensitivityToDtwThreshold(sensitivity: Float): Float {
        return (0.82f - (sensitivity.coerceIn(0.2f, 0.95f) * 0.40f)).coerceIn(0.42f, 0.74f)
    }

    fun hasAcousticModel(): Boolean {
        return bundledModel != null || templateStore.samples.value.any { it.mfccSequence.isNotEmpty() }
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
        val threshold = sensitivityToDtwThreshold(settings.wakeSensitivity)
        val mfccTemplates = samples.filter { it.mfccSequence.isNotEmpty() }

        // 1. Evaluate bundled CHKW v1 binary acoustic model when listening for 'يا شيتا'
        val bundledResult = if (bundledModel != null && isUsingDefaultYaSheetaPhrase(settings)) {
            bundledModel.evaluate(
                pcmUtterance = pcmUtterance,
                noiseFloorRms = noiseFloorRms,
                sensitivityOverride = settings.wakeSensitivity
            )
        } else {
            null
        }

        // 2. Evaluate user-enrolled MFCC-DTW templates if present
        var userBestDtw = 0f
        var userTopAvgDtw = 0f
        var userEvaluated = false
        if (mfccTemplates.isNotEmpty()) {
            val liveMfcc = AudioFeatureExtractor.extractMfccSequence(pcmUtterance, noiseFloorRms)
            if (liveMfcc != null) {
                userEvaluated = true
                val dtwScores = mfccTemplates.map { tpl ->
                    AudioFeatureExtractor.dtwConfidence(liveMfcc, tpl.mfccSequence)
                }.sortedDescending()
                userBestDtw = dtwScores.firstOrNull() ?: 0f
                userTopAvgDtw = if (dtwScores.size >= 2) {
                    (dtwScores[0] * 0.65f) + (dtwScores[1] * 0.35f)
                } else {
                    userBestDtw
                }
            }
        }

        if (bundledResult == null && !userEvaluated) {
            return WakeEvaluationResult(
                detected = false,
                wakeConfidence = 0f,
                thresholdUsed = threshold,
                voiceMatchScore = null,
                voiceMatchPassed = true,
                usedLowConfidenceFallback = false,
                detectionSource = "NONE",
                inferenceTimeUs = 0L,
                diagnosticSummary = "لا يوجد نموذج صوتي نشط — سجل اسم التنبيه بالصوت أو استخدم 'يا شيتا'"
            )
        }

        val userMatched = userEvaluated && (userTopAvgDtw >= threshold || userBestDtw >= (threshold + 0.05f))
        val bundledMatched = bundledResult?.detected == true

        val combinedConfidence = when {
            userEvaluated && bundledResult != null ->
                (userTopAvgDtw * 0.55f + bundledResult.confidence * 0.45f).coerceIn(0f, 1f)
            userEvaluated -> userTopAvgDtw
            else -> bundledResult?.confidence ?: 0f
        }
        val activeThreshold = bundledResult?.thresholdUsed ?: threshold
        val inferenceTimeUs = bundledResult?.inferenceTimeUs ?: 0L

        val phraseMatched = when {
            userEvaluated && bundledResult != null ->
                userMatched || (bundledMatched && combinedConfidence >= (activeThreshold - 0.04f))
            userEvaluated -> userMatched
            else -> bundledMatched
        }

        val sourceTag = when {
            userMatched && bundledMatched -> "BUNDLED_BIN+USER_DTW"
            userMatched -> "USER_MFCC_DTW"
            bundledMatched -> "BUNDLED_CHKW_BIN"
            else -> "REJECTED"
        }

        if (!phraseMatched) {
            return WakeEvaluationResult(
                detected = false,
                wakeConfidence = combinedConfidence,
                thresholdUsed = activeThreshold,
                voiceMatchScore = null,
                voiceMatchPassed = true,
                usedLowConfidenceFallback = false,
                detectionSource = sourceTag,
                inferenceTimeUs = inferenceTimeUs,
                diagnosticSummary = "مرفوض: ثقة ${(combinedConfidence * 100).toInt()}% < ${(activeThreshold * 100).toInt()}%" +
                    (bundledResult?.let { " (NN=${(it.neuralProbability * 100).toInt()}%)" } ?: "")
            )
        }

        // 3. Optional independent Voice Match speaker verification (when owner enrolled samples)
        val speakerCentroid = templateStore.getAveragedSpeakerCentroid()
        val liveSpeakerVec = AudioFeatureExtractor.extractSpeakerVector(pcmUtterance)
        val speakerSim = if (speakerCentroid != null && liveSpeakerVec != null) {
            AudioFeatureExtractor.cosineSimilarity(liveSpeakerVec, speakerCentroid)
        } else {
            null
        }

        if (!settings.voiceMatchEnabled || speakerSim == null) {
            return WakeEvaluationResult(
                detected = true,
                wakeConfidence = combinedConfidence,
                thresholdUsed = activeThreshold,
                voiceMatchScore = speakerSim,
                voiceMatchPassed = true,
                usedLowConfidenceFallback = false,
                detectionSource = sourceTag,
                inferenceTimeUs = inferenceTimeUs,
                diagnosticSummary = "تم كشف 'يا شيتا' [$sourceTag] ${(combinedConfidence * 100).toInt()}% (${inferenceTimeUs}µs)"
            )
        }

        val directPass = speakerSim >= settings.voiceMatchThreshold
        if (directPass) {
            return WakeEvaluationResult(
                detected = true,
                wakeConfidence = combinedConfidence,
                thresholdUsed = activeThreshold,
                voiceMatchScore = speakerSim,
                voiceMatchPassed = true,
                usedLowConfidenceFallback = false,
                detectionSource = sourceTag,
                inferenceTimeUs = inferenceTimeUs,
                diagnosticSummary = "تطابق كامل [$sourceTag]: ${(combinedConfidence * 100).toInt()}% • VoiceMatch=${(speakerSim * 100).toInt()}%"
            )
        }

        val strongWakeOverride = settings.allowOwnerFallbackOnLowVoiceMatch &&
            speakerSim >= (settings.voiceMatchThreshold - 0.16f) &&
            combinedConfidence >= (activeThreshold + 0.04f)

        return if (strongWakeOverride) {
            WakeEvaluationResult(
                detected = true,
                wakeConfidence = combinedConfidence,
                thresholdUsed = activeThreshold,
                voiceMatchScore = speakerSim,
                voiceMatchPassed = false,
                usedLowConfidenceFallback = true,
                detectionSource = sourceTag,
                inferenceTimeUs = inferenceTimeUs,
                diagnosticSummary = "بديل المالك الآمن [$sourceTag]: ${(combinedConfidence * 100).toInt()}% • VoiceMatch منخفض (${(speakerSim * 100).toInt()}%)"
            )
        } else {
            WakeEvaluationResult(
                detected = false,
                wakeConfidence = combinedConfidence,
                thresholdUsed = activeThreshold,
                voiceMatchScore = speakerSim,
                voiceMatchPassed = false,
                usedLowConfidenceFallback = false,
                detectionSource = "VOICE_MATCH_DENIED",
                inferenceTimeUs = inferenceTimeUs,
                diagnosticSummary = "مرفوض بـ Voice Match: ${(speakerSim * 100).toInt()}% < ${(settings.voiceMatchThreshold * 100).toInt()}%"
            )
        }
    }
}
