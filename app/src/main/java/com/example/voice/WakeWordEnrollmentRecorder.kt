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
 * extracts an acoustic + speaker vector, and returns a VoiceTemplateSample.
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
        val durationMs = 1700L
        val totalSamples = (sampleRate * durationMs / 1000L).toInt()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val audioRecord = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        }.getOrElse {
            return@withContext EnrollmentCaptureResult.Error(
                "تعذر فتح المايكروفون حالياً. اتأكد إنه مش مستخدم في تطبيق تاني."
            )
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return@withContext EnrollmentCaptureResult.Error(
                "المايكروفون غير جاهز للتسجيل على الجهاز ده."
            )
        }

        val captured = ShortArray(totalSamples)
        var readTotal = 0
        var peakRms = 0f

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
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
            onAmplitude(0f)
        }

        val trimmed = captured.copyOf(readTotal)
        val vector = AudioFeatureExtractor.extractSpeakerVector(trimmed)
            ?: return@withContext EnrollmentCaptureResult.Error(
                "الصوت كان واطي أوي أو قصير. قرّب من المايك وقول «$wakePhrase» بوضوح."
            )

        EnrollmentCaptureResult.Success(
            VoiceTemplateSample(
                id = UUID.randomUUID().toString(),
                label = wakePhrase,
                featureVector = vector,
                durationMs = durationMs,
                peakRms = peakRms,
                timestampMs = System.currentTimeMillis()
            )
        )
    }
}
