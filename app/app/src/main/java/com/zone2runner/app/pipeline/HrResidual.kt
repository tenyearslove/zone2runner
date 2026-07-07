package com.zone2runner.app.pipeline

import android.content.Context
import org.json.JSONObject
import kotlin.math.max

/**
 * 심박 예측 잔차 NN (gray-box / PINN, adr-020 후속, report-005).
 * 생리 ODE(HrOdeModel) 예측 '위에' 얹는 작은 신경망 — ODE가 못 담는 나머지(주로 드리프트 비선형)만
 * 보정한다. 오프라인 학습(`ml/train_hr_residual.py`), 폰은 순수 Kotlin 순전파로 '추론만'(실시간 재학습 안 함).
 * 출력(잔차)은 물리 경계(±clampFrac HRR)로 잘라 안전을 지킨다(무결성: 물리가 사실 확정, NN은 그 안에서만).
 *
 * 입력 features = [hr_now_frac, dHR_bpm_s, slope, elapsed_min] (dynFeaturesAt의 df[0],df[2],df[4],df[6]).
 * 출력 = 각 지평(30/60초)의 잔차 frac. HrOdeModel이 ODE 예측에 더한다.
 */
class HrResidual private constructor(
    private val mean: DoubleArray,
    private val scale: DoubleArray,
    private val layers: List<Layer>,
    val clampFrac: Double,
    val metrics: Map<String, Double>,
) {
    private class Layer(val w: Array<DoubleArray>, val b: DoubleArray)

    /** df(7종) → 잔차 frac 2개(30,60), 물리 경계로 clamp. */
    fun residual(df: DoubleArray, hrr: Double): DoubleArray {
        val feat = doubleArrayOf(df[0], df[2], df[4], df[6]) // hr_now_frac, dHR(bpm/s), slope, elapsed_min
        var x = DoubleArray(feat.size) { (feat[it] - mean[it]) / scale[it] }
        for ((idx, layer) in layers.withIndex()) {
            val out = DoubleArray(layer.b.size)
            for (o in out.indices) {
                var s = layer.b[o]; val row = layer.w[o]
                for (i in x.indices) s += row[i] * x[i]
                out[o] = s
            }
            if (idx < layers.size - 1) for (o in out.indices) out[o] = max(0.0, out[o]) // ReLU
            x = out
        }
        return DoubleArray(x.size) { x[it].coerceIn(-clampFrac, clampFrac) }
    }

    companion object {
        /** 에셋에서 로드. 없거나 파싱 실패 시 null(잔차 없이 ODE 단독 동작). */
        fun fromAssets(ctx: Context, name: String = "hr_residual.json"): HrResidual? =
            runCatching { fromJsonString(ctx.assets.open(name).bufferedReader().use { it.readText() }) }.getOrNull()

        fun fromJsonString(json: String): HrResidual {
            val o = JSONObject(json)
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
            val clamp = o.optDouble("clamp_frac", 0.08)
            val metrics = HashMap<String, Double>()
            o.optJSONObject("metrics")?.let { m -> m.keys().forEach { k -> metrics[k] = m.getDouble(k) } }
            return HrResidual(mean, scale, layers, clamp, metrics)
        }

        private fun org.json.JSONArray.toDoubleArray() = DoubleArray(length()) { getDouble(it) }
    }
}
