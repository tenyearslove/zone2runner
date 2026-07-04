package com.zone2runner.app.pipeline

import android.content.Context
import org.json.JSONObject

/**
 * 개인 젖산역치(Zone2 상단 uFrac) 추정 MLP (spec-015, adr-014).
 * 세션 특징 8종 -> uFrac(HRR 비율) 회귀. ml/train_threshold.py가 뽑은 threshold_mlp.json 로드,
 * 순수 Kotlin 순전파(adr-011). 문헌(Etxegarai 2018, Zhu 2025) 접근의 경량 구현.
 */
class ThresholdEstimator private constructor(
    val features: List<String>,
    private val mean: DoubleArray,
    private val scale: DoubleArray,
    private val layers: List<Layer>,
    val metrics: Map<String, Double>,
) {
    private class Layer(val w: Array<DoubleArray>, val b: DoubleArray)

    /** feat 순서 = features(spec-015). 반환 = uFrac(안전 클램프 0.55~0.80). */
    fun estimateUFrac(feat: DoubleArray): Double {
        var x = DoubleArray(feat.size) { (feat[it] - mean[it]) / scale[it] }
        for ((idx, layer) in layers.withIndex()) {
            val out = DoubleArray(layer.b.size)
            for (o in out.indices) {
                var s = layer.b[o]
                val row = layer.w[o]
                for (i in x.indices) s += row[i] * x[i]
                out[o] = s
            }
            if (idx < layers.size - 1) for (o in out.indices) if (out[o] < 0) out[o] = 0.0
            x = out
        }
        return x[0].coerceIn(0.30, 0.75) // %HRmax 재보정으로 하한 완화(2026-07-04)
    }

    companion object {
        fun fromAssets(ctx: Context, name: String = "threshold_mlp.json"): ThresholdEstimator =
            fromJsonString(ctx.assets.open(name).bufferedReader().use { it.readText() })

        fun fromJsonString(json: String): ThresholdEstimator {
            val o = JSONObject(json)
            val feats = o.getJSONArray("features").let { a -> List(a.length()) { a.getString(it) } }
            val mean = o.getJSONArray("scaler_mean").toDoubleArray()
            val scale = o.getJSONArray("scaler_scale").toDoubleArray()
            val lj = o.getJSONArray("layers")
            val layers = ArrayList<Layer>(lj.length())
            for (li in 0 until lj.length()) {
                val lo = lj.getJSONObject(li)
                val wj = lo.getJSONArray("w")
                val w = Array(wj.length()) { wj.getJSONArray(it).toDoubleArray() }
                layers += Layer(w, lo.getJSONArray("b").toDoubleArray())
            }
            val metrics = HashMap<String, Double>()
            o.optJSONObject("metrics")?.let { m -> m.keys().forEach { k -> metrics[k] = m.getDouble(k) } }
            return ThresholdEstimator(feats, mean, scale, layers, metrics)
        }

        private fun org.json.JSONArray.toDoubleArray() = DoubleArray(length()) { getDouble(it) }
    }
}
