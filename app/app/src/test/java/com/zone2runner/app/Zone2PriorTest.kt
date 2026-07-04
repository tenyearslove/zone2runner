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

    // %HRmax 기준(2026-07-04): uFrac0 = (hrmaxFrac*maxHr - rhr)/hrr. 기본 프로필 maxHr183/rhr58/hrr125.
    private fun expectedUFrac0(hrmaxFrac: Double, maxHr: Int = 183, rhr: Int = 58, hrr: Double = 125.0) =
        ((hrmaxFrac.coerceIn(0.60, 0.78) * maxHr - rhr) / hrr).coerceIn(0.30, 0.75)

    @Test fun ac1_centerSelection_hrmaxBasis() {
        val prior = Zone2Prior.of(profile())
        assertEquals(expectedUFrac0(0.70), prior.uFrac0, 1e-6) // %HRmax 0.70 → HRR 비율 환산
        assertEquals(8.0, prior.sigma0Bpm, 1e-9) // 기존 고정 σ와 동일(하위 호환)
        assertEquals(0.0, prior.offset, 1e-9)
    }

    @Test fun ac2_allCombinations_stayInSpecRange() {
        for (b in 1..5) for (f in 1..5) for (q in 1..5) {
            val prior = Zone2Prior.of(profile(b, f, q))
            assertTrue("offset 범위: $b/$f/$q", prior.offset in -0.08..0.06)
            assertTrue("uFrac0 범위(%HRmax 환산 클램프): $b/$f/$q", prior.uFrac0 in 0.30..0.75)
        }
    }

    @Test fun ac2_directionality() {
        // 엘리트+매일 > 중앙 > 입문+비만+거의안함 (%HRmax 목표 오프셋 방향 보존)
        val high = Zone2Prior.of(profile(body = 2, fit = 5, freq = 5))
        val mid = Zone2Prior.of(profile())
        val low = Zone2Prior.of(profile(body = 5, fit = 1, freq = 1))
        assertTrue(high.uFrac0 > mid.uFrac0)
        assertTrue(low.uFrac0 < mid.uFrac0)
        assertEquals(expectedUFrac0(0.70 + 0.06), high.uFrac0, 1e-6) // clamp +0.06
        assertEquals(expectedUFrac0(0.70 - 0.08), low.uFrac0, 1e-6)  // clamp -0.08
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
        assertTrue("uFrac 상한 0.75", p.boundary().uFrac <= 0.75 + 1e-9)
        val mu0 = 58 + expectedUFrac0(0.70) * 125
        assertTrue("세션 내 이동 ±10bpm", p.muUpper <= mu0 + 10 + 1e-6)
    }

    @Test fun talkTest_movesBoundaryTowardObservation() {
        // BORDERLINE(말 끊기기 시작 ≈ VT1): 현재 HR을 상한 관측으로 → 그 방향으로 이동
        val p = Personalization(profile())
        val mu0 = p.muUpper
        val curHr = (mu0 + 8).toInt() // 현재 HR이 상한 추정보다 위
        p.observeTalkTest(curHr, com.zone2runner.app.pipeline.TalkState.BORDERLINE)
        assertTrue("BORDERLINE은 현재 HR쪽으로 상한 상승", p.muUpper > mu0)
        assertTrue("불확실성 감소", p.sigma < profile().let { Zone2Prior.of(it).sigma0Bpm })

        // BORDERLINE(좁은 σ)은 COMFORTABLE(넓은 σ)보다 더 확신 → 갱신 후 σ가 작다
        val a = Personalization(profile()); val b = Personalization(profile())
        val hr = (a.muUpper + 3).toInt()
        a.observeTalkTest(hr, com.zone2runner.app.pipeline.TalkState.BORDERLINE)
        b.observeTalkTest(hr, com.zone2runner.app.pipeline.TalkState.COMFORTABLE)
        assertTrue("BORDERLINE이 더 확신(σ 작음)", a.sigma < b.sigma)

        // HARD(말 못 함): 상한이 현재 HR 아래 → 하향
        val c = Personalization(profile())
        val hi = (c.muUpper + 2).toInt()
        c.observeTalkTest(hi, com.zone2runner.app.pipeline.TalkState.HARD)
        assertTrue("HARD는 상한을 낮춘다", c.muUpper < (profile().restingHr + Zone2Prior.of(profile()).uFrac0 * profile().hrr) + 2)
    }

    @Test fun ac6_personalizationStartsAtPrior_andStillConverges() {
        // prior 반영: 엘리트 프로필은 상한이 높게 시작 (fit5+freq5 = 0.05+0.02 = 0.07 → clamp +0.06)
        val elite = Personalization(profile(fit = 5, freq = 5))
        assertEquals(expectedUFrac0(0.70 + 0.06), elite.boundary().uFrac, 1e-6)
        // 갱신 동작 회귀: 관측을 반복하면 관측 쪽으로 수렴, σ 감소
        val p = Personalization(profile())
        val sigma0 = p.sigma
        val mu0 = 58 + expectedUFrac0(0.70) * 125
        repeat(5) { p.update(mu0 + 5, obsSd = 5.0) } // prior보다 약간 위 관측
        assertTrue("관측 방향으로 이동", p.muUpper > mu0)
        assertTrue("불확실성 감소", p.sigma < sigma0)
    }
}
