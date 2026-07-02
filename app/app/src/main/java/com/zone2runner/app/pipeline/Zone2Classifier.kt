package com.zone2runner.app.pipeline

import android.content.Context
import com.zone2runner.app.domain.ZoneJudgment
import org.json.JSONObject

/**
 * 온디바이스 Zone2 판정 MLP (7 -> 32 -> 16 -> 3, ReLU).
 *
 * ml/export_model.py가 뽑은 assets/zone2_mlp.json(가중치+StandardScaler)을 로드해
 * 순수 Kotlin 순전파로 추론한다. TFLite 런타임 불필요(adr-011) — 모델이 작아 충분.
 * 규칙 폴백(콜드스타트/모델 미로드)도 제공.
 */
class Zone2Classifier private constructor(
    val features: List<String>,
    private val mean: DoubleArray,
    private val scale: DoubleArray,
    private val layers: List<Layer>,
    val metrics: Map<String, Double>,
) {
    private class Layer(val w: Array<DoubleArray>, val b: DoubleArray) // w[out][in]

    data class Result(val judgment: ZoneJudgment, val probs: FloatArray)

    /** feat 순서 = features(=simulator FEATURE_NAMES). */
    fun classify(feat: DoubleArray): Result {
        var x = DoubleArray(feat.size) { (feat[it] - mean[it]) / scale[it] }
        for ((idx, layer) in layers.withIndex()) {
            val out = DoubleArray(layer.b.size)
            for (o in out.indices) {
                var s = layer.b[o]
                val row = layer.w[o]
                for (i in x.indices) s += row[i] * x[i]
                out[o] = s
            }
            // 마지막 레이어(로짓) 제외 ReLU
            if (idx < layers.size - 1) for (o in out.indices) if (out[o] < 0) out[o] = 0.0
            x = out
        }
        val probs = softmax(x)
        var best = 0
        for (i in x.indices) if (x[i] > x[best]) best = i
        return Result(ZoneJudgment.fromIndex(best), probs)
    }

    private fun softmax(z: DoubleArray): FloatArray {
        val m = z.max()
        val e = DoubleArray(z.size) { Math.exp(z[it] - m) }
        val sum = e.sum()
        return FloatArray(z.size) { (e[it] / sum).toFloat() }
    }

    companion object {
        /** 규칙 폴백: hr_norm_u>0 초과, hr_norm_l<0 미달, 나머지 유지. (feat[0]=u,[1]=l) */
        fun ruleClassify(feat: DoubleArray): ZoneJudgment = when {
            feat[1] < 0.0 -> ZoneJudgment.BELOW
            feat[0] > 0.0 -> ZoneJudgment.ABOVE
            else -> ZoneJudgment.IN
        }

        fun fromAssets(ctx: Context, name: String = "zone2_mlp.json"): Zone2Classifier {
            val json = ctx.assets.open(name).bufferedReader().use { it.readText() }
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
            val metrics = HashMap<String, Double>()
            o.optJSONObject("metrics")?.let { m ->
                m.keys().forEach { k -> metrics[k] = m.getDouble(k) }
            }
            return Zone2Classifier(feats, mean, scale, layers, metrics)
        }

        private fun org.json.JSONArray.toDoubleArray() = DoubleArray(length()) { getDouble(it) }
    }
}
