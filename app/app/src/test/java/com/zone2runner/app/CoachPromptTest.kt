package com.zone2runner.app

import com.zone2runner.app.coaching.CoachContext
import com.zone2runner.app.coaching.CoachIntent
import com.zone2runner.app.coaching.CoachPrompt
import com.zone2runner.app.coaching.DirectionGuard
import com.zone2runner.app.domain.ZoneJudgment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 간결 구조화 프롬프트(spec-028 FR1) 검증 — "말투/상황(사실 숫자)/임무/제한"만 담고,
 * 방향 임무의 어휘 강제와 특수 코칭 임무가 컨텍스트에 따라 선택되는지.
 * default()는 Android Context 없이 내장 기본 템플릿을 쓰므로 JVM 단위 테스트 가능.
 */
class CoachPromptTest {

    private val p = CoachPrompt.default()

    @Test fun rendersDirectionTask_andTerrainFact() {
        val slow = p.render(CoachContext(ZoneJudgment.ABOVE, slopePct = 0.0, paceMinKm = 6.0, elapsedSec = 300))
        assertTrue("초과→낮추는 임무", slow.contains("낮춰"))
        assertTrue("평지 사실", slow.contains("평지"))
        val up = p.render(CoachContext(ZoneJudgment.BELOW, slopePct = 3.0, paceMinKm = 6.0, elapsedSec = 300))
        assertTrue("미달→올리는 임무", up.contains("올려") || up.contains("높여"))
        assertTrue("오르막 사실", up.contains("오르막"))
    }

    @Test fun factsNumbers_appearOnlyWhenValid() {
        val bare = p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 300))
        assertFalse("수치 없으면 심박 사실 생략", bare.contains("심박"))
        val rich = p.render(CoachContext(ZoneJudgment.ABOVE, 0.0, 6.0, 300,
            currentHr = 150, loBpm = 122, hiBpm = 138))
        assertTrue(rich.contains("심박 150"))
        assertTrue(rich.contains("122~138"))
    }

    @Test fun promptIsConcise() {
        // 간결성(AC-1): 풍부한 컨텍스트에서도 구조화 프롬프트가 짧게 유지
        val rich = p.render(CoachContext(ZoneJudgment.ABOVE, 3.0, 6.0, 900,
            currentHr = 150, loBpm = 122, hiBpm = 138, spm = 150, tempC = 31.0), "friend")
        assertTrue("프롬프트 170자 이하: ${rich.length}자", rich.length <= 170)
    }

    @Test fun heatAndCadence_asFactsNotSentences() {
        val hot = p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 300, tempC = 31.0))
        assertTrue("더위는 사실로", hot.contains("기온 31도"))
        val mild = p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 300, tempC = 18.0))
        assertFalse("선선하면 기온 사실 없음", mild.contains("기온"))
        val low = p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 300, spm = 150))
        assertTrue("저케이던스 사실", low.contains("케이던스 150 낮음"))
    }

    @Test fun specialTasks_selectedByContext() {
        assertTrue(p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 600, milestoneMin = 10)).contains("10분"))
        assertTrue(p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 60, warmup = true)).contains("천천히"))
        assertTrue(p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 900, recovering = true)).contains("내려가"))
        assertTrue(p.render(CoachContext(ZoneJudgment.IN, 5.0, 6.0, 400, uphillWarn = true)).contains("오르막"))
        assertTrue(p.render(CoachContext(ZoneJudgment.BELOW, -5.0, 6.0, 400, jointProtect = true)).contains("무릎"))
        val drift = p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 900, driftRising = true))
        assertTrue(drift.contains("서서히 오르"))
        val late = p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 1600, driftRising = true, latePacing = true))
        assertTrue(late.contains("후반"))
    }

    @Test fun persona_isSingleWordParameter() {
        val ctx = CoachContext(ZoneJudgment.ABOVE, 0.0, 6.0, 300)
        assertTrue(p.render(ctx, "spartan").contains("말투: 짧고 단호한 명령형"))
        assertTrue(p.render(ctx, "friend").contains("말투: 친한 친구 반말"))
        assertTrue(p.render(ctx, "calm").contains("말투: 차분한 존댓말"))
        assertEquals("미지 키는 기본 말투", p.render(ctx), p.render(ctx, "no_such_persona"))
        // 방향 임무는 말투와 무관하게 항상 포함(방향 잠금 전제, spec-024 AC-3 계승)
        for (key in listOf("default", "spartan", "friend", "calm")) {
            assertTrue(p.render(ctx, key).contains("낮춰"))
        }
    }

    // ---- 폴백 큐(spec-028 FR3): 전량 해당 방향의 DirectionGuard 통과(AC-5) ----

    @Test fun fallbackCues_passDirectionGuard() = kotlinx.coroutines.runBlocking<Unit> {
        val c = com.zone2runner.app.coaching.RuleCoach()
        assertTrue(DirectionGuard.ok(CoachIntent.SPEED_UP,
            c.say(CoachContext(ZoneJudgment.BELOW, 0.0, 6.0, 300))))
        assertTrue(DirectionGuard.ok(CoachIntent.SLOW_DOWN,
            c.say(CoachContext(ZoneJudgment.ABOVE, 0.0, 6.0, 300))))
        assertTrue(DirectionGuard.ok(CoachIntent.MAINTAIN,
            c.say(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 300))))
        // 관절 보호 큐는 가속 명령이 없어야(spec-026/028)
        assertFalse(DirectionGuard.containsUpCommand(
            c.say(CoachContext(ZoneJudgment.BELOW, -5.0, 6.0, 400, jointProtect = true))))
    }
}
