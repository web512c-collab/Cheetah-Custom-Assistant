package com.example.data

import android.content.Context
import com.example.core.model.VoiceTemplateSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/**
 * Self-contained local store for acoustic MFCC-DTW wake-word templates and Voice Match
 * speaker vectors. Persisted in app-private filesDir and excluded from cloud backups.
 */
class VoiceTemplateStore(context: Context) {

    private val templateFile = File(context.filesDir, "cheetah_voice_templates.json")
    private val _samples = MutableStateFlow<List<VoiceTemplateSample>>(emptyList())
    val samples: StateFlow<List<VoiceTemplateSample>> = _samples.asStateFlow()

    init {
        _samples.value = loadFromDisk()
    }

    suspend fun addSample(sample: VoiceTemplateSample) = withContext(Dispatchers.IO) {
        val updated = (_samples.value + sample).takeLast(MAX_SAMPLES)
        saveToDisk(updated)
        _samples.value = updated
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        if (templateFile.exists()) {
            templateFile.delete()
        }
        _samples.value = emptyList()
    }

    fun reload() {
        _samples.value = loadFromDisk()
    }

    fun getAveragedSpeakerCentroid(): FloatArray? {
        val current = _samples.value
        if (current.isEmpty()) return null
        val dim = current.first().featureVector.size
        if (dim == 0) return null
        val centroid = FloatArray(dim)
        var validCount = 0
        for (sample in current) {
            if (sample.featureVector.size == dim) {
                for (i in 0 until dim) {
                    centroid[i] += sample.featureVector[i]
                }
                validCount++
            }
        }
        if (validCount == 0) return null
        var norm = 0f
        for (i in 0 until dim) {
            centroid[i] /= validCount.toFloat()
            norm += centroid[i] * centroid[i]
        }
        val denom = sqrt(norm).coerceAtLeast(1e-6f)
        for (i in 0 until dim) {
            centroid[i] /= denom
        }
        return centroid
    }

    private fun loadFromDisk(): List<VoiceTemplateSample> {
        if (!templateFile.exists()) return emptyList()
        return runCatching {
            val raw = templateFile.readText()
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val vecArray = obj.getJSONArray("vec")
                    val vec = FloatArray(vecArray.length()) { idx ->
                        vecArray.getDouble(idx).toFloat()
                    }
                    val mfccJson = obj.optJSONArray("mfcc")
                    val mfccSeq = if (mfccJson != null) {
                        List(mfccJson.length()) { fIdx ->
                            val frameArr = mfccJson.getJSONArray(fIdx)
                            FloatArray(frameArr.length()) { cIdx ->
                                frameArr.getDouble(cIdx).toFloat()
                            }
                        }
                    } else {
                        emptyList()
                    }
                    add(
                        VoiceTemplateSample(
                            id = obj.getString("id"),
                            label = obj.getString("label"),
                            featureVector = vec,
                            mfccSequence = mfccSeq,
                            durationMs = obj.optLong("durationMs", 1200L),
                            peakRms = obj.optDouble("peakRms", 0.0).toFloat(),
                            timestampMs = obj.optLong("timestampMs", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveToDisk(samples: List<VoiceTemplateSample>) {
        val array = JSONArray()
        for (s in samples) {
            val vecArray = JSONArray()
            s.featureVector.forEach { vecArray.put(it.toDouble()) }
            val mfccArray = JSONArray()
            s.mfccSequence.forEach { frame ->
                val frameArr = JSONArray()
                frame.forEach { coeff -> frameArr.put(coeff.toDouble()) }
                mfccArray.put(frameArr)
            }
            val obj = JSONObject().apply {
                put("id", s.id)
                put("label", s.label)
                put("vec", vecArray)
                put("mfcc", mfccArray)
                put("durationMs", s.durationMs)
                put("peakRms", s.peakRms.toDouble())
                put("timestampMs", s.timestampMs)
            }
            array.put(obj)
        }
        templateFile.writeText(array.toString())
    }

    companion object {
        const val MAX_SAMPLES = 3
    }
}
