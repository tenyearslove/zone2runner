package com.zone2runner.app

import com.zone2runner.app.coaching.PersonalizationExplainer
import com.zone2runner.app.data.Profiles
import com.zone2runner.app.domain.PersonalizationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** spec-020 순수 로직: 개인화 현황 계산 + 설명 무결성 + 프로필 네임스페이스. */
class PersonalizationStatusTest {

    private fun status(prior: Double, learned: Double?, sessions: Int = 3, sigma: Double? = 6.0) =
        PersonalizationStatus(
            restingHr = 60, hrr = 130.0, priorUFrac = prior, learnedUFrac = learned,
            band = 0.10, sessions = sessions, talkObs = 5, predUpdates = 40,
            sigmaBpm = sigma, uFracHistory = listOf(prior, (prior + (learned ?: prior)) / 2, learned ?: prior),
        )

    @Test fun bpmAndShift_areCorrect() {
        // prior 0.70 → hi = 60 + 0.70*130 = 151. learned 0.66 → hi = 60 + 0.66*130 = 145.8→145
        val s = status(0.70, 0.66)
        assertEquals(151, s.priorHi)
        assertEquals(145, s.curHi)
        assertEquals(145 - 151, s.shiftHiBpm) // -6, 아래로 이동
        assertTrue(s.learned)
    }

    @Test fun noLearning_usesPrior_andStageLabel() {
        val s = status(0.70, null, sessions = 0)
        assertTrue(!s.learned)
        assertEquals(s.priorHi, s.curHi) // 학습 없으면 현재=초기
        assertEquals("시작 전 (공식 초기값)", s.stageLabel)
    }

    @Test fun stageLabel_reflectsConfidence() {
        assertEquals("학습 시작", status(0.70, 0.69, sessions = 1).stageLabel)
        assertEquals("학습 중", status(0.70, 0.68, sessions = 5, sigma = 8.0).stageLabel)
        assertEquals("안정적", status(0.70, 0.66, sessions = 10, sigma = 3.5).stageLabel)
    }

    @Test fun explainer_factsMatchDirection_noHallucination() {
        val down = PersonalizationExplainer.facts(status(0.70, 0.64))
        assertTrue("아래로 내린 사실 포함", down.contains("내렸") && down.contains("벅차"))
        val up = PersonalizationExplainer.facts(status(0.66, 0.72))
        assertTrue("위로 올린 사실 포함", up.contains("올렸") && up.contains("편하"))
        val none = PersonalizationExplainer.facts(status(0.70, null, sessions = 0))
        assertTrue("데이터 없음 안내", none.contains("아직 러닝 데이터가 없어"))
        // 팩트에 실제 수치가 그대로 들어감(LLM이 아니라 규칙이 확정 → 무결성)
        assertTrue(down.contains("${status(0.70, 0.64).curHi}"))
    }

    @Test fun profilePrefNamespace_defaultIsUnsuffixed_othersSuffixed() {
        assertEquals("learned_zone", Profiles.prefNameFor("learned_zone", "default")) // 무손실 하위호환
        assertEquals("learned_zone_p123", Profiles.prefNameFor("learned_zone", "p123"))
    }
}
