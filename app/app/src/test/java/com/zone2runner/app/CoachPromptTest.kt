package com.zone2runner.app

import com.zone2runner.app.coaching.CoachContext
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

    @Test fun cadenceClauseInjected_whenOutOfRange() {
        val low = p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 300, spm = 150))
        assertTrue("저케이던스 폼 조언", low.contains("발걸음"))
        val ok = p.render(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 300, spm = 175))
        assertFalse("정상 케이던스면 폼 조언 없음", ok.contains("발걸음"))
    }
}
