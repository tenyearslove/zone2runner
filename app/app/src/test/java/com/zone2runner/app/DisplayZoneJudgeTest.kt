package com.zone2runner.app

import com.zone2runner.app.domain.DisplayZone
import com.zone2runner.app.domain.DisplayZoneJudge
import com.zone2runner.app.domain.DisplayZones
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 표시 존 판정(adr-023) — 5존 분할과 히스테리시스(경계 ±2bpm 유지, 3틱 연속 시 전환) 검증.
 * 경계 예시: lo=120, hi=140, max=185 → seg=(185-140)/3=15 → Z3 140~155, Z4 155~170, Z5 170~.
 */
class DisplayZoneJudgeTest {

    private val LO = 120; private val HI = 140; private val MAX = 185

    private fun raw(bpm: Int) = DisplayZones.rawZone(bpm, LO, HI, MAX)

    @Test
    fun rawZone_splitsFiveZones() {
        assertEquals(DisplayZone.Z1, raw(119))
        assertEquals(DisplayZone.Z2, raw(120))
        assertEquals(DisplayZone.Z2, raw(140))
        assertEquals(DisplayZone.Z3, raw(141))
        assertEquals(DisplayZone.Z3, raw(155))
        assertEquals(DisplayZone.Z4, raw(156))
        assertEquals(DisplayZone.Z4, raw(170))
        assertEquals(DisplayZone.Z5, raw(171))
    }

    @Test
    fun rawZone_degenerateMax_stillOrdersZones() {
        // max가 상한에 붙어도(seg 최솟값 1) Z3<Z4<Z5 순서 유지, 크래시 없음
        assertEquals(DisplayZone.Z5, DisplayZones.rawZone(145, 120, 140, 141))
        assertEquals(DisplayZone.Z3, DisplayZones.rawZone(141, 120, 140, 141))
    }

    @Test
    fun judge_noFlicker_withinMargin() {
        val j = DisplayZoneJudge(marginBpm = 2, holdTicks = 3)
        assertEquals(DisplayZone.Z2, j.judge(138, LO, HI, MAX))
        // 경계(140) ±2 안에서 141/139 교대 — 노이즈는 존을 못 바꾼다
        assertEquals(DisplayZone.Z2, j.judge(141, LO, HI, MAX))
        assertEquals(DisplayZone.Z2, j.judge(139, LO, HI, MAX))
        assertEquals(DisplayZone.Z2, j.judge(142, LO, HI, MAX))
        assertEquals(DisplayZone.Z2, j.judge(139, LO, HI, MAX))
    }

    @Test
    fun judge_switchesImmediately_beyondMargin() {
        val j = DisplayZoneJudge(marginBpm = 2, holdTicks = 3)
        assertEquals(DisplayZone.Z2, j.judge(135, LO, HI, MAX))
        assertEquals(DisplayZone.Z3, j.judge(143, LO, HI, MAX)) // 140+2 초과 → 즉시 Z3
        assertEquals(DisplayZone.Z2, j.judge(137, LO, HI, MAX)) // 140-2 미만 → 즉시 Z2 복귀
    }

    @Test
    fun judge_switchesAfterHoldTicks_nearBoundary() {
        val j = DisplayZoneJudge(marginBpm = 2, holdTicks = 3)
        assertEquals(DisplayZone.Z2, j.judge(138, LO, HI, MAX))
        // 경계 살짝 위(141~142)에 계속 머묾 — 3틱째에 전환(느린 진짜 이동)
        assertEquals(DisplayZone.Z2, j.judge(141, LO, HI, MAX))
        assertEquals(DisplayZone.Z2, j.judge(142, LO, HI, MAX))
        assertEquals(DisplayZone.Z3, j.judge(141, LO, HI, MAX))
    }

    @Test
    fun judge_multiZoneJump_isImmediate() {
        val j = DisplayZoneJudge()
        assertEquals(DisplayZone.Z2, j.judge(130, LO, HI, MAX))
        assertEquals(DisplayZone.Z5, j.judge(175, LO, HI, MAX)) // 스파이크급 상승은 즉시 반영
    }

    @Test
    fun withinFrac_clampsAndMapsZoneRange() {
        assertEquals(0.0, DisplayZones.withinFrac(90, DisplayZone.Z1, LO, HI, MAX), 1e-9)   // 바닥 고정
        assertEquals(0.5, DisplayZones.withinFrac(130, DisplayZone.Z2, LO, HI, MAX), 1e-9)  // Z2 중앙
        assertEquals(0.5, DisplayZones.withinFrac(163, DisplayZone.Z4, LO, HI, MAX), 0.05)  // Z4(155~170) 중앙 부근
        assertEquals(1.0, DisplayZones.withinFrac(200, DisplayZone.Z5, LO, HI, MAX), 1e-9)  // 천장 고정
    }
}
