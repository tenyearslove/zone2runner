package com.zone2runner.app

import com.zone2runner.app.domain.Profile
import com.zone2runner.app.domain.Zone2Prior
import com.zone2runner.app.pipeline.Personalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** spec-013 수락 기준(AC1~AC4, AC6) 검증. */
class Zone2PriorTest {

    private fun profile(body: Int = 3, fit: Int = 3, freq: Int = 3, h: Int = 170, w: Int = 70) =
        Profile(35, 58, 183, heightCm = h, weightKg = w, bodyType = body, fitnessLevel = fit, weeklyFreq = freq)

    @Test fun ac1_centerSelection_equalsLegacyFormula() {
        val prior = Zone2Prior.of(profile())
        assertEquals(0.70, prior.uFrac0, 1e-9)
        assertEquals(8.0, prior.sigma0Bpm, 1e-9) // 기존 고정 σ와 동일(하위 호환)
        assertEquals(0.0, prior.offset, 1e-9)
    }

    @Test fun ac2_allCombinations_stayInSpecRange() {
        for (b in 1..5) for (f in 1..5) for (q in 1..5) {
            val prior = Zone2Prior.of(profile(b, f, q))
            assertTrue("offset 범위: $b/$f/$q", prior.offset in -0.08..0.06)
            assertTrue("uFrac0 범위: $b/$f/$q", prior.uFrac0 in 0.60..0.78)
        }
    }

    @Test fun ac2_directionality() {
        // 엘리트+매일 > 중앙 > 입문+비만+거의안함
        val high = Zone2Prior.of(profile(body = 2, fit = 5, freq = 5))
        val mid = Zone2Prior.of(profile())
        val low = Zone2Prior.of(profile(body = 5, fit = 1, freq = 1))
        assertTrue(high.uFrac0 > mid.uFrac0)
        assertTrue(low.uFrac0 < mid.uFrac0)
        assertEquals(0.70 + 0.06, high.uFrac0, 1e-9)          // 0 + 0.05 + 0.02 = 0.07 → clamp +0.06
        assertEquals(0.70 - 0.08, low.uFrac0, 1e-9)           // -0.04 -0.05 -0.02 → clamp -0.08
    }

    @Test fun ac3_sigmaGrowsWithExtremity_andBmiMismatch() {
        assertEquals(8.0, Zone2Prior.of(profile()).sigma0Bpm, 1e-9)
        assertEquals(8.0 + 0.8 * 6, Zone2Prior.of(profile(body = 5, fit = 1, freq = 1, h = 170, w = 90)).sigma0Bpm, 1e-9)
        // BMI 정상(제안 보통=3)인데 비만형(5) 선택 → 2단계 불일치 → +2
        val mismatch = Zone2Prior.of(profile(body = 5, h = 170, w = 60)) // BMI 20.8 → 제안 3
        assertEquals(8.0 + 0.8 * 2 + 2.0, mismatch.sigma0Bpm, 1e-9)
    }

    @Test fun ac4_bmiSuggestion_koreanCriteria() {
        assertEquals(3, Zone2Prior.suggestBodyType(170, 60))   // BMI 20.8 보통
        assertEquals(4, Zone2Prior.suggestBodyType(170, 70))   // BMI 24.2 과체중
        assertEquals(5, Zone2Prior.suggestBodyType(170, 75))   // BMI 26.0 비만
        assertEquals(2, Zone2Prior.suggestBodyType(170, 52))   // BMI 18.0 마름
        assertEquals(1, Zone2Prior.suggestBodyType(170, 47))   // BMI 16.3 매우마름
        assertEquals(null, Zone2Prior.suggestBodyType(0, 70))  // 미입력
    }

    @Test fun rhrUnknown_estimatedFromFactors_andWidensSigma() {
        // 러닝 수준↑/빈도↑ → RHR 추정 낮아짐(체력-RHR 역상관)
        assertEquals(66, Zone2Prior.estimateRhr(3, 3))
        assertEquals(74, Zone2Prior.estimateRhr(1, 3))
        assertEquals(50, Zone2Prior.estimateRhr(5, 5))
        assertTrue(Zone2Prior.estimateRhr(1, 1) <= 85 && Zone2Prior.estimateRhr(5, 5) >= 45)
        // RHR 추정치 사용 → σ0 +3
        val est = Zone2Prior.of(profile().copy(rhrEstimated = true))
        assertEquals(8.0 + 3.0, est.sigma0Bpm, 1e-9)
    }

    @Test fun safetyGuard_uFracNeverExceeds080() {
        // "HR 160인데 Zone2 유지" 실기기 관찰(2026-07-03) 재발 방지:
        // 어떤 관측 폭주에도 상한은 HRR 80% + 세션 내 ±10bpm을 넘지 않는다
        val p = Personalization(profile())
        repeat(50) { p.update(175.0, obsSd = 1.0) } // 극단 관측 폭주
        assertTrue("uFrac 상한 0.80", p.boundary().uFrac <= 0.80 + 1e-9)
        val mu0 = 58 + 0.70 * 125
        assertTrue("세션 내 이동 ±10bpm", p.muUpper <= mu0 + 10 + 1e-9)
    }

    @Test fun ac6_personalizationStartsAtPrior_andStillConverges() {
        // prior 반영: 엘리트 프로필은 상한이 높게 시작
        val elite = Personalization(profile(fit = 5, freq = 5))
        assertEquals(0.70 + 0.06, elite.boundary().uFrac, 1e-9) // 0.07 → 총합 clamp +0.06
        // 갱신 동작 회귀: 관측을 반복하면 관측 쪽으로 수렴, σ 감소
        val p = Personalization(profile())
        val sigma0 = p.sigma
        repeat(5) { p.update(150.0, obsSd = 5.0) }
        assertTrue("관측(150bpm) 방향으로 이동", p.muUpper > 58 + 0.70 * 125 - 15 && p.muUpper < 152)
        assertTrue("불확실성 감소", p.sigma < sigma0)
    }
}
