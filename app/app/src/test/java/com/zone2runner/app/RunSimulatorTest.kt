package com.zone2runner.app

import com.zone2runner.app.domain.Profile
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

    @Test fun hr_neverExceedsRunnerMaxHr() {
        // 여러 시드에서 참 HR이 최대심박에 포화하는지(클램프). 관측 노이즈(sd<=1.0)만 소폭 허용.
        for (seed in longArrayOf(1L, 42L, 99L, 12345L)) {
            val session = RunSimulator(seed).generate(durationMin = 30)
            val cap = session.runner.maxHr + 5
            val over = session.samples.count { it.hr > cap }
            assertEquals("seed=$seed: HR이 maxHr(${session.runner.maxHr})를 초과", 0, over)
        }
    }

    @Test fun profile_anchorsRunnerBody() {
        val p = Profile(age = 35, restingHr = 58, maxHr = 183)
        val session = RunSimulator(seed = 3L).generate(durationMin = 5, profile = p)
        assertEquals(35, session.runner.age)
        assertEquals(58, session.runner.resting)
        assertEquals(183, session.runner.maxHr)
        assertTrue("HR이 프로필 maxHr 근방 이하", session.samples.all { it.hr <= 183 + 5 })
    }

    @Test fun selfRegulation_hrDoesNotPinAtCeiling() {
        // 러너 자기조절(체감 기반)로 심박이 최대치에 눌러붙지 않아야 함(사용자 관찰 "중반부 200 고정" 수정).
        // 여러 시드에서 maxHr-3 이상에 머무는 시간 비율이 낮아야 한다(오르내리며 회복).
        for (seed in longArrayOf(1L, 42L, 99L, 2024L)) {
            val session = RunSimulator(seed).generate(durationMin = 30)
            val cap = session.runner.maxHr - 3
            val pinned = session.samples.count { it.hr >= cap }.toDouble() / session.samples.size
            assertTrue("seed=$seed: 심박이 천장(${cap}+)에 ${(pinned * 100).toInt()}%나 머묾(자기조절 실패)",
                pinned < 0.08)
        }
    }

    @Test fun sameSeed_isDeterministic() {
        val a = RunSimulator(seed = 7L).generate(5).samples
        val b = RunSimulator(seed = 7L).generate(5).samples
        assertEquals(a.size, b.size)
        assertEquals(a.first().hr, b.first().hr)
        assertEquals(a.last().paceMinKm, b.last().paceMinKm, 1e-9)
    }
}
