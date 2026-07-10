package com.zone2runner.app

import com.zone2runner.app.analysis.AnalysisInput
import com.zone2runner.app.analysis.CadenceStabilityMetric
import com.zone2runner.app.analysis.DriftSlopeMetric
import com.zone2runner.app.analysis.GapMinettiMetric
import com.zone2runner.app.analysis.HrrMetric
import com.zone2runner.app.analysis.SignalBuffer
import com.zone2runner.app.analysis.SubmaxHrMetric
import com.zone2runner.app.analysis.Trend
import com.zone2runner.app.domain.Profile
import com.zone2runner.app.domain.Zone2Boundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisMetricsTest {

    private fun input(buf: SignalBuffer) =
        AnalysisInput(buf.tNow, Profile.default(age = 35, restingHr = 55), Zone2Boundary(0.70, 0.58), buf)

    // ---- DriftSlope ----
    @Test fun drift_steadyRisingHr_slopeUp() {
        val buf = SignalBuffer()
        // 200초 정속(페이스 6.0 고정), HR 0.1bpm/s 상승 = 6 bpm/분
        for (t in 0 until 200) buf.add(t, 120.0 + 0.1 * t, 6.0, 170, 0.0)
        val s = DriftSlopeMetric().onTick(input(buf))!!
        assertEquals(6.0, s.value, 0.3)            // bpm/분
        assertEquals(Trend.UP, s.trend)
        assertTrue(!s.gated)
    }

    @Test fun drift_nonSteadyPace_gated() {
        val buf = SignalBuffer()
        for (t in 0 until 200) buf.add(t, 130.0, if (t % 2 == 0) 4.0 else 9.0, 170, 0.0) // 페이스 요동
        val s = DriftSlopeMetric().onTick(input(buf))!!
        assertTrue(s.gated)
    }

    @Test fun drift_shortWindow_null() {
        val buf = SignalBuffer()
        for (t in 0 until 10) buf.add(t, 120.0, 6.0, 170, 0.0)
        assertNull(DriftSlopeMetric().onTick(input(buf)))
    }

    // ---- GAP (Minetti) ----
    @Test fun gap_flatEqualsActual() {
        val buf = SignalBuffer().apply { add(0, 130.0, 6.0, 170, 0.0) }
        val s = GapMinettiMetric().onTick(input(buf))!!
        assertEquals(6.0, s.value, 1e-6)   // 평지 = 실제
    }

    @Test fun gap_uphillFasterEquivalent() {
        val buf = SignalBuffer().apply { add(0, 130.0, 6.0, 170, 10.0) } // 10% 오르막
        val s = GapMinettiMetric().onTick(input(buf))!!
        assertTrue("오르막 GAP는 실제보다 빠른(작은) 페이스여야", s.value < 6.0)
    }

    @Test fun gap_downhillSlowerEquivalent() {
        val buf = SignalBuffer().apply { add(0, 130.0, 6.0, 170, -8.0) }
        val s = GapMinettiMetric().onTick(input(buf))!!
        assertTrue("내리막 GAP는 실제보다 느린(큰) 페이스여야", s.value > 6.0)
    }

    // ---- Cadence stability ----
    @Test fun cadence_stableLowSigma_flat() {
        val buf = SignalBuffer()
        for (t in 0 until 80) buf.add(t, 130.0, 6.0, 178 + (t % 2), 0.0) // ±0.5 spm
        val s = CadenceStabilityMetric().onTick(input(buf))!!
        assertTrue(s.value < 2.53)
        assertEquals(Trend.FLAT, s.trend)
    }

    @Test fun cadence_jittery_unstable() {
        val buf = SignalBuffer()
        for (t in 0 until 80) buf.add(t, 130.0, 6.0, if (t % 2 == 0) 170 else 186, 0.0) // ±8 spm
        val s = CadenceStabilityMetric().onTick(input(buf))!!
        assertTrue(s.value > 2.53)
        assertEquals(Trend.UP, s.trend)
    }

    // ---- Submaximal HR ----
    @Test fun submax_dominantBin_meanHr() {
        val buf = SignalBuffer()
        // 200초 동안 페이스 6.0(빈), HR 140 근방
        for (t in 0 until 200) buf.add(t, 140.0, 6.0, 170, 0.0)
        val s = SubmaxHrMetric().onSessionEnd(input(buf))!!
        assertEquals(140.0, s.value, 1.0)
    }

    @Test fun submax_tooShort_null() {
        val buf = SignalBuffer()
        for (t in 0 until 60) buf.add(t, 140.0, 6.0, 170, 0.0) // 120초 미만
        assertNull(SubmaxHrMetric().onSessionEnd(input(buf)))
    }

    // ---- HRR ----
    @Test fun hrr_effortThenRecovery_positiveDrop() {
        val buf = SignalBuffer()
        var t = 0
        // 60초 러닝(빠름, HR 상승 165), 이후 정지(느림, HR 회복 120)
        for (i in 0 until 60) { buf.add(t, 150.0 + i * 0.25, 5.5, 175, 0.0); t++ }
        for (i in 0 until 80) { buf.add(t, 165.0 - i * 0.6, 14.0, 60, 0.0); t++ } // 급감속 + HR 하강
        val s = HrrMetric().onSessionEnd(input(buf))!!
        assertTrue("회복폭 양수", s.value > 10.0)
    }

    @Test fun hrr_noSlowdown_null() {
        val buf = SignalBuffer()
        for (t in 0 until 200) buf.add(t, 150.0, 6.0, 175, 0.0) // 계속 같은 속도
        assertNull(HrrMetric().onSessionEnd(input(buf)))
    }
}
