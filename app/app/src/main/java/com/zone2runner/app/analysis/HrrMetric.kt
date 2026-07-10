package com.zone2runner.app.analysis

import com.zone2runner.app.analysis.AnalysisConfig.EFFORT_END_HOLD
import com.zone2runner.app.analysis.AnalysisConfig.EFFORT_END_SPEED_RATIO
import com.zone2runner.app.analysis.AnalysisConfig.HRR_RECENT
import com.zone2runner.app.analysis.AnalysisConfig.HRR_WINDOW

/**
 * 심박 회복(HRR) — 노력 종료(속도 급감) 후 60초 심박 하강폭(Daanen 2012, 훈련상태/회복 지표).
 * 연속 Zone2 러닝엔 명시적 쿨다운이 없으므로 "속도가 직전 평균의 70% 아래로 지속"을 노력 종료 대리로 감지.
 * 감지 없으면 null(정직 — 산출 안 함). 세션종료 지표.
 */
class HrrMetric : AnalysisMetric {
    override val id = ID
    override val mode = MetricMode.SESSION_END

    override fun onSessionEnd(input: AnalysisInput): MetricSample? {
        val w = input.window
        val end = w.tNow + 1
        val times = w.times(0, end)
        val hr = w.hr(0, end)
        val pace = w.pace(0, end)
        val n = times.size
        if (n < HRR_RECENT + EFFORT_END_HOLD + 5) return null

        fun speed(k: Int) = if (pace[k] > 0.1) 60.0 / pace[k] else 0.0

        var bestDrop = Double.NEGATIVE_INFINITY
        var bestT = -1
        var k = HRR_RECENT
        while (k < n) {
            var rs = 0.0; var rc = 0
            for (j in (k - HRR_RECENT) until k) { rs += speed(j); rc++ }
            val recent = if (rc > 0) rs / rc else 0.0
            if (recent > 2.0) { // 뛰고 있던 상태에서만
                var held = true
                var j = k
                while (j < minOf(k + EFFORT_END_HOLD, n)) {
                    if (speed(j) >= EFFORT_END_SPEED_RATIO * recent) { held = false; break }
                    j++
                }
                if (held) {
                    val targetT = times[k] + HRR_WINDOW
                    var idx = -1
                    var m = k
                    while (m < n) { if (times[m] >= targetT) { idx = m; break }; m++ }
                    if (idx >= 0) {
                        val drop = hr[k] - hr[idx]
                        if (drop > bestDrop) { bestDrop = drop; bestT = times[k].toInt() }
                    }
                    k += HRR_WINDOW // 이 회복 구간은 건너뜀
                }
            }
            k++
        }
        if (bestT < 0 || bestDrop == Double.NEGATIVE_INFINITY) return null
        val note = "%02d:%02d 노력 후 60초 회복 %.0f bpm".format(bestT / 60, bestT % 60, bestDrop)
        return MetricSample(ID, bestDrop, note = note)
    }

    companion object { const val ID = "hrr" }
}
