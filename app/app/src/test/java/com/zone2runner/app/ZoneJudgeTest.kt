package com.zone2runner.app

import com.zone2runner.app.domain.ZoneJudgment
import com.zone2runner.app.pipeline.ZoneJudge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 규칙 Zone 판정(adr-013 FR1) 검증 — 히스테리시스 동작 + "모순 불가" 속성(AC4).
 * 경계는 판정과 밴드가 공유하므로, 지속 심박이 경계를 margin 이상 벗어났는데 반대 판정이
 * 나오는 일은 어떤 입력 순서에서도 불가능해야 한다.
 */
class ZoneJudgeTest {

    @Test fun basicJudgment_matchesBand() {
        val j = ZoneJudge(marginBpm = 2.0)
        assertEquals(ZoneJudgment.BELOW, j.judge(110.0, 124.0, 138.0))
        assertEquals(ZoneJudgment.IN, j.judge(130.0, 124.0, 138.0))
        assertEquals(ZoneJudgment.ABOVE, j.judge(150.0, 124.0, 138.0))
    }

    @Test fun hysteresis_suppressesChattering() {
        val j = ZoneJudge(marginBpm = 2.0)
        j.judge(130.0, 124.0, 138.0) // IN
        // 상한 살짝 위(138~140)에선 IN 유지(전환은 hi+2 초과부터)
        assertEquals(ZoneJudgment.IN, j.judge(139.0, 124.0, 138.0))
        assertEquals(ZoneJudgment.IN, j.judge(140.0, 124.0, 138.0))
        assertEquals(ZoneJudgment.ABOVE, j.judge(141.0, 124.0, 138.0))
        // ABOVE에선 hi-2 아래로 내려와야 IN 복귀(밴드 안쪽으로 확실히)
        assertEquals(ZoneJudgment.ABOVE, j.judge(137.0, 124.0, 138.0))
        assertEquals(ZoneJudgment.IN, j.judge(135.0, 124.0, 138.0))
        // 하한 쪽 대칭
        assertEquals(ZoneJudgment.IN, j.judge(123.0, 124.0, 138.0))
        assertEquals(ZoneJudgment.BELOW, j.judge(121.0, 124.0, 138.0))
        assertEquals(ZoneJudgment.BELOW, j.judge(125.0, 124.0, 138.0))
        assertEquals(ZoneJudgment.IN, j.judge(127.0, 124.0, 138.0))
    }

    @Test fun property_noContradiction_underRandomSequences() {
        // 실기기 결함("상한 42bpm 초과인데 미달")의 재발 방지 속성 검사
        val rnd = Random(42)
        val j = ZoneJudge(marginBpm = 2.0)
        val lo = 124.0; val hi = 138.0
        repeat(20_000) {
            val hr = 80.0 + rnd.nextDouble() * 120.0 // 80~200
            val out = j.judge(hr, lo, hi)
            if (hr > hi + 2.0) assertEquals("심박 ${hr}에서 초과 아님", ZoneJudgment.ABOVE, out)
            if (hr < lo - 2.0) assertEquals("심박 ${hr}에서 미달 아님", ZoneJudgment.BELOW, out)
            if (hr > hi + 2.0) assertTrue(out != ZoneJudgment.BELOW)
            if (hr < lo - 2.0) assertTrue(out != ZoneJudgment.ABOVE)
        }
    }

    @Test fun boundaryMoves_withPersonalization() {
        // 개인화가 경계를 옮기면 같은 심박도 판정이 따라 바뀜(진실은 하나 — 경계)
        val j = ZoneJudge(marginBpm = 2.0)
        assertEquals(ZoneJudgment.ABOVE, j.judge(145.0, 124.0, 138.0))
        val j2 = ZoneJudge(marginBpm = 2.0)
        assertEquals(ZoneJudgment.IN, j2.judge(145.0, 134.0, 148.0))
    }
}
