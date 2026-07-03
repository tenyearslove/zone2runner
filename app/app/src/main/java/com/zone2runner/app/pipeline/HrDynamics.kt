package com.zone2runner.app.pipeline

import android.content.Context
import org.json.JSONObject

/**
 * 개인 심박 동역학 모델 (spec-014, adr-013) — 온디바이스 회귀 MLP (8 -> 32 -> 16 -> 2, ReLU).
 *
 * "현재 상태 + 앞으로 유지할 페이스" -> [30초 뒤, 60초 뒤] 심박(HRR 비율)을 예측한다.
 * ml/train_hr_dynamics.py가 뽑은 assets/hr_dynamics.json(가중치+StandardScaler) 로드,
 * 순수 Kotlin 순전파(adr-011 방식). 분류가 아니라 회귀 — softmax 없음.
 *
 * 입력 특징 순서(spec-014):
 *   [hr_now_frac, hr_sus_frac, dHR, pace_plan, slope, spm, elapsed_min, decoupling]
 */
class HrDynamics private constructor(
    val features: List<String>,
    private val mean: DoubleArray,
    private val scale: DoubleArray,
    private val layers: List<Layer>,
    val horizonsSec: List<Int>,
    val metrics: Map<String, Double>,
) {
    private class Layer(val w: Array<DoubleArray>, val b: DoubleArray) // w[out][in]

    /** feat 순서 = features. 반환 = 각 horizon의 예측 hr_frac (HRR 비율). */
    fun predictFrac(feat: DoubleArray): DoubleArray {
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
        return x
    }

    /**
     * Zone2 목표 페이스 역질의 (spec-014 FR3): 후보 페이스를 스윕해 60초 뒤 예측 심박이
     * 밴드 [loFrac, hiFrac] 중심에 가장 가까운 페이스를 반환(min/km).
     * baseFeat = 페이스 슬롯(인덱스 3)만 바꿔 끼울 현재 상태 특징 벡터.
     */
    fun recommendPace(baseFeat: DoubleArray, loFrac: Double, hiFrac: Double): Double {
        val center = (loFrac + hiFrac) / 2.0
        var bestPace = baseFeat[3]
        var bestErr = Double.MAX_VALUE
        var p = PACE_MIN
        val feat = baseFeat.copyOf()
        while (p <= PACE_MAX + 1e-9) {
            feat[3] = p
            val fr60 = predictFrac(feat).last()
            val err = Math.abs(fr60 - center)
            if (err < bestErr) { bestErr = err; bestPace = p }
            p += PACE_STEP
        }
        return bestPace
    }

    companion object {
        const val PACE_MIN = 4.0
        const val PACE_MAX = 12.0
        const val PACE_STEP = 0.25

        fun fromAssets(ctx: Context, name: String = "hr_dynamics.json"): HrDynamics =
            fromJsonString(ctx.assets.open(name).bufferedReader().use { it.readText() })

        /** Context 없이 JSON 문자열에서 로드(단위 테스트용). */
        fun fromJsonString(json: String): HrDynamics {
            val o = JSONObject(json)
            val feats = o.getJSONArray("features").let { a -> List(a.length()) { a.getString(it) } }
            val mean = o.getJSONArray("scaler_mean").toDoubleArray()
            val scale = o.getJSONArray("scaler_scale").toDoubleArray()
            val layersJson = o.getJSONArray("layers")
            val layers = ArrayList<Layer>(layersJson.length())
            for (li in 0 until layersJson.length()) {
                val lo = layersJson.getJSONObject(li)
                val wj = lo.getJSONArray("w")
                val w = Array(wj.length()) { wj.getJSONArray(it).toDoubleArray() }
                val b = lo.getJSONArray("b").toDoubleArray()
                layers += Layer(w, b)
            }
            val horizons = o.optJSONArray("horizons_sec")?.let { a -> List(a.length()) { a.getInt(it) } }
                ?: listOf(30, 60)
            val metrics = HashMap<String, Double>()
            o.optJSONObject("metrics")?.let { m -> m.keys().forEach { k -> metrics[k] = m.getDouble(k) } }
            return HrDynamics(feats, mean, scale, layers, horizons, metrics)
        }

        private fun org.json.JSONArray.toDoubleArray() = DoubleArray(length()) { getDouble(it) }
    }
}
