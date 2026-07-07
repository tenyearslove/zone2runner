package com.zone2runner.app

import com.zone2runner.app.pipeline.HrOdeModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * 심박 예측 ODE 단위 검증 (adr-020): mono-exponential 예측의 물리적 타당성 + 페이스 역질의 방향성 +
 * 온라인 τ 추정이 참값으로 수렴하는지.
 */
class HrOdeModelTest {

    private val hrr = 140.0

    /** df = [hr_now_frac, hr_sus_frac, dHR(bpm/s), pace_plan, slope, spm, elapsed_min]. */
    private fun df(hNow: Double, dHrBpmS: Double, pace: Double = 6.0, slope: Double = 0.0) =
        doubleArrayOf(hNow, hNow, dHrBpmS, pace, slope, 165.0, 10.0)

    @Test fun rising_prediction_between_now_and_steadystate() {
        val m = HrOdeModel()
        val hNow = 0.55
        val dHr = 0.2 // bpm/s 상승 중
        val pred = m.predict(df(hNow, dHr), hrr)
        // 상승 중이면 예측은 현재보다 높고, 60초 예측 >= 30초 예측(단조 상승), 정상상태 아래
        assertTrue("30초 예측 > 현재", pred[0] > hNow)
        assertTrue("60초 >= 30초", pred[1] >= pred[0])
        val hSS = hNow + HrOdeModel.TAU0 * (dHr / hrr)
        assertTrue("정상상태 넘지 않음", pred[1] <= hSS + 1e-6)
    }

    @Test fun steady_prediction_stays_flat() {
        val m = HrOdeModel()
        val pred = m.predict(df(0.6, 0.0), hrr)
        assertEquals("정상상태면 예측≈현재", 0.6, pred[0], 0.02)
        assertEquals(0.6, pred[1], 0.02)
    }

    @Test fun mono_exponential_shape_matches_formula() {
        val m = HrOdeModel()
        val hNow = 0.5; val dHr = 0.3
        val pred = m.predict(df(hNow, dHr), hrr)
        val tau = HrOdeModel.TAU0
        val hSS = hNow + tau * (dHr / hrr)
        val exp60 = hSS + (hNow - hSS) * exp(-60.0 / tau)
        assertEquals("60초 예측이 mono-exp 공식과 일치", exp60, pred[1], 1e-6)
    }

    @Test fun recommendPace_slowerForLowerBand() {
        val m = HrOdeModel()
        val lowBand = m.recommendPace(df(0.6, 0.0), 0.40, 0.50) // 낮은 목표
        val highBand = m.recommendPace(df(0.6, 0.0), 0.65, 0.75) // 높은 목표
        // 낮은 목표 심박 → 더 느린(값 큰) 페이스여야 함(단조성)
        assertTrue("낮은 밴드가 더 느린 페이스: $lowBand vs $highBand", lowBand >= highBand)
        assertTrue(lowBand in HrOdeModel.PACE_MIN..HrOdeModel.PACE_MAX)
    }

    @Test fun explain_decomposition_sums_to_prediction() {
        val m = HrOdeModel()
        val pred = m.predict(df(0.5, 0.3), hrr) // 상승 중
        val e = m.last
        // pred60 = 현재 + 추세 + 드리프트 (설명 항목 합이 예측과 일치 — 설명용이성 정직성)
        val sum = e.hNow + e.trendFrac + e.driftFrac
        assertEquals("분해 합 = 예측", pred[1], sum, 1e-9)
        assertEquals("predFrac 저장", pred[1], e.predFrac, 1e-9)
        assertTrue("상승 중이면 추세 항 양수", e.trendFrac > 0)
    }

    @Test fun online_tau_converges_toward_true() {
        // 참 τ=20초인 1차 지연 계열을 연속으로(불연속 리셋 없이) 만들어 관측시키면
        // 추정 τ가 30(초기)→20 쪽으로 이동해야 한다. 목표를 180초마다 바꿔 여러 전이 구간을 제공.
        val trueTau = 20.0
        val m = HrOdeModel()
        var hr = 0.45
        val pace = 6.0
        for (t in 0 until 1800) {
            val target = if ((t / 180) % 2 == 0) 0.75 else 0.50 // 피스와이즈-상수 목표(연속 HR)
            val dhPerSec = (target - hr) / trueTau               // 1차 지연으로 연속 추종
            val dHrBpmS = dhPerSec * hrr
            val d = df(hr, dHrBpmS, pace)
            val pred = m.predict(d, hrr)
            m.record(t, d, pred, hrr, pace)
            m.observe(t, hr, pace)
            hr += dhPerSec
        }
        val tau = m.params()[0]
        println("추정 τ=%.1f (참 20, 초기 30)".format(tau))
        assertTrue("τ 추정이 참값 20 쪽으로 유의하게 이동: $tau", tau < 26.0)
    }
}
