package com.zone2runner.app.analysis

import com.zone2runner.app.analysis.AnalysisConfig.CADENCE_MIN_N
import com.zone2runner.app.analysis.AnalysisConfig.CADENCE_WIN
import com.zone2runner.app.analysis.AnalysisConfig.MDC_CADENCE
import kotlin.math.sqrt

/**
 * 케이던스 안정성 — 창 내 케이던스 표준편차(spm). σ > MDC(2.53spm, JOSPT 2016)면 불안정(피로/폼 신호).
 * 種類A 도출값. 임계는 문헌 최소검출변화(種類C). 손목 케이던스는 평균만 신뢰(spec-025 경계).
 */
class CadenceStabilityMetric : AnalysisMetric {
    override val id = ID
    override val mode = MetricMode.BOTH

    override fun onTick(input: AnalysisInput): MetricSample? {
        val to = input.tSec + 1
        return sigmaOf(input.window.spm(to - CADENCE_WIN, to))
    }

    override fun onSessionEnd(input: AnalysisInput): MetricSample? {
        val w = input.window
        return sigmaOf(w.spm(0, w.tNow + 1))
    }

    private fun sigmaOf(raw: DoubleArray): MetricSample? {
        val s = raw.filter { it > 0.0 }
        if (s.size < CADENCE_MIN_N) return null
        val mean = s.average()
        var v = 0.0; for (x in s) v += (x - mean) * (x - mean)
        val sd = sqrt(v / s.size)
        val unstable = sd > MDC_CADENCE
        val note = "케이던스 σ %.1f spm%s".format(sd, if (unstable) " (불안정)" else "")
        return MetricSample(ID, sd, trend = if (unstable) Trend.UP else Trend.FLAT, note = note)
    }

    companion object { const val ID = "cadence_stability" }
}
