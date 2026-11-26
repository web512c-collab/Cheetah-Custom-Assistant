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

sealed class EnrollmentCaptureResult {
    data class Success(val sample: VoiceTemplateSample) : EnrollmentCaptureResult()
    data class Error(val messageAr: String) : EnrollmentCaptureResult()
}

/**
 * Records a short spoken wake-word utterance directly from AudioRecord (16kHz Mono PCM),
 * extracts both the 13-MFCC DTW sequence and the 14-D Voice Match speaker vector.
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
                "لازم توافق على صلاحية المايكروفون الأول عشان تسجل اسم التنبيه."
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
                "المايكروفون غير متاح أو مستخدم حالياً في تطبيق تاني."
            )
        }

        val trimmed = captured.copyOf(readTotal)
        val mfccSequence = AudioFeatureExtractor.extractMfccSequence(trimmed, noiseFloorRms = 0.008f)
        val speakerVector = AudioFeatureExtractor.extractSpeakerVector(trimmed)

        if (mfccSequence == null || speakerVector == null) {
            return@withContext EnrollmentCaptureResult.Error(
                "الصوت كان واطي أوي أو قصير. قرّب من المايك وقول «$wakePhrase» بوضوح."
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
}
