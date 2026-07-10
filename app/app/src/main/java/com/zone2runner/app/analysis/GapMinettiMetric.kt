package com.zone2runner.app.analysis

import com.zone2runner.app.analysis.AnalysisConfig.MINETTI_LEVEL
import com.zone2runner.app.analysis.AnalysisConfig.PACE_RUN_HI
import com.zone2runner.app.analysis.AnalysisConfig.minettiCr

/**
 * 경사보정 페이스(GAP) — Minetti 2002 측정 다항식으로 경사를 평지 등가 페이스로 정규화(種類C, 발명 상수 아님).
 * 오르막은 같은 심박이라도 실제 페이스가 느리므로, 평지 등가로 환산하면 더 빠른 페이스가 된다.
 * GAP(min/km) = 실제페이스 × Cr(0)/Cr(경사). 실시간 지표(코칭 컨텍스트/표시).
 */
class GapMinettiMetric : AnalysisMetric {
    override val id = ID
    override val mode = MetricMode.REALTIME

    override fun onTick(input: AnalysisInput): MetricSample? {
        val pace = input.window.latestPace()
        if (pace <= 0.1 || pace >= PACE_RUN_HI + 7.0) return null // 정지/걷기 sentinel 제외
        val i = input.window.latestSlope() / 100.0
        val cr = minettiCr(i)
        if (cr <= 1e-6) return null
        val gap = pace * (MINETTI_LEVEL / cr)
        val terrain = when { i > 0.02 -> "오르막"; i < -0.02 -> "내리막"; else -> "평지" }
        val note = "$terrain 보정 %.2f→%.2f min/km".format(pace, gap)
        return MetricSample(ID, gap, note = note)
    }

    companion object { const val ID = "gap" }
}
