package com.zone2runner.app

import com.zone2runner.app.coaching.CoachIntent
import com.zone2runner.app.coaching.DirectionGuard
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

    // ---- RuleCoach 전 문구는 자기 의도에서 반드시 통과 ----

    @Test fun ruleCoachLines_passTheirOwnIntent() {
        val speedUp = listOf(
            "내리막이에요. 조금 더 밀어서 심박을 Zone 2로 올려볼까요.",
            "여유가 있어요. 페이스를 살짝 올려 Zone 2로 들어가요.",
            "심박이 낮아요. 조금만 더 속도를 내볼게요.", // "내볼게요"의 '내'가 down으로 오탐되면 안 됨
        )
        val slowDown = listOf(
            "오르막이라 심박이 올랐어요. 보폭을 줄여 천천히 올라가요.", // "올라가요"가 up으로 오탐되면 안 됨
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
}
