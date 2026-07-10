package com.zone2runner.app.analysis

import kotlin.math.sqrt

/**
 * 개인 노이즈플로어(種類B 학습값, spec-025 §4) — 한 지표의 정상 범위를 온라인 EWMA로 추정.
 * 중립 prior(NaN)에서 출발해 그 사람 관측으로 수렴(평균 m, 분산 v). 판단선 = m + k·σ̂ — bpm 상수가
 * 아니라 개인 도출통계라 사람마다 자가 보정(CLAUDE.md 값 원칙). 세션 간 상태(m/v/count)를 저장/복원.
 */
class NoiseFloor(
    private val alpha: Double = 0.1,   // EWMA 계수(클수록 최근 가중)
    seedMean: Double = Double.NaN,
    seedVar: Double = Double.NaN,
    seedCount: Int = 0,
) {
    private var m = seedMean
    private var v = seedVar
    var count = seedCount; private set

    /** 새 관측 반영. */
    fun observe(x: Double) {
        count++
        if (m.isNaN()) { m = x; v = 0.0; return }
        val d = x - m
        m += alpha * d
        val prev = if (v.isNaN()) 0.0 else v
        v = (1 - alpha) * (prev + alpha * d * d)
    }

    fun mean(): Double = if (m.isNaN()) 0.0 else m
    fun sigma(): Double = if (v.isNaN() || v < 0.0) 0.0 else sqrt(v)

    /** 개인 판단선 m + k·σ̂. 관측이 충분히 쌓였을 때만 의미(count 확인은 호출부). */
    fun threshold(k: Double): Double = mean() + k * sigma()

    // 영속화용 원시 상태(NaN 가능 — 미학습 표현).
    fun meanRaw(): Double = m
    fun varRaw(): Double = v
    fun ready(minCount: Int): Boolean = count >= minCount && !m.isNaN()
}
