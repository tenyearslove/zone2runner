package com.zone2runner.app

import com.zone2runner.app.coaching.CoachContext
import com.zone2runner.app.coaching.CoachIntent
import com.zone2runner.app.coaching.CoachPrompt
import com.zone2runner.app.domain.ZoneJudgment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프롬프트 템플릿 외부화(2번) + 맥락 확장(3번) 검증.
 * default()는 Android Context 없이 내장 기본 템플릿을 쓰므로 JVM 단위 테스트 가능.
 */
class CoachPromptTest {

    private val p = CoachPrompt.default()

    @Test fun rendersDirectionAndTerrain() {
        val slow = p.render(CoachContext(ZoneJudgment.ABOVE, slopePct = 0.0, paceMinKm = 6.0, elapsedSec = 300))
        assertTrue("초과→낮추는 방향", slow.contains("낮춰"))
        assertTrue("평지 지형", slow.contains("평지"))
        val up = p.render(CoachContext(ZoneJudgment.BELOW, slopePct = 3.0, paceMinKm = 6.0, elapsedSec = 300))
        assertTrue("미달→올리는 방향", up.contains("올려") || up.contains("높여"))
        assertTrue("오르막 반영", up.contains("오르막"))
    }

    @Test fun preemptiveWordingDiffers() {
        val normal = p.render(CoachContext(ZoneJudgment.ABOVE, 0.0, 6.0, 300))
        val pre = p.render(CoachContext(ZoneJudgment.ABOVE, 0.0, 6.0, 300, preemptive = true))
        assertTrue("선제는 '예측' 문구 포함", pre.contains("예측"))
        assertFalse("일반은 예측 문구 없음", normal.contains("곧 상한을 넘을 것으로 예측"))
    }

    @Test fun contextLine_appearsOnlyWithValidNumbers() {
        // 수치 없음 → context 줄 생략(기존 동작)
        val bare = p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 300))
        assertFalse(bare.contains("현재 심박"))
        // 수치 있음 → 현재/목표/예측이 프롬프트에 들어감(3번)
        val rich = p.render(CoachContext(ZoneJudgment.ABOVE, 0.0, 6.0, 300,
            currentHr = 150, loBpm = 122, hiBpm = 138, predictedHr60 = 155))
        assertTrue(rich.contains("현재 심박 150bpm"))
        assertTrue(rich.contains("목표 122~138bpm"))
        assertTrue(rich.contains("60초 뒤 155bpm"))
    }

    @Test fun heatClause_onlyWhenHot_andNotADirection() {
        val hot = p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 300, tempC = 31.0))
        assertTrue("더울 때 기온/수분 맥락", hot.contains("31") && hot.contains("수분"))
        val mild = p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 300, tempC = 18.0))
        assertFalse("선선하면 기온 맥락 없음", mild.contains("덥"))
        // 더위 절('무리하지 말고')이 방향 판정을 뒤집지 않아야 함
        val up = p.render(CoachContext(ZoneJudgment.BELOW, 0.0, 6.0, 300, tempC = 31.0))
        assertTrue("더워도 미달이면 올리는 방향 유지", up.contains("올려") || up.contains("높여"))
    }

    @Test fun heatGuidance_excludedFromDirectionGuard() {
        // "더우니 무리하지 말고 수분" + 올리는 방향 → SPEED_UP 통과해야(더위 절 제외 판정)
        assertTrue(com.zone2runner.app.coaching.DirectionGuard.ok(
            CoachIntent.SPEED_UP, "페이스를 살짝 올려요. 더우니 무리하지 말고 수분 챙겨요."))
        // MAINTAIN인데 더위 절만 있으면 통과(증감 명령 없음)
        assertTrue(com.zone2runner.app.coaching.DirectionGuard.ok(
            CoachIntent.MAINTAIN, "이 리듬 그대로. 더우니 수분 챙겨요."))
    }

    @Test fun cadenceClauseInjected_whenOutOfRange() {
        val low = p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 300, spm = 150))
        assertTrue("저케이던스 폼 조언", low.contains("발걸음"))
        val ok = p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 300, spm = 175))
        assertFalse("정상 케이던스면 폼 조언 없음", ok.contains("발걸음"))
    }
}
