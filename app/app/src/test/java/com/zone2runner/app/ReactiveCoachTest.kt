package com.zone2runner.app

import com.zone2runner.app.coaching.CoachContext
import com.zone2runner.app.coaching.RuleCoach
import com.zone2runner.app.domain.ZoneJudgment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactiveCoachTest {

    @Test fun driftRising_whileIn_returnsDriftWarning() = runBlocking {
        val c = RuleCoach("default")
        val ctx = CoachContext(
            ZoneJudgment.IN, 0.0, 6.0, 300,
            currentHr = 135, loBpm = 120, hiBpm = 140, driftRising = true,
        )
        val line = c.say(ctx)
        assertTrue("드리프트 경고 문구여야: $line", line.contains("오르"))
    }

    @Test fun in_noDrift_returnsKeep_notDriftWarning() = runBlocking {
        val c = RuleCoach("default")
        val line = c.say(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 300))
        assertFalse("드리프트 아닐 땐 유지 문구: $line", line.contains("서서히 오르"))
    }

    @Test fun driftFlag_ignoredWhenNotIn() = runBlocking {
        // 판정이 ABOVE면 드리프트 플래그와 무관하게 초과 방향 코칭(드리프트 경고는 IN 전용)
        val c = RuleCoach("default")
        val line = c.say(CoachContext(ZoneJudgment.ABOVE, 0.0, 6.0, 300, driftRising = true))
        assertFalse(line.contains("서서히 오르"))
    }
}
