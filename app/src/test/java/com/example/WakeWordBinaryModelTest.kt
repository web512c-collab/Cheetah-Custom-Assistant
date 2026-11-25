package com.example

import com.example.core.model.AssistantSettings
import com.example.core.model.WakeWordSetupMode
import com.example.voice.AudioFeatureExtractor
import com.example.voice.BundledYaSheetaAcousticModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class WakeWordBinaryModelTest {

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
        noiseLvl: Double = 0.015
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
            val noisy = (valFloat + (rng.nextDouble() * 2.0 - 1.0) * noiseLvl).coerceIn(-0.98, 0.98)
            (noisy * 32767.0).toInt().toShort()
        }
    }

    @Test
    fun bundledBinaryModelLoadsVerifiesCrcAndMatchesContract() {
        val file = locateBundledModelFile()
        val model = file.inputStream().use { BundledYaSheetaAcousticModel.loadFromStream(it) }.getOrThrow()

        assertTrue(model.checksumVerified)
        assertEquals(16000, model.sampleRateHz)
        assertEquals(24, model.timeSteps)
        assertEquals(13, model.numMfcc)
        assertEquals(64, model.inputDim)
        assertEquals(32, model.hiddenDim)
        assertEquals(2, model.numClasses)
        assertEquals(file.length().toInt(), model.modelSizeBytes)
        model.close()
    }

    @Test
    fun corruptedBinaryModelFailsCrc32Verification() {
        val bytes = locateBundledModelFile().readBytes()
        bytes[20] = (bytes[20].toInt() xor 0x55).toByte()
        val result = runCatching { BundledYaSheetaAcousticModel.parseBuffer(ByteBuffer.wrap(bytes)) }
        assertTrue("Corrupted CHKW binary must fail CRC32 check", result.isFailure)
    }

    @Test
    fun bundledModelDetectsYaSheetaAndRejectsNoiseAndNonMatchingContours() {
        val model = locateBundledModelFile().inputStream().use {
            BundledYaSheetaAcousticModel.loadFromStream(it)
        }.getOrThrow()

        val normal = synthesizeYaSheetaAcoustic(seed = 101L, durScale = 1.0, pitchShift = 1.0)
        val fast = synthesizeYaSheetaAcoustic(seed = 202L, durScale = 0.86, pitchShift = 1.12)
        val slow = synthesizeYaSheetaAcoustic(seed = 303L, durScale = 1.15, pitchShift = 0.88)

        val resNormal = model.evaluate(normal, noiseFloorRms = 0.01f, sensitivityOverride = 0.65f)
        val resFast = model.evaluate(fast, noiseFloorRms = 0.01f, sensitivityOverride = 0.65f)
        val resSlow = model.evaluate(slow, noiseFloorRms = 0.01f, sensitivityOverride = 0.65f)

        assertTrue("Normal 'يا شيتا' should detect: $resNormal", resNormal.detected)
        assertTrue("Fast 'يا شيتا' should detect: $resFast", resFast.detected)
        assertTrue("Slow 'يا شيتا' should detect: $resSlow", resSlow.detected)

        // Pure tone negative
        val sr = AudioFeatureExtractor.SAMPLE_RATE
        val pureTone = ShortArray((sr * 0.75).toInt()) { i ->
            (0.35 * sin(2.0 * PI * 680.0 * i / sr) * 32767.0).toInt().toShort()
        }
        val resTone = model.evaluate(pureTone, noiseFloorRms = 0.01f, sensitivityOverride = 0.65f)
        assertFalse("Pure tone must be rejected: $resTone", resTone.detected)

        // Broadband noise negative
        val noiseRng = Random(99L)
        val broadband = ShortArray((sr * 0.75).toInt()) {
            ((noiseRng.nextDouble() * 2.0 - 1.0) * 0.30 * 32767.0).toInt().toShort()
        }
        val resNoise = model.evaluate(broadband, noiseFloorRms = 0.01f, sensitivityOverride = 0.65f)
        assertFalse("Broadband noise must be rejected: $resNoise", resNoise.detected)

        // Feature tensor contract check
        val tensor = AudioFeatureExtractor.extractKwsFeatureTensor(normal)
        assertNotNull(tensor)
        assertEquals(24, tensor!!.size)
        assertEquals(16, tensor[0].size)

        // Default settings check: 'يا شيتا' is active without requiring user voice recording
        val defaultSettings = AssistantSettings()
        assertEquals("يا شيتا", defaultSettings.wakePhrase)
        assertEquals(WakeWordSetupMode.TEXT_TYPED, defaultSettings.wakeSetupMode)

        model.close()
    }
}
