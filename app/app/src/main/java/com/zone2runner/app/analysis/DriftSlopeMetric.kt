package com.zone2runner.app.analysis

import com.zone2runner.app.analysis.AnalysisConfig.CV_STEADY
import com.zone2runner.app.analysis.AnalysisConfig.DRIFT_MIN_N
import com.zone2runner.app.analysis.AnalysisConfig.K_SIGMA
import com.zone2runner.app.analysis.AnalysisConfig.W_DRIFT_RT

/**
 * HR 드리프트 기울기(bpm/분) — 정속 구간 심박~시간 OLS 회귀(Coyle 2001 심혈관 드리프트).
 * 종류A 도출값. 판단선은 bpm 상수가 아니라 도출 통계량 배수(slope 대 k·SE)로 표현(마법상수 금지).
 * 정속 게이트(페이스 변동계수 ≤ CV_STEADY)를 통과한 창에서만 유효(손목PPG는 정속서만 정확, adr-024).
 */
class DriftSlopeMetric : AnalysisMetric {
    override val id = ID
    override val mode = MetricMode.BOTH

    override fun onTick(input: AnalysisInput): MetricSample? {
        val to = input.tSec + 1
        val from = to - W_DRIFT_RT
        return fitSteady(input.window, from, to)
    }

    /** 세션종료: 세션 내 가장 안정적인 정속 창(최저 CV)에서 드리프트 기울기. 없으면 null. */
    override fun onSessionEnd(input: AnalysisInput): MetricSample? {
        val w = input.window
        val end = w.tNow + 1
        if (end < W_DRIFT_RT) return fitSteady(w, 0, end) // 짧은 세션은 전체로
        var best: MetricSample? = null
        var bestCv = Double.MAX_VALUE
        var start = 0
        while (start + W_DRIFT_RT <= end) {
            val cv = w.paceCv(start, start + W_DRIFT_RT)
            if (cv != null && cv <= CV_STEADY && cv < bestCv) {
                val s = fitSteady(w, start, start + W_DRIFT_RT)
                if (s != null && !s.gated) { best = s; bestCv = cv }
            }
            start += 60
        }
        return best
    }

    private fun fitSteady(w: SignalWindow, from: Int, to: Int): MetricSample? {
        val ts = w.times(from, to)
        val hr = w.hr(from, to)
        if (ts.size < DRIFT_MIN_N) return null
        val cv = w.paceCv(from, to) ?: return null
        if (cv > CV_STEADY) return MetricSample(ID, 0.0, gated = true, note = "비정속(CV %.2f)".format(cv))
        val f = LinearRegression.fit(ts, hr) ?: return null
        val slopePerMin = f.slope * 60.0
        val sePerMin = (f.se.takeIf { it.isFinite() } ?: 0.0) * 60.0
        // 유의 판정: 기울기가 잡음(k·SE)을 넘으면 추세. SE=0(완벽 적합)이면 기울기 자체가 유의.
        // 단 0.1 bpm/분 미만은 무시(FLAT).
        val signif = if (sePerMin <= 0.0) 0.1 else K_SIGMA * sePerMin
        val trend = when {
            slopePerMin > maxOf(0.1, signif) -> Trend.UP
            slopePerMin < -maxOf(0.1, signif) -> Trend.DOWN
            else -> Trend.FLAT
        }
        val note = "정속창 %ds, %+.1f bpm/분".format(ts.size, slopePerMin)
        return MetricSample(ID, slopePerMin, se = sePerMin, r2 = f.r2, trend = trend, note = note)
    }

    companion object { const val ID = "drift_slope" }
}
