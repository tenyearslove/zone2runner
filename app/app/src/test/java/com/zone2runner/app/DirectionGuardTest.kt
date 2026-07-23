package com.zone2runner.app

import com.zone2runner.app.coaching.CoachIntent
import com.zone2runner.app.coaching.DirectionGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * adr-002 방향 잠금 가드. 케이스 출처:
 * - 기각 대상: 2026-07-03 실기기 필드 로그의 실제 Gemini Nano 출력(무방향/역방향).
 * - 통과 대상: RuleCoach 전체 문구(폴백 문구가 스스로 걸리면 안 됨) + 적합했던 LLM 출력.
 */
class DirectionGuardTest {

    // ---- 실기기 LLM 출력 (2026-07-03 시뮬 세션 로그) ----

    @Test fun rejects_directionlessCheer() {
        // "초과" 상황에서 나왔던 무방향 응원 → 기각되어야 함
        assertFalse(DirectionGuard.ok(CoachIntent.SLOW_DOWN, "힘내세요!"))
        assertFalse(DirectionGuard.ok(CoachIntent.SPEED_UP, "힘내세요!"))
    }

    @Test fun accepts_calmDownPhrases_forSlowDown() {
        assertTrue(DirectionGuard.ok(CoachIntent.SLOW_DOWN, "숨 좀 고르고, 편안하게 달려봐요."))
        assertTrue(DirectionGuard.ok(CoachIntent.SLOW_DOWN, "숨이 조금 차려도 괜찮아요, 천천히 가요."))
    }

    @Test fun accepts_maintainPhrases() {
        assertTrue(DirectionGuard.ok(CoachIntent.MAINTAIN, "꾸준히 좋은 페이스 유지해 가세요!"))
        assertTrue(DirectionGuard.ok(CoachIntent.MAINTAIN, "힘내세요!")) // 유지 상황의 응원은 무해
    }

    // ---- 역방향(모순) 지시 ----

    @Test fun rejects_contradictions() {
        assertFalse(DirectionGuard.ok(CoachIntent.SLOW_DOWN, "조금만 더 속도를 올려봐요!"))
        assertFalse(DirectionGuard.ok(CoachIntent.SLOW_DOWN, "빠르게 치고 나가세요."))
        assertFalse(DirectionGuard.ok(CoachIntent.SPEED_UP, "페이스를 낮춰 천천히 가요."))
        assertFalse(DirectionGuard.ok(CoachIntent.MAINTAIN, "속도를 올려볼까요."))
        assertFalse(DirectionGuard.ok(CoachIntent.MAINTAIN, "조금 늦춰주세요."))
    }

    // ---- 케이던스(폼) 절은 방향 판정에서 제외 ----

    @Test fun cadenceClause_isExcludedFromDirectionCheck() {
        // "발걸음 빈도를 낮추고"의 '낮추'가 SPEED_UP/MAINTAIN 모순으로 오판되면 안 됨
        assertTrue(DirectionGuard.ok(CoachIntent.MAINTAIN, "지금 리듬 그대로 가요. 발걸음 빈도는 살짝 낮추고 편하게."))
        assertTrue(DirectionGuard.ok(CoachIntent.SPEED_UP, "페이스를 살짝 올려볼까요. 보폭은 줄이고 발걸음은 자주."))
        // 폼 절을 제외해도 페이스 방향 모순은 여전히 잡혀야 함
        assertFalse(DirectionGuard.ok(CoachIntent.SLOW_DOWN, "속도를 올려요. 발걸음은 자주 디뎌요."))
    }

    // ---- 케이던스 밴드/규칙 팁 ----

    @Test fun cadenceBand_thresholds() {
        fun ctx(spm: Int) = com.zone2runner.app.coaching.CoachContext(
            com.zone2runner.app.domain.ZoneJudgment.IN, 0.0, 6.5, 300, spm = spm)
        assertEquals(com.zone2runner.app.coaching.CadenceBand.UNKNOWN, ctx(0).cadence)
        assertEquals(com.zone2runner.app.coaching.CadenceBand.LOW, ctx(155).cadence)   // <162 (180-10%)
        assertEquals(com.zone2runner.app.coaching.CadenceBand.OK, ctx(175).cadence)
        assertEquals(com.zone2runner.app.coaching.CadenceBand.HIGH, ctx(195).cadence)  // >190
    }

    @Test fun ruleCoach_wordLevelCue_noAppendedTips(): Unit = kotlinx.coroutines.runBlocking {
        // spec-028 FR3: 폴백은 단어 수준 큐 — 케이던스/더위 절을 조합하지 않는다(문장 조합 소멸).
        val coach = com.zone2runner.app.coaching.RuleCoach()
        val low = coach.say(com.zone2runner.app.coaching.CoachContext(
            com.zone2runner.app.domain.ZoneJudgment.ABOVE, 0.0, 6.5, 300, spm = 150))
        assertTrue("팁 없이 방향 큐만: $low", !low.contains("발걸음"))
        assertTrue(DirectionGuard.ok(CoachIntent.SLOW_DOWN, low))
        val hot = coach.say(com.zone2runner.app.coaching.CoachContext(
            com.zone2runner.app.domain.ZoneJudgment.IN, 0.0, 6.5, 300, tempC = 31.0))
        assertTrue("더위 절도 조합 안 함: $hot", !hot.contains("수분"))
    }

    // ---- 대표 코칭 문장(과거 실측 LLM 출력 스타일)은 자기 의도에서 반드시 통과 ----

    @Test fun ruleCoachLines_passTheirOwnIntent() {
        val speedUp = listOf(
            "내리막이에요. 조금 더 밀어서 심박을 Zone 2로 올려볼까요.",
            "여유가 있어요. 페이스를 살짝 올려 Zone 2로 들어가요.",
            "심박이 낮아요. 조금만 더 속도를 내볼게요.", // "내볼게요"의 '내'가 down으로 오탐되면 안 됨
        )
        val slowDown = listOf(
            "오르막이라 심박이 올랐어요. 천천히, 보폭을 줄여 올라가요.", // "올라가요" up 오탐 금지 + 방향어는 폼 절 밖
            "심박이 Zone 2를 넘었어요. 페이스를 조금 늦춰요.",
            "약간 빨라요. 호흡을 고르며 속도를 낮춰볼게요.", // "빨라요"가 up으로 오탐되면 안 됨
        )
        val maintain = listOf(
            "좋아요, Zone 2 유지 중이에요. 이 리듬 그대로.",
            "완벽해요. 지금 페이스를 계속 지켜주세요.",
        )
        speedUp.forEach { assertTrue("SPEED_UP 통과 실패: $it", DirectionGuard.ok(CoachIntent.SPEED_UP, it)) }
        slowDown.forEach { assertTrue("SLOW_DOWN 통과 실패: $it", DirectionGuard.ok(CoachIntent.SLOW_DOWN, it)) }
        maintain.forEach { assertTrue("MAINTAIN 통과 실패: $it", DirectionGuard.ok(CoachIntent.MAINTAIN, it)) }
    }

    // ---- 페르소나별 RuleCoach 문구도 전부 자기 의도에서 통과 (spec-024: 폴백 말투) ----

    @Test fun ruleCoach_allPersonas_allSituations_passDirectionGuard() = kotlinx.coroutines.runBlocking {
        fun ctx(j: com.zone2runner.app.domain.ZoneJudgment, slope: Double = 0.0, spm: Int = 0, temp: Double? = null) =
            com.zone2runner.app.coaching.CoachContext(j, slope, 6.5, 300, spm = spm, tempC = temp)
        val cases = listOf( // (판정, 경사) 조합으로 모든 문구 분기를 훑는다
            com.zone2runner.app.domain.ZoneJudgment.BELOW to 0.0,
            com.zone2runner.app.domain.ZoneJudgment.BELOW to -3.0,
            com.zone2runner.app.domain.ZoneJudgment.ABOVE to 0.0,
            com.zone2runner.app.domain.ZoneJudgment.ABOVE to 3.0,
            com.zone2runner.app.domain.ZoneJudgment.IN to 0.0,
        )
        for (persona in listOf("default", "spartan", "friend", "calm")) {
            val coach = com.zone2runner.app.coaching.RuleCoach(persona)
            repeat(2) { // counter 순환으로 리스트의 두 문구 모두 검사
                for ((j, slope) in cases) {
                    val line = coach.say(ctx(j, slope, spm = 150, temp = 31.0)) // 케이던스+더위 팁까지 얹어 검사
                    assertTrue("[$persona] ${j}/slope=$slope 방향 위반: $line",
                        DirectionGuard.ok(com.zone2runner.app.coaching.intentOf(j), line))
                }
            }
        }
    }

    @Test fun ruleCoach_unknownPersona_fallsBackToDefault() = kotlinx.coroutines.runBlocking {
        val d = com.zone2runner.app.coaching.RuleCoach("default")
        val u = com.zone2runner.app.coaching.RuleCoach("no_such")
        val ctx = com.zone2runner.app.coaching.CoachContext(
            com.zone2runner.app.domain.ZoneJudgment.IN, 0.0, 6.5, 300)
        assertEquals(d.say(ctx), u.say(ctx))
    }
}
