package com.zone2runner.app.analysis

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 최소제곱 선형회귀 y ~ x 의 결과.
 * slope/intercept 외에 기울기 표준오차(se)와 결정계수(r2)를 함께 제공해,
 * 판단선을 bpm 상수가 아니라 도출 통계량(slope 대 k·se)으로 표현할 수 있게 한다(CLAUDE.md 값 원칙).
 */
data class LinFit(
    val slope: Double,
    val intercept: Double,
    val se: Double,   // 기울기 표준오차(n<3 또는 x 변동 0이면 NaN)
    val r2: Double,   // 0..1
    val n: Int,
)

/**
 * 순수 OLS 계산 모듈(SRP/DRY). SlopeEstimator(경사=고도~거리)와 DriftSlopeMetric(드리프트=심박~시간)이 공유.
 * 상태 없음 — 호출부가 창(window) 표본을 모아 넘긴다.
 */
object LinearRegression {

    /** xs/ys 표본으로 OLS 적합. n<2 또는 x 변동이 사실상 0이면 null. */
    fun fit(xs: DoubleArray, ys: DoubleArray): LinFit? {
        val n = xs.size
        if (n < 2 || ys.size != n) return null
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (i in 0 until n) {
            val x = xs[i]; val y = ys[i]
            sx += x; sy += y; sxx += x * x; sxy += x * y; syy += y * y
        }
        val denom = n * sxx - sx * sx
        if (abs(denom) < 1e-9) return null
        val slope = (n * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / n

        val meanY = sy / n
        val ssTot = (syy - n * meanY * meanY).coerceAtLeast(0.0)   // Σ(y-ȳ)²
        val ssxx = (sxx - sx * sx / n).coerceAtLeast(0.0)          // Σ(x-x̄)²
        val ssReg = slope * slope * ssxx
        val ssRes = (ssTot - ssReg).coerceAtLeast(0.0)
        val r2 = if (ssTot > 1e-12) (ssReg / ssTot).coerceIn(0.0, 1.0) else 0.0
        val se = if (n > 2 && ssxx > 1e-12) sqrt((ssRes / (n - 2)) / ssxx) else Double.NaN
        return LinFit(slope, intercept, se, r2, n)
    }

    /** 편의: (x,y) 쌍 리스트. */
    fun fit(points: List<Pair<Double, Double>>): LinFit? {
        if (points.size < 2) return null
        val xs = DoubleArray(points.size) { points[it].first }
        val ys = DoubleArray(points.size) { points[it].second }
        return fit(xs, ys)
    }
}
