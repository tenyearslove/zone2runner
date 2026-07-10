package com.zone2runner.app.analysis

import com.zone2runner.app.analysis.AnalysisConfig.MIN_BIN_SEC
import com.zone2runner.app.analysis.AnalysisConfig.PACE_BIN
import com.zone2runner.app.analysis.AnalysisConfig.PACE_RUN_HI
import com.zone2runner.app.analysis.AnalysisConfig.PACE_RUN_LO
import kotlin.math.floor

/**
 * 서브맥시멀 HR@고정페이스 — 세션에서 가장 오래 머문 페이스 빈의 평균 심박(Sports Med-Open 2023, ICC 0.88).
 * 세션 간 동일 빈 비교로 유산소 체력 추세(낮아지면 개선). 種類A 도출값, 세션종료 지표.
 */
class SubmaxHrMetric : AnalysisMetric {
    override val id = ID
    override val mode = MetricMode.SESSION_END

    override fun onSessionEnd(input: AnalysisInput): MetricSample? {
        val w = input.window
        val end = w.tNow + 1
        val pace = w.pace(0, end)
        val hr = w.hr(0, end)
        if (pace.size != hr.size || pace.isEmpty()) return null
        // 페이스 빈 → (합계 HR, 표본수)
        val sum = HashMap<Int, Double>()
        val cnt = HashMap<Int, Int>()
        for (k in pace.indices) {
            val p = pace[k]; val h = hr[k]
            if (p < PACE_RUN_LO || p > PACE_RUN_HI || h <= 0.0) continue
            val bi = floor(p / PACE_BIN).toInt()
            sum[bi] = (sum[bi] ?: 0.0) + h
            cnt[bi] = (cnt[bi] ?: 0) + 1
        }
        val best = cnt.entries.filter { it.value >= MIN_BIN_SEC }.maxByOrNull { it.value } ?: return null
        val meanHr = sum[best.key]!! / best.value
        val center = (best.key + 0.5) * PACE_BIN
        val note = "%.2f min/km 구간 평균 %.0f bpm (%ds)".format(center, meanHr, best.value)
        return MetricSample(ID, meanHr, note = note)
    }

    companion object { const val ID = "submax_hr" }
}
