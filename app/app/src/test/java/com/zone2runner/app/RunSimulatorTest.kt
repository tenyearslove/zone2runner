package com.zone2runner.app

import com.zone2runner.app.sim.RunSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 시뮬레이터가 물리적으로 그럴듯한 1Hz 샘플을 생성하는지 검증. */
class RunSimulatorTest {

    @Test fun generate_producesPlausibleSamples() {
        val session = RunSimulator(seed = 42L).generate(durationMin = 10)
        assertEquals(600, session.samples.size)
        val s = session.samples
        // tSec 연속
        assertEquals(0, s.first().tSec)
        assertEquals(599, s.last().tSec)
        // HR/페이스/케이던스 범위(대부분)
        val hrOk = s.count { it.hr in 60..210 }
        assertTrue("HR 대부분 생리 범위", hrOk > s.size * 0.9)
        assertTrue("페이스 범위", s.all { it.paceMinKm in 3.0..12.0 })
        assertTrue("케이던스 범위", s.all { it.spm in 140..210 })
        // GPS가 움직임(시작과 끝이 달라야 함)
        val moved = Math.abs(s.first().lat - s.last().lat) + Math.abs(s.first().lon - s.last().lon)
        assertTrue("경로가 이동해야 함", moved > 1e-4)
    }

    @Test fun sameSeed_isDeterministic() {
        val a = RunSimulator(seed = 7L).generate(5).samples
        val b = RunSimulator(seed = 7L).generate(5).samples
        assertEquals(a.size, b.size)
        assertEquals(a.first().hr, b.first().hr)
        assertEquals(a.last().paceMinKm, b.last().paceMinKm, 1e-9)
    }
}
