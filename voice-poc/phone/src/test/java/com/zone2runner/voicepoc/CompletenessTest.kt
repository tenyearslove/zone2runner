package com.zone2runner.voicepoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletenessTest {
    private val target = "저는 지금 편안하게 천천히 달리고 있습니다"

    @Test fun fullReadIsComplete() {
        assertEquals(1.0, Completeness.ratio(target, target), 1e-9)
        // 띄어쓰기/문장부호 차이는 무시
        assertEquals(1.0, Completeness.ratio(target, "저는 지금 편안하게 천천히 달리고 있습니다."), 1e-9)
    }

    @Test fun partialReadIsLower() {
        val partial = Completeness.ratio(target, "저는 지금")       // 앞 2어절만
        assertTrue("일부만 읽으면 완성도 낮음", partial < 0.4)
        assertTrue("0 초과", partial > 0.0)
    }

    @Test fun gaspYieldsNearZero() {
        // "헉헉" 같은 비단어는 목표와 거의 안 겹침
        assertTrue(Completeness.ratio(target, "헉 헉 저는") < 0.25)
        assertEquals(0.0, Completeness.ratio(target, ""), 1e-9)
    }

    @Test fun judgeMonotonicByCompleteness() {
        val full = AsrTalkJudge.judge(completeness = 1.0, pauseCount = 0)
        val half = AsrTalkJudge.judge(completeness = 0.5, pauseCount = 1)
        val barely = AsrTalkJudge.judge(completeness = 0.15, pauseCount = 3)
        assertTrue(full.difficulty < half.difficulty)
        assertTrue(half.difficulty < barely.difficulty)
        assertTrue("완독은 편한 단계", full.level.ordinal <= TalkLevel.COMFORTABLE.ordinal)
        assertTrue("거의 못 읽으면 벅참 이상", barely.level.ordinal >= TalkLevel.HARD.ordinal)
    }
}
