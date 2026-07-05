package com.zone2runner.voicepoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class UtteranceAnalyzerTest {
    private val sr = 16_000
    private val beat = 550

    /** (발화 여부, ms) 세그먼트로 합성. 발화=220Hz 사인, 비발화=0. */
    private fun sig(vararg segs: Pair<Boolean, Int>): ShortArray {
        val total = segs.sumOf { it.second } * sr / 1000
        val out = ShortArray(total); var idx = 0
        for ((v, ms) in segs) {
            val n = ms * sr / 1000
            for (i in 0 until n) out[idx++] = if (v) (8000 * sin(2 * PI * 220 * i / sr)).toInt().toShort() else 0
        }
        return out
    }

    @Test fun countsBreathBreaks() {
        // 말 2s + 들숨 0.4s + 말 2s + 들숨 0.4s + 말 2s → 호흡 2회, 발화 3덩이
        val m = UtteranceAnalyzer.analyze(sig(true to 2000, false to 400, true to 2000, false to 400, true to 2000), sr, beat)
        assertEquals("호흡 끊김 2회", 2, m.breaths)
        assertTrue("평균 발화 길이 ~2s", m.meanUtteranceSec in 1.6..2.6)
    }

    @Test fun longUtteranceWhenComfortable() {
        // 한 숨에 길게(6s) = 편함 → 호흡 0, 발화 길이 큼
        val m = UtteranceAnalyzer.analyze(sig(true to 6000), sr, beat)
        assertEquals(0, m.breaths)
        assertTrue(m.meanUtteranceSec > 4.0)
        assertTrue("숨당 토큰 많음", m.tokensPerBreath > 6.0)
    }

    @Test fun shortUtterancesWhenBreathless() {
        // 자주 끊어 숨쉼(0.8s 말 + 0.35s 들숨 반복) = 벅참 → 발화 길이 짧고 호흡 많음
        val m = UtteranceAnalyzer.analyze(
            sig(true to 800, false to 350, true to 800, false to 350, true to 800, false to 350, true to 800), sr, beat
        )
        assertTrue("호흡 여러 번", m.breaths >= 3)
        assertTrue("발화 길이 짧음", m.meanUtteranceSec < 1.2)
        val comf = UtteranceAnalyzer.analyze(sig(true to 6000), sr, beat)
        assertTrue("편함이 발화 길이 더 김", comf.meanUtteranceSec > m.meanUtteranceSec)
    }
}
