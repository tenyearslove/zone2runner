package com.zone2runner.app.sensor

import com.zone2runner.app.analysis.LinearRegression

/**
 * 거리창 기반 경사(grade) 추정 — GPS 고도 노이즈에 강건 (사용자 지적 2026-07-06).
 *
 * 왜: 소비자 GPS 고도 정확도는 ±10~30m(수평의 2~3배). 연속 fix의 고도차를 그 순간 수평이동
 * (수 m)으로 나누면 경사가 수백 %로 튄다. 실제 3% 언덕(100m당 3m)조차 고도 노이즈에 묻힌다.
 * → 순간 미분 대신 **누적 이동거리 windowM(기본 150m) 구간의 고도 회귀 기울기**로 거시 판정한다.
 * 정량: ±12m 고도 노이즈를 평지 ±2%까지 누르려면 창이 ~200m 필요 — 즉 GPS 고도는 러닝 경사에
 * 근본적으로 부적합하며, 긴 창(지연 감수) + 넓은 데드밴드로 "거시 경향"만 본다. 정밀 %는 못 준다.
 *
 * 반환은 % grade. 앱은 주로 범주(오르막/평지/내리막, ±2% 데드밴드)로 소비하므로 과신하지 않는다.
 * (더 정확한 상대 고도는 기압계 — 후속 과제. 여기서는 새 권한/센서 없이 GPS만으로 최대한 개선.)
 */
class SlopeEstimator(
    private val windowM: Double = 150.0,  // 회귀 구간(수평 누적거리) — 짧으면 GPS 고도 노이즈 지배
    private val emaAlpha: Double = 0.12,   // 표시 안정화(작을수록 매끈)
    private val maxGradePct: Double = 15.0,
) {
    private data class P(val dist: Double, val alt: Double)
    private val buf = ArrayDeque<P>()
    private var cumDist = 0.0
    private var ema = 0.0
    private var seeded = false

    /** 새 위치 도착. horizM=직전 대비 수평 이동(m), altM=고도(m). grade(%) 반환. */
    fun update(horizM: Double, altM: Double): Double {
        if (horizM.isFinite() && horizM > 0.3) cumDist += horizM
        buf.addLast(P(cumDist, altM))
        // 창 밖(오래된) 점 제거 — 항상 windowM 이상 남기되 최소 2점
        while (buf.size > 2 && cumDist - buf.first().dist > windowM) buf.removeFirst()

        // 구간이 충분히 길 때만 회귀(짧으면 노이즈 지배 → 직전 값 유지)
        val span = cumDist - buf.first().dist
        if (buf.size >= 4 && span >= windowM * 0.6) {
            val slope = regressionSlope() * 100.0 // 고도/거리 → %
            val g = slope.coerceIn(-maxGradePct, maxGradePct)
            ema = if (seeded) ema + (g - ema) * emaAlpha else g.also { seeded = true }
        }
        return ema
    }

    /** 최소제곱 회귀로 alt~dist 기울기(고도변화/거리). 공유 원시모듈 사용(DRY). 노이즈가 평균화됨. */
    private fun regressionSlope(): Double {
        val xs = DoubleArray(buf.size); val ys = DoubleArray(buf.size)
        var i = 0
        for (p in buf) { xs[i] = p.dist; ys[i] = p.alt; i++ }
        return LinearRegression.fit(xs, ys)?.slope ?: 0.0
    }

    fun current(): Double = ema
}
