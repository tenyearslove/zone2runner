package com.zone2runner.app

import com.zone2runner.app.coaching.CoachContext
import com.zone2runner.app.coaching.NumberGuard
import com.zone2runner.app.domain.ZoneJudgment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 숫자 무결성 가드(spec-028 FR2, AC-2) — 출력의 모든 숫자 ⊆ 입력 사실 숫자.
 * "없는 숫자 금지" 제1원칙의 기계 검증. 언어 무관.
 */
class NumberGuardTest {

    private val ctx = CoachContext(ZoneJudgment.ABOVE, 0.0, 6.0, 900,
        currentHr = 150, loBpm = 122, hiBpm = 138, spm = 170, tempC = 31.0)

    @Test fun allowsNumbersFromFacts() {
        val allowed = NumberGuard.allowedOf(ctx)
        assertTrue(NumberGuard.ok(allowed, "심박 150이 138을 넘었어요. 페이스를 낮춰요."))
        assertTrue(NumberGuard.ok(allowed, "Zone 2로 돌아가요. 기온 31도니 무리하지 말고."))
        assertTrue(NumberGuard.ok(allowed, "숫자 없는 문장도 통과"))
    }

    @Test fun rejectsFabricatedNumbers() {
        val allowed = NumberGuard.allowedOf(ctx)
        assertFalse("입력에 없는 심박", NumberGuard.ok(allowed, "심박을 145로 맞춰요."))
        assertFalse("지어낸 시간", NumberGuard.ok(allowed, "3분만 더 버텨요."))
        assertFalse("지어낸 페이스", NumberGuard.ok(allowed, "5:30 페이스로 가요."))
    }

    @Test fun milestoneAndElapsedMinutes_allowed() {
        val m = CoachContext(ZoneJudgment.IN, 0.0, 6.0, 630, milestoneMin = 10)
        val allowed = NumberGuard.allowedOf(m)
        assertTrue("마일스톤 분", NumberGuard.ok(allowed, "Zone 2 10분 달성!"))
        assertTrue("경과 분(630초=10분)", NumberGuard.ok(allowed, "10분째 좋은 리듬이에요."))
    }

    @Test fun paceIntAllowed_zone2ConstantAllowed() {
        val allowed = NumberGuard.allowedOf(ctx) // paceMinKm=6.0
        assertTrue(NumberGuard.ok(allowed, "6분대 페이스 유지, Zone 2 그대로."))
    }
}
