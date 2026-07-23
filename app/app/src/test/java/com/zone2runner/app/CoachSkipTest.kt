package com.zone2runner.app

import com.zone2runner.app.coaching.Coach
import com.zone2runner.app.coaching.CoachContext
import com.zone2runner.app.domain.Profile
import com.zone2runner.app.domain.Sample
import com.zone2runner.app.pipeline.RunEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LLM 로딩 중 코칭 보류(spec-028): 코치가 빈 문장을 반환하면 코칭 라인이 생기지 않고,
 * 슬롯이 반환돼 준비 후 재시도된다. 준비되면 그때부터 정상 기록.
 */
class CoachSkipTest {

    private fun sample(t: Int, hr: Int) = Sample(t, hr, 6.0, 175, 0.0, Double.NaN, Double.NaN)

    @Test fun blankLine_isSkipped_notRecorded(): Unit = runBlocking {
        val silent = object : Coach {
            override val name = "loading"
            override suspend fun say(ctx: CoachContext): String = "" // 로딩 중 보류
        }
        val engine = RunEngine(Profile.default(35, 55), silent)
        var t = 0
        repeat(180) { engine.onSample(sample(t, 155)); t++ } // 초과 지속 — 코칭 시도 다수 발생
        assertEquals("보류 중엔 코칭 라인 0", 0, engine.report().coachingLines.size)
    }

    @Test fun resumesRecording_afterCoachReady(): Unit = runBlocking {
        var ready = false
        var attempts = 0
        val coach = object : Coach {
            override val name = "warmup-then-ready"
            override suspend fun say(ctx: CoachContext): String {
                attempts++
                return if (ready) "속도 줄이기" else ""
            }
        }
        val engine = RunEngine(Profile.default(35, 55), coach)
        var t = 0
        repeat(120) { engine.onSample(sample(t, 155)); t++ } // 로딩 구간 — 전부 보류
        ready = true
        repeat(120) { engine.onSample(sample(t, 155)); t++ } // 준비 후 — 기록 재개
        assertTrue("로딩 중에도 시도는 있었음", attempts > 1)
        assertTrue("준비 후 코칭 기록됨", engine.report().coachingLines.isNotEmpty())
        assertTrue(engine.report().coachingLines.all { it.contains("속도 줄이기") })
    }
}
