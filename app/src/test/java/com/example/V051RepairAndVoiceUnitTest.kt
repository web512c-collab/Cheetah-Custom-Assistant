package com.example

import com.example.core.model.AssistantSettings
import com.example.core.model.EgyptianIntent
import com.example.core.model.GuardrailVerdict
import com.example.core.model.PermissionHealthState
import com.example.core.model.VoiceTemplateSample
import com.example.core.model.WakePipelineState
import com.example.data.VoiceTemplateStore
import com.example.guardrails.CommandGuardrailValidator
import com.example.voice.AudioFeatureExtractor
import com.example.voice.BundledYaSheetaAcousticModel
import com.example.voice.VoiceErrorMapper
import com.example.voice.VoiceFailureKind
import com.example.voice.WakeStateMachine
import com.example.voice.WakeThresholdCalibrator
import com.example.voice.WakeWordEngine
import com.example.voice.WakeWordEnrollmentRecorder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class V051RepairAndVoiceUnitTest {

    private fun locateBundledModelFile(): File {
        val candidates = listOf(
            File("src/main/assets/models/cheetah_ya_sheeta_kws.bin"),
            File("app/src/main/assets/models/cheetah_ya_sheeta_kws.bin")
        )
        return candidates.firstOrNull { it.exists() && it.isFile }
            ?: error("Could not locate cheetah_ya_sheeta_kws.bin from ${File(".").absolutePath}")
    }

    private fun synthesizeYaSheetaAcoustic(
        seed: Long,
        durScale: Double = 1.0,
        pitchShift: Double = 1.0,
        amplitude: Double = 0.65
    ): ShortArray {
        val rng = Random(seed)
        val sr = AudioFeatureExtractor.SAMPLE_RATE
        val dur = 0.78 * durScale
        val n = (sr * dur).toInt()
        val f0 = 155.0 * pitchShift
        var lp = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / sr
            val u = i.toDouble() / n
            val valFloat = when {
                u < 0.28 -> {
                    val p = u / 0.28
                    val f1 = 380.0 + 340.0 * p
                    val f2 = 2100.0 - 800.0 * p
                    val env = sin(PI * p).coerceAtLeast(0.0).pow(0.5)
                    env * (0.45 * sin(2 * PI * f0 * t) + 0.35 * sin(2 * PI * f1 * t) + 0.22 * sin(2 * PI * f2 * t))
                }
                u < 0.52 -> {
                    val p = (u - 0.28) / 0.24
                    val env = sin(PI * p).coerceAtLeast(0.0).pow(0.45)
                    val hiss = rng.nextDouble() * 2.0 - 1.0
                    val hp = hiss - 0.85 * lp
                    lp = hiss
                    val fric = 0.5 * hp + 0.35 * sin(2 * PI * (2800.0 + rng.nextDouble() * 1000.0) * t)
                    env * 0.48 * fric
                }
                u < 0.78 -> {
                    val p = (u - 0.52) / 0.26
                    val env = sin(PI * p).coerceAtLeast(0.0).pow(0.5)
                    env * (0.45 * sin(2 * PI * f0 * t) + 0.36 * sin(2 * PI * 310.0 * pitchShift * t) + 0.26 * sin(2 * PI * 2250.0 * t))
                }
                else -> {
                    val p = (u - 0.78) / 0.22
                    if (p < 0.22) {
                        0.52 * ((rng.nextDouble() * 2.0 - 1.0) * (1.0 - p / 0.22))
                    } else {
                        val env = sin(PI * ((p - 0.22) / 0.78)).coerceAtLeast(0.0).pow(0.6)
                        env * (0.42 * sin(2 * PI * f0 * t) + 0.34 * sin(2 * PI * 720.0 * t) + 0.22 * sin(2 * PI * 1250.0 * t))
                    }
                }
            }
            val noisy = (valFloat * amplitude + (rng.nextDouble() * 2.0 - 1.0) * 0.01).coerceIn(-0.98, 0.98)
            (noisy * 32767.0).toInt().toShort()
        }
    }

    private fun makeTemplateSample(id: String, pcm: ShortArray): VoiceTemplateSample {
        val mfcc = AudioFeatureExtractor.extractMfccSequence(pcm, noiseFloorRms = 0.008f)
            ?: error("Failed to extract MFCC for test sample $id")
        val vec = AudioFeatureExtractor.extractSpeakerVector(pcm) ?: FloatArray(14)
        return VoiceTemplateSample(
            id = id,
            label = "يا شيتا",
            featureVector = vec,
            mfccSequence = mfcc,
            durationMs = 800L,
            peakRms = 0.25f,
            timestampMs = 1_000L
        )
    }

    @Test
    fun `wake threshold calibrator adapts across consistent vs scattered 5-sample enrollments and respects sensitivity bounds`() {
        val basePcm = synthesizeYaSheetaAcoustic(seed = 11L, durScale = 1.0, pitchShift = 1.0)
        val consistentSamples = (1..5).map { idx ->
            makeTemplateSample("c$idx", synthesizeYaSheetaAcoustic(seed = 100L + idx, durScale = 0.99 + idx * 0.004, pitchShift = 1.0))
        }
        val scatteredSamples = listOf(
            makeTemplateSample("s1", synthesizeYaSheetaAcoustic(seed = 201L, durScale = 0.75, pitchShift = 0.80)),
            makeTemplateSample("s2", synthesizeYaSheetaAcoustic(seed = 202L, durScale = 1.25, pitchShift = 1.30)),
            makeTemplateSample("s3", synthesizeYaSheetaAcoustic(seed = 203L, durScale = 0.85, pitchShift = 1.18)),
            makeTemplateSample("s4", synthesizeYaSheetaAcoustic(seed = 204L, durScale = 1.18, pitchShift = 0.84)),
            makeTemplateSample("s5", basePcm)
        )

        val uncalibrated = WakeThresholdCalibrator.calibrate(consistentSamples.take(1), sensitivity = 0.65f)
        assertFalse(uncalibrated.isCalibrated)

        val calConsistent = WakeThresholdCalibrator.calibrate(consistentSamples, sensitivity = 0.65f)
        val calScattered = WakeThresholdCalibrator.calibrate(scatteredSamples, sensitivity = 0.65f)
        assertTrue(calConsistent.isCalibrated)
        assertTrue(calScattered.isCalibrated)
        assertTrue(calConsistent.threshold in WakeThresholdCalibrator.MIN_CALIBRATED_THRESHOLD..WakeThresholdCalibrator.MAX_CALIBRATED_THRESHOLD)
        assertTrue(calScattered.threshold in WakeThresholdCalibrator.MIN_CALIBRATED_THRESHOLD..WakeThresholdCalibrator.MAX_CALIBRATED_THRESHOLD)
        assertTrue(
            "Consistent speaker samples (${calConsistent.intraSampleMeanDtw}) should yield higher or equal threshold (${calConsistent.threshold}) than scattered (${calScattered.threshold})",
            calConsistent.threshold >= calScattered.threshold
        )

        val lowSens = WakeThresholdCalibrator.calibrate(consistentSamples, sensitivity = 0.25f)
        val highSens = WakeThresholdCalibrator.calibrate(consistentSamples, sensitivity = 0.95f)
        assertTrue("Higher sensitivity should lower threshold: high=${highSens.threshold} low=${lowSens.threshold}", highSens.threshold <= lowSens.threshold)
    }

    @Test
    fun `chkw v1 cannot trigger wake standalone without user MFCC templates and voice match defaults OFF`() = runTest {
        val tempDir = Files.createTempDirectory("sheeta-v051-wake-test").toFile()
        try {
            val store = VoiceTemplateStore(tempDir, persistToDisk = false)
            assertEquals(5, VoiceTemplateStore.MIN_REQUIRED_SAMPLES)
            assertEquals(8, VoiceTemplateStore.MAX_SAMPLES)

            val bundled = locateBundledModelFile().inputStream().use {
                BundledYaSheetaAcousticModel.loadFromStream(it)
            }.getOrThrow()

            val engine = WakeWordEngine(templateStore = store, bundledModel = bundled)
            val defaults = AssistantSettings()
            assertFalse("Voice Match must default OFF", defaults.voiceMatchEnabled)
            assertTrue("Internet search defaults ON with transparent disclosure", defaults.internetSearchEnabled)

            // Even with a clean 'يا شيتا' synthetic utterance, CHKW-v1 MUST NOT trigger wake without user MFCC templates
            val probeUtterance = synthesizeYaSheetaAcoustic(seed = 101L, durScale = 1.0, pitchShift = 1.0)
            val withoutUserSamples = engine.evaluate(probeUtterance, noiseFloorRms = 0.01f, settings = defaults)
            assertFalse("CHKW-v1 must not trigger standalone wake without user MFCC templates", withoutUserSamples.detected)
            assertEquals("NEEDS_ENROLLMENT", withoutUserSamples.detectionSource)
            assertTrue(
                "CHKW prior must be capped <= 0.10f, got ${withoutUserSamples.wakeConfidence}",
                withoutUserSamples.wakeConfidence <= WakeWordEngine.MAX_CHKW_TIE_BREAKER_PRIOR + 0.0001f
            )

            // Enroll 5 MFCC samples -> engine triggers cleanly
            repeat(5) { idx ->
                val samplePcm = synthesizeYaSheetaAcoustic(seed = 300L + idx, durScale = 0.98 + idx * 0.01, pitchShift = 1.0)
                store.addSample(makeTemplateSample("u$idx", samplePcm))
            }
            val withUserSamples = engine.evaluate(probeUtterance, noiseFloorRms = 0.01f, settings = defaults)
            assertTrue("Enrolled 5-sample MFCC-DTW should detect cleanly: $withUserSamples", withUserSamples.detected)
            assertTrue("Voice match should pass when OFF", withUserSamples.voiceMatchPassed)

            // Verify ordinary command (OpenApp / MediaControl) is never blocked even when voiceMatchPassed = false
            val openVerdict = CommandGuardrailValidator.validate(
                intent = EgyptianIntent.OpenApp("YouTube"),
                settings = defaults,
                permissions = PermissionHealthState(hasMicPermission = true),
                voiceMatchPassed = false
            )
            assertTrue(openVerdict is GuardrailVerdict.Allowed)

            bundled.close()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `enrollment audio quality gate rejects silence clipping and continuous noise while accepting clean speech`() {
        val sr = AudioFeatureExtractor.SAMPLE_RATE
        // 1. Silence
        val silence = ShortArray(sr) { 0 }
        val silenceReport = WakeWordEnrollmentRecorder.validateAudioQuality(silence, "يا شيتا")
        assertFalse(silenceReport.isAcceptable)

        // 2. Hard clipping (> 3% at full scale)
        val clipped = ShortArray(sr) { i ->
            if (sin(2.0 * PI * 320.0 * i / sr) >= 0.0) Short.MAX_VALUE else (-Short.MAX_VALUE.toInt()).toShort()
        }
        val clippedReport = WakeWordEnrollmentRecorder.validateAudioQuality(clipped, "يا شيتا")
        assertFalse(clippedReport.isAcceptable)
        assertTrue(clippedReport.clippingRatio > 0.03f)

        // 3. Flat continuous broadband noise (high active ratio, low SNR contrast)
        val rng = Random(42L)
        val continuousNoise = ShortArray(sr) {
            ((rng.nextDouble() * 2.0 - 1.0) * 0.16 * 32767.0).toInt().toShort()
        }
        val noiseReport = WakeWordEnrollmentRecorder.validateAudioQuality(continuousNoise, "يا شيتا")
        assertFalse(noiseReport.isAcceptable)

        // 4. Clean modulated speech-like utterance with quiet lead-in/tail
        val cleanSpeech = ShortArray((sr * 1.4).toInt()) { i ->
            val t = i.toDouble() / sr
            if (t < 0.25 || t > 1.05) {
                (rng.nextGaussian() * 40.0).toInt().coerceIn(-500, 500).toShort()
            } else {
                val env = sin(PI * ((t - 0.25) / 0.80)).coerceAtLeast(0.0)
                val carrier = 0.5 * sin(2.0 * PI * 210.0 * t) + 0.35 * sin(2.0 * PI * 540.0 * t)
                (env * carrier * 12000.0).toInt().toShort()
            }
        }
        val cleanReport = WakeWordEnrollmentRecorder.validateAudioQuality(cleanSpeech, "يا شيتا")
        assertTrue("Expected clean speech to pass quality gate: ${cleanReport.rejectionReasonAr}", cleanReport.isAcceptable)
    }

    @Test
    fun `wake state machine cycles cleanly and recovers from watchdog timeout`() {
        val now = AtomicLong(1_000L)
        val sm = WakeStateMachine(clockMs = { now.get() })
        assertTrue(sm.transition(WakePipelineState.WAKE_DETECTED, "dtw_hit"))
        assertTrue(sm.transition(WakePipelineState.COMMAND_LISTENING, "vosk_open"))
        now.addAndGet(WakeStateMachine.TIMEOUT_COMMAND_LISTENING_MS + 500L)
        assertTrue(sm.checkWatchdog())
        assertEquals(WakePipelineState.IDLE_WAKE_LISTENING, sm.currentState)
    }

    @Test
    fun `voice error mapper differentiates mic denied os background block missing vosk model load error and hardware error`() {
        val denied = VoiceErrorMapper.mapFailure("SecurityException", hasMicPermission = false, hasLocalSttModel = true)
        assertEquals(VoiceFailureKind.MIC_PERMISSION_DENIED, denied.kind)
        assertTrue(denied.suggestSettingsTab)

        val bgBlocked = VoiceErrorMapper.mapFailure("BACKGROUND_MIC_BLOCKED", hasMicPermission = true, hasLocalSttModel = true, isBackgroundContext = true)
        assertEquals(VoiceFailureKind.OS_BACKGROUND_MIC_BLOCKED, bgBlocked.kind)

        val noModel = VoiceErrorMapper.mapFailure("NO_STT_MODEL", hasMicPermission = true, hasLocalSttModel = false)
        assertEquals(VoiceFailureKind.NO_LOCAL_STT_MODEL, noModel.kind)
        assertTrue(noModel.suggestSttSetup)

        val loadFailed = VoiceErrorMapper.mapFailure("STT_MODEL_LOAD_FAILED", hasMicPermission = true, hasLocalSttModel = true)
        assertEquals(VoiceFailureKind.STT_MODEL_LOAD_FAILED, loadFailed.kind)

        val hwFailed = VoiceErrorMapper.mapFailure("AUDIORECORD_INIT_FAILED", hasMicPermission = true, hasLocalSttModel = true)
        assertEquals(VoiceFailureKind.MIC_HARDWARE_OR_CAPTURE_FAILED, hwFailed.kind)

        val noiseOnly = VoiceErrorMapper.mapFailure("", hasMicPermission = true, hasLocalSttModel = true)
        assertEquals(VoiceFailureKind.NOISE_OR_NO_SPEECH, noiseOnly.kind)

        val distinctMessages = setOf(
            denied.messageAr,
            bgBlocked.messageAr,
            noModel.messageAr,
            loadFailed.messageAr,
            hwFailed.messageAr,
            noiseOnly.messageAr
        )
        assertEquals("Every voice failure mode must have a distinct Arabic diagnostic message", 6, distinctMessages.size)
    }
}
