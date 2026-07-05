package com.zone2runner.voicepoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class UtteranceAnalyzerTest {
    private val sr = 16_000
    private val win = 20_000

    private fun sig(vararg segs: Pair<Boolean, Int>): ShortArray {
        val total = segs.sumOf { it.second } * sr / 1000
        val out = ShortArray(total); var idx = 0
        for ((v, ms) in segs) {
            val n = ms * sr / 1000
            for (i in 0 until n) out[idx++] = if (v) (8000 * sin(2 * PI * 220 * i / sr)).toInt().toShort() else 0
        }
        return out
    }

    /** 편함: 숫자를 빠르게 이어 셈(8덩이, 짧은 어절 간격, 긴 숨 없음). */
    private fun comfortable() = sig(
        true to 400, false to 150, true to 400, false to 150, true to 400, false to 150, true to 400, false to 150,
        true to 400, false to 150, true to 400, false to 150, true to 400, false to 150, true to 400
    )

    /** 벅참: 몇 개 세고 길게 들이쉼(4덩이, 긴 숨 3회). */
    private fun breathless() = sig(
        true to 400, false to 800, true to 400, false to 800, true to 400, false to 800, true to 400
    )

    @Test fun segmentsCountApproxNumbersSaid() {
        assertEquals("빠른 이어세기 8덩이", 8, UtteranceAnalyzer.analyze(comfortable(), sr, win).voicedSegments)
        assertEquals("끊긴 세기 4덩이", 4, UtteranceAnalyzer.analyze(breathless(), sr, win).voicedSegments)
    }

    @Test fun speakingRatioHigherWhenComfortable() {
        val c = UtteranceAnalyzer.analyze(comfortable(), sr, win)
        val b = UtteranceAnalyzer.analyze(breathless(), sr, win)
        assertTrue("편함이 말한 비율 높음", c.speakingRatio > b.speakingRatio)
        assertTrue("편함이 더 많이 셈", c.voicedSegments > b.voicedSegments)
    }

    @Test fun longBreathsCountedOnlyWhenBreathless() {
        assertEquals("짧은 어절 간격은 들숨 아님", 0, UtteranceAnalyzer.analyze(comfortable(), sr, win).breaths)
        assertTrue("벅참은 긴 들숨 여러 번", UtteranceAnalyzer.analyze(breathless(), sr, win).breaths >= 3)
    }
}
