package com.zone2runner.app

import com.zone2runner.app.sensor.SlopeEstimator
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 거리창 경사 추정이 GPS 고도 노이즈에 강건한지 — 순간 미분 대비 개선 검증(사용자 지적 2026-07-06).
 * 노이즈는 개활지 수직정확도 ~±6m 가정(도심/음영은 더 나쁨 → 앱은 범주로만 소비).
 */
class SlopeEstimatorTest {

    @Test fun flatGroundWithGpsAltitudeNoise_staysNearZero() {
        val rng = Random(1)
        val est = SlopeEstimator()
        var g = 0.0
        // 평지(고도 100m 고정)인데 GPS 고도에 노이즈. 매 초 2.8m 전진.
        repeat(400) { g = est.update(horizM = 2.8, altM = 100.0 + rng.nextGaussian() * 6.0) }
        // 순간 미분이면 수백 %로 튀지만, 거리창 회귀는 0 근처로 눌러야 함
        assertTrue("flat+noise grade near 0, got %.2f".format(g), Math.abs(g) < 3.0)
    }

    @Test fun realRamp_recoversTrueGrade_despiteNoise() {
        val rng = Random(2)
        val est = SlopeEstimator()
        var g = 0.0; var alt = 100.0
        // 진짜 5퍼센트 오르막: 2.8m 전진마다 0.14m 상승 + 노이즈.
        repeat(500) { alt += 2.8 * 0.05; g = est.update(2.8, alt + rng.nextGaussian() * 6.0) }
        assertTrue("5pct uphill recovered, got %.2f".format(g), g in 2.5..7.5)
    }

    @Test fun downhill_isNegative() {
        val rng = Random(3)
        val est = SlopeEstimator()
        var g = 0.0; var alt = 100.0
        repeat(500) { alt -= 2.8 * 0.04; g = est.update(2.8, alt + rng.nextGaussian() * 6.0) }
        assertTrue("downhill negative, got %.2f".format(g), g < -1.5)
    }
}
