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

    @Test fun milestone_returnsEncouragementWithMinutes() = runBlocking {
        val c = RuleCoach("default")
        val line = c.say(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 600, milestoneMin = 10))
        assertTrue("마일스톤 분수 포함: $line", line.contains("10분"))
    }

    @Test fun warmup_returnsWarmupCue() = runBlocking {
        val line = RuleCoach("default").say(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 60, warmup = true))
        assertTrue("워밍업 문구: $line", line.contains("천천히") || line.contains("데워"))
    }

    @Test fun jointProtect_downhill_returnsJointCue_notSpeedUp() = runBlocking {
        // spec-026: 관절 위험군 내리막에서 BELOW여도 "속도 올려" 대신 관절 보호 큐(보폭/무릎)
        val line = RuleCoach("default").say(CoachContext(ZoneJudgment.BELOW, -5.0, 6.0, 400, jointProtect = true))
        assertTrue("관절 큐(보폭/무릎): $line", line.contains("보폭") || line.contains("무릎"))
        assertFalse("속도 올리라는 말 없어야: $line", line.contains("올려") || line.contains("속도를 내"))
    }

    @Test fun latePacing_usesLatePhrase() = runBlocking {
        val line = RuleCoach("default").say(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 1600, driftRising = true, latePacing = true))
        assertTrue("후반 문구: $line", line.contains("후반"))
    }

    @Test fun recovering_returnsRecoveryLine() = runBlocking {
        val line = RuleCoach("default").say(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 900, recovering = true))
        assertTrue("회복 문구: $line", line.contains("회복") || line.contains("내려가"))
    }

    @Test fun uphillWarn_returnsPreventiveCue() = runBlocking {
        val line = RuleCoach("default").say(CoachContext(ZoneJudgment.IN, 5.0, 6.0, 400, uphillWarn = true))
        assertTrue("오르막 예방 문구: $line", line.contains("오르막"))
    }

    @Test fun driftFlag_ignoredWhenNotIn() = runBlocking {
        // 판정이 ABOVE면 드리프트 플래그와 무관하게 초과 방향 코칭(드리프트 경고는 IN 전용)
        val c = RuleCoach("default")
        val line = c.say(CoachContext(ZoneJudgment.ABOVE, 0.0, 6.0, 300, driftRising = true))
        assertFalse(line.contains("서서히 오르"))
    }
}
