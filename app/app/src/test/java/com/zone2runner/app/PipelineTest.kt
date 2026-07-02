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

    @Test fun featureExtractor_warmupThenExtractsSevenFeatures() {
        val fx = FeatureExtractor()
        val profile = Profile.default(35, 58)
        // 200초 공급(HR 서서히 상승, 페이스 일정)
        for (t in 0 until 200) {
            val hr = 120.0 + t * 0.1
            fx.add(hr, 6.0, 170, 0.0)
        }
        assertTrue("warmup 완료여야 함", fx.warmupDone())
        // t=150은 (150-120)%5==0 이므로 특징 반환
        val feat = fx.extractAt(150, profile, uEst = 0.70, lEst = 0.60)
        assertNotNull("stride 지점에서 특징이 나와야 함", feat)
        assertEquals(7, feat!!.size)
        // hr_norm_u(=hrFrac-uEst) < hr_norm_l(=hrFrac-lEst) (uEst>lEst)
        assertTrue(feat[0] < feat[1])
        // stride 아닌 지점은 null
        assertNull(fx.extractAt(151, profile, 0.70, 0.60))
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
