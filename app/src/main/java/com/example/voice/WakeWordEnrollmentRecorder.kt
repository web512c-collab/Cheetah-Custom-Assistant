package com.example.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.example.core.model.VoiceTemplateSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs

sealed class EnrollmentCaptureResult {
    data class Success(val sample: VoiceTemplateSample) : EnrollmentCaptureResult()
    data class Error(val messageAr: String) : EnrollmentCaptureResult()
}

data class EnrollmentQualityReport(
    val isAcceptable: Boolean,
    val overallRms: Float,
    val peakRms: Float,
    val clippingRatio: Float,
    val activeFrameRatio: Float,
    val speechToNoiseRatio: Float,
    val rejectionReasonAr: String? = null
)

/**
 * Records a short spoken wake-word utterance directly from AudioRecord (16kHz Mono PCM),
 * validates audio quality (clipping, loudness, active speech ratio, background noise),
 * and extracts both the 13-MFCC DTW sequence and the speaker vector.
 */
class WakeWordEnrollmentRecorder(private val context: Context) {

    suspend fun recordSample(
        wakePhrase: String,
        onAmplitude: (Float) -> Unit
    ): EnrollmentCaptureResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext EnrollmentCaptureResult.Error(
                "إذن الميكروفون مش متاح — افتح الإعدادات واسمح بالمايك مرة واحدة."
            )
        }

        val sampleRate = AudioFeatureExtractor.SAMPLE_RATE
        val durationMs = 1900L
        val totalSamples = (sampleRate * durationMs / 1000L).toInt()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val captured = ShortArray(totalSamples)
        var readTotal = 0
        var peakRms = 0f

        val captureOk = MicrophoneArbitrator.withMicAccess(MicOwner.UI_ENROLLMENT) {
            val audioRecord = createBestAudioRecord(sampleRate, minBuf) ?: return@withMicAccess false
            try {
                audioRecord.startRecording()
                if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    return@withMicAccess false
                }
                val chunk = ShortArray(512)
                while (readTotal < totalSamples) {
                    val read = audioRecord.read(
                        chunk,
                        0,
                        minOf(chunk.size, totalSamples - readTotal)
                    )
                    if (read <= 0) break
                    System.arraycopy(chunk, 0, captured, readTotal, read)
                    readTotal += read
                    val rms = AudioFeatureExtractor.computeRms(chunk, read)
                    if (rms > peakRms) peakRms = rms
                    onAmplitude((rms * 4.5f).coerceIn(0f, 1f))
                }
                true
            } finally {
                runCatching { audioRecord.stop() }
                audioRecord.release()
                onAmplitude(0f)
            }
        }
        if (!captureOk) {
            return@withContext EnrollmentCaptureResult.Error(
                "تعذر فتح الميكروفون أو القراءة — تأكد إن مفيش تطبيق تاني حاجز المايك."
            )
        }

        val trimmed = captured.copyOf(readTotal)
        val quality = validateAudioQuality(trimmed, wakePhrase)
        if (!quality.isAcceptable) {
            return@withContext EnrollmentCaptureResult.Error(
                quality.rejectionReasonAr ?: "جودة التسجيل غير كافية — حاول تاني."
            )
        }

        val mfccSequence = AudioFeatureExtractor.extractMfccSequence(trimmed, noiseFloorRms = 0.008f)
        val speakerVector = AudioFeatureExtractor.extractSpeakerVector(trimmed)

        if (mfccSequence == null || mfccSequence.size < MIN_MFCC_FRAMES || speakerVector == null) {
            return@withContext EnrollmentCaptureResult.Error(
                "النطق كان قصير أو مش واضح. قرّب من المايك وقول «$wakePhrase» كاملة."
            )
        }

        EnrollmentCaptureResult.Success(
            VoiceTemplateSample(
                id = UUID.randomUUID().toString(),
                label = wakePhrase,
                featureVector = speakerVector,
                mfccSequence = mfccSequence,
                durationMs = durationMs,
                peakRms = peakRms,
                timestampMs = System.currentTimeMillis()
            )
        )
    }

    private fun createBestAudioRecord(sampleRate: Int, minBuf: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val rec = runCatching {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                return rec
            }
            rec?.release()
        }
        return null
    }

    companion object {
        private const val MIN_MFCC_FRAMES = 10

        /**
         * Pure-Kotlin quality gate checking loudness, clipping, active speech frame ratio,
         * and speech-to-noise contrast. Safe to unit-test directly without AudioRecord.
         */
        fun validateAudioQuality(pcm: ShortArray, wakePhrase: String): EnrollmentQualityReport {
            if (pcm.size < AudioFeatureExtractor.SAMPLE_RATE / 4) {
                return EnrollmentQualityReport(
                    isAcceptable = false,
                    overallRms = 0f,
                    peakRms = 0f,
                    clippingRatio = 0f,
                    activeFrameRatio = 0f,
                    speechToNoiseRatio = 0f,
                    rejectionReasonAr = "التسجيل قصير جداً — قول «$wakePhrase» كاملة."
                )
            }

            val overallRms = AudioFeatureExtractor.computeRms(pcm, pcm.size)
            var clippedCount = 0
            for (s in pcm) {
                if (abs(s.toInt()) >= 32400) clippedCount++
            }
            val clippingRatio = clippedCount.toFloat() / pcm.size.toFloat()

            val frameLen = 320 // 20ms @ 16kHz
            val frameRmsList = mutableListOf<Float>()
            var pos = 0
            while (pos + frameLen <= pcm.size) {
                val slice = pcm.copyOfRange(pos, pos + frameLen)
                frameRmsList.add(AudioFeatureExtractor.computeRms(slice, frameLen))
                pos += frameLen
            }
            val sortedRms = frameRmsList.sorted()
            val noiseFloor = if (sortedRms.isNotEmpty()) {
                sortedRms[(sortedRms.size * 0.20f).toInt().coerceIn(0, sortedRms.lastIndex)].coerceAtLeast(0.002f)
            } else {
                0.005f
            }
            val peakFrameRms = sortedRms.lastOrNull() ?: overallRms
            val speechThresh = maxOf(0.014f, noiseFloor * 2.2f)
            val activeFrames = frameRmsList.count { it >= speechThresh }
            val activeRatio = if (frameRmsList.isEmpty()) 0f else activeFrames.toFloat() / frameRmsList.size.toFloat()
            val snr = peakFrameRms / noiseFloor.coerceAtLeast(0.002f)

            if (clippingRatio > 0.03f) {
                return EnrollmentQualityReport(
                    isAcceptable = false,
                    overallRms = overallRms,
                    peakRms = peakFrameRms,
                    clippingRatio = clippingRatio,
                    activeFrameRatio = activeRatio,
                    speechToNoiseRatio = snr,
                    rejectionReasonAr = "الصوت عالي لدرجة التشويش (Clipping) — أبعد المايك شوية وحاول تاني."
                )
            }

            if (overallRms < 0.009f || peakFrameRms < 0.018f || activeRatio < 0.08f) {
                return EnrollmentQualityReport(
                    isAcceptable = false,
                    overallRms = overallRms,
                    peakRms = peakFrameRms,
                    clippingRatio = clippingRatio,
                    activeFrameRatio = activeRatio,
                    speechToNoiseRatio = snr,
                    rejectionReasonAr = "الصوت واطي أوي أو مفيش كلام واضح — قرّب من المايك وقول «$wakePhrase»."
                )
            }

            if (activeRatio > 0.90f && snr < 1.85f) {
                return EnrollmentQualityReport(
                    isAcceptable = false,
                    overallRms = overallRms,
                    peakRms = peakFrameRms,
                    clippingRatio = clippingRatio,
                    activeFrameRatio = activeRatio,
                    speechToNoiseRatio = snr,
                    rejectionReasonAr = "فيه دوشة مستمرة في الخلفية مأثرة على وضوح البصمة — جرب في مكان أهدى شوية."
                )
            }

            return EnrollmentQualityReport(
                isAcceptable = true,
                overallRms = overallRms,
                peakRms = peakFrameRms,
                clippingRatio = clippingRatio,
                activeFrameRatio = activeRatio,
                speechToNoiseRatio = snr
            )
        }
    }
}
