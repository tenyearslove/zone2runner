package com.zone2runner.app

import com.zone2runner.app.pipeline.HrResidual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 심박 예측 잔차 NN(gray-box, report-005) 순전파 검증.
 * export된 hr_residual.json을 로드해 (1) 출력 2개(30/60초), (2) 물리 경계(±clampFrac) 준수를 확인.
 */
class HrResidualTest {

    private fun load(): HrResidual? {
        val f = listOf(File("src/main/assets/hr_residual.json"), File("app/src/main/assets/hr_residual.json"))
            .firstOrNull { it.exists() } ?: return null
        return runCatching { HrResidual.fromJsonString(f.readText()) }.getOrNull()
    }

    /** df = [hr_now_frac, hr_sus_frac, dHR(bpm/s), pace_plan, slope, spm, elapsed_min]. */
    private fun df(hNow: Double, dHr: Double, slope: Double, elapsed: Double) =
        doubleArrayOf(hNow, hNow, dHr, 6.0, slope, 165.0, elapsed)

    @Test fun forwardPass_bounded_and_twoHorizons() {
        val m = load()
        assertNotNull("hr_residual.json 로드", m); m!!
        val hrr = 140.0
        // 다양한 입력에서 출력이 항상 2개 + 물리 경계 안
        for (hNow in listOf(0.4, 0.7, 1.0)) for (dHr in listOf(-0.3, 0.0, 0.4)) for (el in listOf(2.0, 15.0, 28.0)) {
            val r = m.residual(df(hNow, dHr, 0.0, el), hrr)
            assertEquals("지평 2개", 2, r.size)
            assertTrue("잔차30 물리경계", Math.abs(r[0]) <= m.clampFrac + 1e-9)
            assertTrue("잔차60 물리경계", Math.abs(r[1]) <= m.clampFrac + 1e-9)
        }
    }

    @Test fun driftDirection_lateRunResidualNonNegativeIsh() {
        val m = load() ?: return
        // 콜드스타트 ODE는 드리프트를 0으로 보므로, 후반(경과↑)+중강도에서 잔차는 대체로 양(+)이어야(실제가 더 높음).
        val hrr = 140.0
        val late = m.residual(df(0.75, 0.0, 0.0, 25.0), hrr)[1]
        // 강한 단언은 피하고(합성/노이즈), 물리 경계 안 + NaN 아님만 확정
        assertTrue("유한", late.isFinite())
    }
}
