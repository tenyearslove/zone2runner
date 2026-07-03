package com.zone2runner.app.pipeline

import android.content.Context
import com.zone2runner.app.domain.ZoneJudgment
import org.json.JSONObject

/**
 * [LEGACY — adr-013으로 판정 경로에서 해임] 온디바이스 Zone2 판정 MLP (7 -> 32 -> 16 -> 3, ReLU).
 *
 * 시뮬레이터 라벨 순환으로 "지속 심박이 상한 크게 초과인데 미달" 오판이 실기기에서 발견되어
 * 판정은 규칙(ZoneJudge)으로 전환, NN은 심박 동역학 모델(HrDynamics, spec-014)로 재조준됨.
 * 이 클래스와 assets/zone2_mlp.json은 결함 재현 회귀 테스트(Zone2ClassifierTest)와
 * 기록 보존용으로 유지한다. 런타임 판정 경로에서는 사용하지 않는다.
 *
 * ml/export_model.py가 뽑은 assets/zone2_mlp.json(가중치+StandardScaler)을 로드해
 * 순수 Kotlin 순전파로 추론한다. TFLite 런타임 불필요(adr-011) — 모델이 작아 충분.
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
        /**
         * 규칙 가드레일 여유(HRR 비율). 상한을 이만큼 초과/하한을 이만큼 미달하면
         * 규칙이 MLP를 무시(≈0.10 HRR ≈ 14bpm). 경계 부근(±margin)은 MLP가 판단.
         */
        const val GUARD_MARGIN = 0.10

        /** 규칙 폴백: hr_norm_u>0 초과, hr_norm_l<0 미달, 나머지 유지. (feat[0]=u,[1]=l) */
        fun ruleClassify(feat: DoubleArray): ZoneJudgment = when {
            feat[1] < 0.0 -> ZoneJudgment.BELOW
            feat[0] > 0.0 -> ZoneJudgment.ABOVE
            else -> ZoneJudgment.IN
        }

        /**
         * DP1 하이브리드 가드레일: MLP 판정을 규칙으로 감싼다.
         * MLP의 dHR(심박 추세) 특징이 절대 심박 위치를 압도해 "심박 180인데 미달" 같은
         * 오판을 내는 것을 방지 — 경계에서 GUARD_MARGIN 이상 벗어나면 규칙이 최종.
         * (feat[0]=hr_norm_u 상한대비, feat[1]=hr_norm_l 하한대비)
         */
        fun guard(mlp: ZoneJudgment, feat: DoubleArray, margin: Double = GUARD_MARGIN): ZoneJudgment = when {
            feat[0] > margin -> ZoneJudgment.ABOVE   // 상한을 크게 초과 → 무조건 초과
            feat[1] < -margin -> ZoneJudgment.BELOW  // 하한을 크게 미달 → 무조건 미달
            else -> mlp
        }

        fun fromAssets(ctx: Context, name: String = "zone2_mlp.json"): Zone2Classifier =
            fromJsonString(ctx.assets.open(name).bufferedReader().use { it.readText() })

        /** Context 없이 JSON 문자열에서 로드(단위 테스트/재사용용). */
        fun fromJsonString(json: String): Zone2Classifier {
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
