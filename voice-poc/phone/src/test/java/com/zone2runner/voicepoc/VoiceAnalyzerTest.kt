package com.zone2runner.voicepoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceAnalyzerTest {

    private val sr = 16_000

    /** (유성 여부, 지속 ms) 세그먼트로 합성 PCM 생성. 유성=220Hz 사인, 침묵=0. */
    private fun signal(vararg segs: Pair<Boolean, Int>): ShortArray {
        val total = segs.sumOf { it.second } * sr / 1000
        val out = ShortArray(total); var idx = 0
        for ((voiced, ms) in segs) {
            val n = ms * sr / 1000
            for (i in 0 until n) {
                out[idx++] = if (voiced) (8000 * sin(2 * PI * 220 * i / sr)).toInt().toShort() else 0
            }
        }
        return out
    }

    @Test fun detectsSpanAndPause() {
        // 0.5s 발화 + 0.3s 침묵(호흡) + 0.5s 발화 → 끊김 1회, 완독 ≈ 1.3s
        val m = VoiceAnalyzer.analyze(signal(true to 500, false to 300, true to 500), sr)
        assertEquals("완독 span ≈ 1300ms", 1300.0, m.speechSpanMs.toDouble(), 60.0)
        assertEquals("호흡 끊김 1회", 1, m.pauseCount)
        assertTrue("발화율 0.6~0.85", m.voicedRatio in 0.6..0.85)
    }

    @Test fun noPauseWhenContinuous() {
        val m = VoiceAnalyzer.analyze(signal(true to 1000), sr)
        assertEquals("연속 발화는 끊김 0", 0, m.pauseCount)
        assertTrue("발화율 높음", m.voicedRatio > 0.9)
    }

    @Test fun silenceYieldsNoSpeech() {
        val m = VoiceAnalyzer.analyze(signal(false to 800), sr)
        assertEquals(0, m.speechSpanMs)
        assertEquals(0.0, m.voicedRatio, 1e-9)
    }

    @Test fun judgeIsMonotonicVsBaseline() {
        val baseline = VoiceMetrics(totalMs = 1400, speechSpanMs = 1300, voicedMs = 1000, pauseCount = 1, voicedRatio = 1000.0 / 1300)
        val easy = VoiceMetrics(1450, 1350, 1010, 1, 1010.0 / 1350)   // 기준선과 거의 동일
        val hard = VoiceMetrics(2000, 1900, 1050, 4, 1050.0 / 1900)   // 느려지고 끊김↑ 발화율↓

        val vEasy = TalkJudge.judge(easy, baseline)
        val vHard = TalkJudge.judge(hard, baseline)
        assertTrue("힘들수록 곤란도↑", vHard.difficulty > vEasy.difficulty)
        assertTrue("힘들수록 단계↑", vHard.level.ordinal > vEasy.level.ordinal)
        assertTrue("편한 낭독은 낮은 단계", vEasy.level.ordinal <= TalkLevel.COMFORTABLE.ordinal)
    }

    @Test fun judgeWithoutBaselineStillReturns() {
        val v = TalkJudge.judge(VoiceMetrics(1400, 1300, 900, 3, 900.0 / 1300), null)
        assertTrue(v.difficulty in 0.0..1.0)
    }
}
