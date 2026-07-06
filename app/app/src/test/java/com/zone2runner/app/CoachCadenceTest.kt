package com.zone2runner.app

import com.zone2runner.app.pipeline.CoachCadence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 코칭 빈도(spec-021) → 케이던스 매핑 검증. */
class CoachCadenceTest {

    @Test fun `보통(level 2)은 기존 하드코딩 값과 동일`() {
        val c = CoachCadence.forLevel(2)
        assertEquals(20, c.minGapSec)
        assertEquals(60, c.overdueSec)
        assertEquals(40, c.idleReSec)
        assertEquals(CoachCadence.DEFAULT, c)
    }

    @Test fun `빈도를 올리면 간격이 단조 감소(더 자주)`() {
        for (lvl in 1..4) {
            val prev = CoachCadence.forLevel(lvl - 1)
            val cur = CoachCadence.forLevel(lvl)
            assertTrue("minGap 감소", cur.minGapSec < prev.minGapSec)
            assertTrue("overdue 감소", cur.overdueSec < prev.overdueSec)
            assertTrue("idle 감소", cur.idleReSec < prev.idleReSec)
        }
    }

    @Test fun `범위 밖 레벨은 clamp`() {
        assertEquals(CoachCadence.forLevel(0), CoachCadence.forLevel(-5))
        assertEquals(CoachCadence.forLevel(4), CoachCadence.forLevel(99))
    }
}
