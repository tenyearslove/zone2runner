package com.zone2runner.app.pipeline

import kotlin.math.abs
import kotlin.math.exp

/**
 * 심박 예측 = 생리 ODE(모집단 prior) + 개인 파라미터 온라인 추정 (adr-020, spec-022).
 *
 * 문헌 골격(Apple/Nature npj 2023, MDPI 2024): 운동 심박은 "수요를 향한 1차 지연 + 카디악 드리프트"의
 * 상미분방정식으로 움직이고, 학습은 그 ODE의 '개인 파라미터'에만 쓴다(HR을 블랙박스로 예측하지 않는다).
 * 여기선 단일 사용자·온디바이스용 경량판:
 *
 *   예측(현재 페이스 유지 시):
 *     hSS = hNow + τ·(dHR/dt)                 // 현재 응답 기울기로 정상상태 심박을 역추정
 *     hr(t+h) = hSS + (hNow − hSS)·e^(−h/τ) + drift·(h/60)   // mono-exponential + 드리프트
 *
 *   개인 파라미터(온라인 학습): τ(반응 시상수), drift(분당 드리프트), 그리고 페이스 추천용 수요맵
 *     hSS_demand(speed, slope) = a + b·speed + c·slope  (LMS로 개인 적합, 모집단값에서 시작).
 *
 * "이 페이스 유지 시" 조건부라, 페이스가 유지된 표본만 학습에 쓴다(페이스 바뀌면 조건이 달라진 것).
 * 값은 전부 HR frac(HRR 비율). 세션 종료 시 파라미터 저장 → 다음 세션이 이어서 개인화.
 */
class HrOdeModel(init: DoubleArray? = null, private val residual: HrResidual? = null) {

    // 개인 파라미터 (init 있으면 이어서, 없으면 모집단 prior)
    private var tauSec = init?.getOrNull(0) ?: TAU0
    private var driftPerMin = init?.getOrNull(1) ?: 0.0
    private var demandA = init?.getOrNull(2) ?: DEMAND_A0
    private var demandB = init?.getOrNull(3) ?: DEMAND_B0
    private var demandC = init?.getOrNull(4) ?: DEMAND_C0

    val horizonsSec = listOf(30, 60)

    /**
     * df = FeatureExtractor.dynFeaturesAt (7종). hrr = 여유심박. 반환 = [frac30, frac60].
     * 최종 = 생리 ODE(뼈대) + 잔차NN(gray-box, 있으면). 잔차는 HrResidual이 물리 경계로 clamp한다.
     */
    fun predict(df: DoubleArray, hrr: Double): DoubleArray {
        val hNow = df[0]                       // hr_now_frac
        val dhPerSec = if (hrr > 0) df[2] / hrr else 0.0  // bpm/s → frac/s
        val hSS = (hNow + tauSec * dhPerSec).coerceIn(SS_MIN, SS_MAX)
        val res = residual?.residual(df, hrr)  // [res30, res60] frac (clamp됨) 또는 null
        val out = DoubleArray(horizonsSec.size) { i ->
            val h = horizonsSec[i].toDouble()
            val ode = hSS + (hNow - hSS) * exp(-h / tauSec) + driftPerMin * (h / 60.0)
            (ode + (res?.get(i) ?: 0.0)).coerceIn(SS_MIN, SS_MAX)
        }
        // 설명용 60초 예측 분해(frac): pred60 − hNow = 추세 + 드리프트 + 잔차 (clamp 전 기준)
        val e60 = exp(-60.0 / tauSec)
        last = Explain(hNow = hNow, hSSFrac = hSS,
            trendFrac = (hSS - hNow) * (1 - e60),   // 현재 추세로 정상상태를 향해 가는 몫
            driftFrac = driftPerMin,                // 60초분 누적 드리프트
            residFrac = res?.get(1) ?: 0.0,         // 잔차 NN 보정
            predFrac = out.last())
        return out
    }

    /** 마지막 predict()의 60초 예측 분해(설명용, frac 단위). */
    data class Explain(val hNow: Double, val hSSFrac: Double, val trendFrac: Double,
                       val driftFrac: Double, val residFrac: Double, val predFrac: Double)
    var last = Explain(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        private set

    /**
     * Zone2 목표 페이스 역질의: 정상상태 수요맵을 밴드 중심으로 역산(해석적).
     * hSS_demand = a + b·speed + c·slope = center → speed* → pace(min/km).
     */
    fun recommendPace(df: DoubleArray, loFrac: Double, hiFrac: Double): Double {
        val center = (loFrac + hiFrac) / 2.0
        val slope = df[4]
        val speed = if (demandB != 0.0) (center - demandA - demandC * slope) / demandB else 0.0
        val paceMinKm = if (speed > 0.1) 1000.0 / (speed * 60.0) else PACE_MAX
        return paceMinKm.coerceIn(PACE_MIN, PACE_MAX)
    }

    // ---- 온라인 학습 ----
    private class Pending(val target: Int, val h: Int, val hNow: Double, val dhPerSec: Double,
                          val speed: Double, val slope: Double, val plannedPace: Double, val pred: Double)
    private val queue = ArrayDeque<Pending>()

    private var n = 0
    private var seBase = 0.0  // 모집단 τ(고정) 예측 오차² — 개선 비교용
    private var seModel = 0.0 // 개인화 후 오차²
    val updates: Int get() = n

    /** 이번 예측을 만기(t+h) 검증용으로 버퍼. */
    fun record(tSec: Int, df: DoubleArray, pred: DoubleArray, hrr: Double, plannedPace: Double) {
        val hNow = df[0]; val dhPerSec = if (hrr > 0) df[2] / hrr else 0.0
        val speed = speedOf(plannedPace); val slope = df[4]
        for (i in horizonsSec.indices) {
            queue.addLast(Pending(tSec + horizonsSec[i], horizonsSec[i], hNow, dhPerSec, speed, slope, plannedPace, pred[i]))
        }
        while (queue.size > CAP) queue.removeFirst()
    }

    /** 실제 심박(frac)+실제 페이스 도착 → 만기된 예측 학습(페이스 유지된 것만). */
    fun observe(tSec: Int, actualFrac: Double, actualPace: Double) {
        while (queue.isNotEmpty() && queue.first().target <= tSec) {
            val p = queue.removeFirst()
            if (p.target != tSec) continue
            if (abs(p.plannedPace - actualPace) > PACE_TOL) continue // 페이스 안 유지 → 학습 제외
            learn(p, actualFrac)
        }
    }

    private fun learn(p: Pending, actual: Double) {
        val h = p.h.toDouble()
        // 현재 파라미터 예측(버퍼의 pred는 예측 당시 값 — 학습 후 파라미터로 다시 계산)
        val pred = predAt(p.hNow, p.dhPerSec, h)
        val err = actual - pred
        // 모집단 τ(고정) 기준선 오차 — 개인화 이득 정량화
        val base = p.hNow + TAU0 * p.dhPerSec
        val basePred = (base.coerceIn(SS_MIN, SS_MAX)).let { it + (p.hNow - it) * exp(-h / TAU0) }
        seBase += (actual - basePred) * (actual - basePred); seModel += err * err; n++

        // 드리프트: 항상 소량 반영(느린 상승 성분)
        driftPerMin = (driftPerMin + LR_DRIFT * err * (h / 60.0)).coerceIn(-DRIFT_CLAMP, DRIFT_CLAMP)

        // τ: 전이 구간(|dHR/dt| 유의)에서만 갱신(정상상태에선 τ 미식별). 수치 기울기 LMS.
        if (abs(p.dhPerSec) > DH_INFORM) {
            val eps = 1.0
            val grad = (predAt(p.hNow, p.dhPerSec, h, tauSec + eps) - pred) / eps
            tauSec = (tauSec + LR_TAU * err * grad).coerceIn(TAU_MIN, TAU_MAX)
        }

        // 수요맵: 준정상(|dHR/dt| 작음) 표본을 (speed,slope)→정상상태 관측으로 LMS 적합
        if (abs(p.dhPerSec) < DH_STEADY) {
            val predD = demandA + demandB * p.speed + demandC * p.slope
            val eD = actual - predD
            demandA = (demandA + LR_DEMAND * eD).coerceIn(0.1, 0.6)
            demandB = (demandB + LR_DEMAND * eD * p.speed).coerceIn(0.02, 0.30)
            demandC = (demandC + LR_DEMAND * eD * p.slope).coerceIn(-0.02, 0.08)
        }
    }

    private fun predAt(hNow: Double, dhPerSec: Double, h: Double, tau: Double = tauSec): Double {
        val hSS = (hNow + tau * dhPerSec).coerceIn(SS_MIN, SS_MAX)
        return (hSS + (hNow - hSS) * exp(-h / tau) + driftPerMin * (h / 60.0)).coerceIn(SS_MIN, SS_MAX)
    }

    /** 성과: 모집단 τ 기준선 대비 개인화 RMSE(bpm). 갱신 없으면 0. */
    fun rmseBpm(hrr: Double): Rmse {
        fun r(se: Double) = if (n > 0) Math.sqrt(se / n) * hrr else 0.0
        return Rmse(r(seBase), r(seModel))
    }
    data class Rmse(val base: Double, val model: Double)

    /** 영속화용 파라미터 [τ, drift, a, b, c]. */
    fun params(): DoubleArray = doubleArrayOf(tauSec, driftPerMin, demandA, demandB, demandC)
    val tau: Double get() = tauSec
    val drift: Double get() = driftPerMin

    private fun speedOf(paceMinKm: Double) = if (paceMinKm > 0.1) 1000.0 / (paceMinKm * 60.0) else 0.0

    companion object {
        // 모집단 prior(생리 문헌): HR on-kinetics 시상수 ~30초, 드리프트 0에서 시작
        const val TAU0 = 30.0
        const val TAU_MIN = 12.0; const val TAU_MAX = 80.0
        // 수요맵 prior: frac ≈ 0.30 + 0.14·speed(m/s) + 0.02·slope(%) — Zone2 대역서 단조/생리적
        const val DEMAND_A0 = 0.30; const val DEMAND_B0 = 0.14; const val DEMAND_C0 = 0.02
        const val SS_MIN = 0.20; const val SS_MAX = 1.15
        const val PACE_MIN = 4.0; const val PACE_MAX = 12.0
        const val PACE_TOL = 0.6          // 페이스 유지 허용(min/km)
        const val DH_INFORM = 0.0015      // τ 학습 임계(frac/s, ≈0.1bpm/s@HRR140 근방)
        const val DH_STEADY = 0.0010      // 수요맵 학습(준정상) 임계
        const val LR_TAU = 120.0; const val LR_DRIFT = 0.02; const val LR_DEMAND = 0.01
        const val DRIFT_CLAMP = 0.12; const val CAP = 240
    }
}
