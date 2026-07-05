package com.zone2runner.app.pipeline

import kotlin.math.abs

/**
 * 심박 예측 온라인 개인 보정 (spec-018, adr-019).
 * 기본 NN(HrDynamics)은 시뮬 학습된 "평균 러너" 예측이라 개인 동역학(HR 반응 속도/드리프트/피로)과
 * 어긋난다. 매초 "과거 예측 vs 지금 실제"를 비교해 개인 잔차를 온라인 학습(LMS)하고, 다음 예측을 보정한다.
 * DP2(Bayesian 경계 개인화)와 같은 철학 — 평균 모델 위에 개인 보정을 얹는다.
 *
 * 정직한 전제: 예측은 "이 페이스 유지 시" 조건부라, 페이스가 유지된 샘플만 학습에 쓴다(페이스를
 * 바꿨으면 예측이 틀린 게 아니라 조건이 바뀐 것).
 *
 * 잔차 특징 x = [1, hr_sus_frac, dHR·10, elapsed_min/30] (동역학 df에서 추출, 페이스 무관 → 추천 페이스와 정합).
 */
class HrPredictionLearner(w30Init: DoubleArray? = null, w60Init: DoubleArray? = null) {

    private val w30 = w30Init?.copyOf(FN) ?: DoubleArray(FN)
    private val w60 = w60Init?.copyOf(FN) ?: DoubleArray(FN)

    private class Pending(val target: Int, val x: DoubleArray, val baseFrac: Double, val plannedPace: Double)
    private val q30 = ArrayDeque<Pending>()
    private val q60 = ArrayDeque<Pending>()

    // 성과 추적(보정 효과 검증용)
    private var n30 = 0; private var n60 = 0
    private var seBase30 = 0.0; private var seCorr30 = 0.0
    private var seBase60 = 0.0; private var seCorr60 = 0.0
    val updates: Int get() = n30 + n60

    private fun resFeat(df: DoubleArray) = doubleArrayOf(1.0, df[1], df[2] * 10.0, df[6] / 30.0)
    private fun dot(w: DoubleArray, x: DoubleArray): Double { var s = 0.0; for (i in w.indices) s += w[i] * x[i]; return s }
    private fun clampCorr(v: Double) = v.coerceIn(-CORR_CLAMP, CORR_CLAMP)

    /** base 예측 frac[30,60]에 개인 보정을 더한 값 반환. */
    fun correct(df: DoubleArray, baseFrac: DoubleArray): DoubleArray {
        val x = resFeat(df)
        return doubleArrayOf(baseFrac[0] + clampCorr(dot(w30, x)), baseFrac[1] + clampCorr(dot(w60, x)))
    }

    /** 추천 페이스용 보정량(60초). 밴드를 이만큼 내려 base 스윕하면 보정 후 밴드 중심을 겨냥. */
    fun correction60(df: DoubleArray): Double = clampCorr(dot(w60, resFeat(df)))

    /** 이번 예측(base)을 버퍼에 저장. */
    fun record(tSec: Int, df: DoubleArray, baseFrac: DoubleArray, plannedPace: Double) {
        val x = resFeat(df)
        q30.addLast(Pending(tSec + 30, x, baseFrac[0], plannedPace))
        q60.addLast(Pending(tSec + 60, x, baseFrac[1], plannedPace))
        while (q30.size > CAP) q30.removeFirst()
        while (q60.size > CAP) q60.removeFirst()
    }

    /** 실제 심박(frac)+실제 페이스 도착 → 만기된 예측 학습(페이스 유지된 것만). */
    fun observe(tSec: Int, actualFrac: Double, actualPace: Double) {
        learn(q30, w30, tSec, actualFrac, actualPace, h30 = true)
        learn(q60, w60, tSec, actualFrac, actualPace, h30 = false)
    }

    private fun learn(q: ArrayDeque<Pending>, w: DoubleArray, tSec: Int, actual: Double, actualPace: Double, h30: Boolean) {
        while (q.isNotEmpty() && q.first().target <= tSec) {
            val p = q.removeFirst()
            if (p.target != tSec) continue                       // 정확히 만기된 것만(샘플 누락 무시)
            if (abs(p.plannedPace - actualPace) > PACE_TOL) continue // 페이스 안 유지 → 조건 달라짐, 학습 제외
            val corr = clampCorr(dot(w, p.x))
            val errBase = actual - p.baseFrac
            val errCorr = actual - (p.baseFrac + corr)
            for (i in w.indices) w[i] += LR * errCorr * p.x[i]   // LMS(온라인 경사) 갱신
            if (h30) { seBase30 += errBase * errBase; seCorr30 += errCorr * errCorr; n30++ }
            else { seBase60 += errBase * errBase; seCorr60 += errCorr * errCorr; n60++ }
        }
    }

    /** 성과: base/corrected RMSE(bpm). 갱신 없으면 0. */
    fun rmseBpm(hrr: Double): Rmse {
        fun r(se: Double, cnt: Int) = if (cnt > 0) Math.sqrt(se / cnt) * hrr else 0.0
        return Rmse(r(seBase30, n30), r(seCorr30, n30), r(seBase60, n60), r(seCorr60, n60))
    }
    data class Rmse(val base30: Double, val corr30: Double, val base60: Double, val corr60: Double)

    fun weights(): DoubleArray = w30 + w60

    companion object {
        const val FN = 4
        const val LR = 0.02        // LMS 학습률
        const val PACE_TOL = 0.6   // 페이스 유지 허용(min/km)
        const val CORR_CLAMP = 0.12 // 보정 상한(HRR frac) — 폭주 방지
        const val CAP = 240        // 버퍼 상한(메모리)
    }
}
