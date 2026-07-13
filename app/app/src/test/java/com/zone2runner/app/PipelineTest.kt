package com.zone2runner.app

import com.zone2runner.app.domain.Profile
import com.zone2runner.app.pipeline.FeatureExtractor
import com.zone2runner.app.pipeline.OutlierGuard
import com.zone2runner.app.pipeline.Personalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 순수 Kotlin 파이프라인(Android 비의존) 동작 검증. */
class PipelineTest {

    @Test fun outlierGuard_rejectsOutOfRange_keepsValid() {
        assertTrue(OutlierGuard.isValid(120))
        assertTrue(!OutlierGuard.isValid(25))
        assertTrue(!OutlierGuard.isValid(300))
        // 이상치는 직전 유효값으로 대체
        assertEquals(130, OutlierGuard.clean(300, 130))
        assertEquals(130, OutlierGuard.clean(20, 130))
        assertEquals(140, OutlierGuard.clean(140, 130))
        // 유효 이력 없으면 null
        assertNull(OutlierGuard.clean(300, null))
    }

    @Test fun featureExtractor_dHrPerSec_trendAndGuards() {
        val fx = FeatureExtractor()
        // 60초 공급(HR 0.1 bpm/s 상승, 페이스 일정)
        for (t in 0 until 60) fx.add(120.0 + t * 0.1, 6.0, 170, 0.0)
        // t=40: dHR = (hr[40]-hr[10])/30 = (124.0-121.0)/30 = 0.1 bpm/s
        assertEquals(0.1, fx.dHrPerSecAt(40)!!, 1e-6)
        // 버퍼 부족(t<30) → null
        assertNull(fx.dHrPerSecAt(10))
    }

    @Test fun personalization_movesTowardObservationAndStaysBounded() {
        val profile = Profile.default(35, 58) // maxHr≈183, hrr≈125
        val p = Personalization(profile)
        val before = p.muUpper
        // 공식 상한보다 높은 관측을 반복 -> muUpper 상승, 불확실성 감소
        val z = profile.restingHr + 0.80 * profile.hrr
        repeat(5) { p.update(z) }
        assertTrue("관측 방향(위)으로 이동해야 함", p.muUpper > before)
        assertTrue("σ 감소해야 함", p.sigma < 8.0)
        val b = p.boundary()
        assertTrue("uFrac 범위 가드", b.uFrac in 0.5..0.85)
        assertTrue("밴드 폭 유지", b.uFrac - b.lFrac > 0.05)
    }
}
