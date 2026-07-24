package com.zone2runner.app

import com.zone2runner.app.coaching.CoachContext
import com.zone2runner.app.coaching.CoachEvidence
import com.zone2runner.app.domain.ZoneJudgment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 코칭 근거 데이터 요약(spec-027 확장) — 관측 스냅샷이 사람이 읽는 형태로 정리되는지. */
class CoachEvidenceTest {

    @Test fun directionCoaching_includesJudgmentBoundsAndFacts() {
        val e = CoachEvidence.of(CoachContext(ZoneJudgment.ABOVE, 3.4, 6.2, 754,
            spm = 168, currentHr = 145, loBpm = 117, hiBpm = 132, tempC = 31.0))
        assertTrue(e.contains("판정 초과"))
        assertTrue(e.contains("지속심박 145bpm"))
        assertTrue(e.contains("117~132bpm"))
        assertTrue(e.contains("경사 3.4% (오르막)"))
        assertTrue(e.contains("페이스 6'12\"/km"))
        assertTrue(e.contains("케이던스 168spm"))
        assertTrue(e.contains("기온 31도"))
        assertTrue(e.contains("경과 12:34"))
    }

    @Test fun specialTriggers_listed() {
        val joint = CoachEvidence.of(CoachContext(ZoneJudgment.BELOW, -5.0, 6.0, 400, jointProtect = true))
        assertTrue(joint.contains("트리거: 내리막 관절 보호"))
        val drift = CoachEvidence.of(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 1600,
            driftRising = true, latePacing = true))
        assertTrue(drift.contains("드리프트 상승 관측"))
        assertTrue(drift.contains("세션 후반"))
    }

    @Test fun unknownValues_omittedHonestly() {
        val e = CoachEvidence.of(CoachContext(ZoneJudgment.IN, 0.0, 6.0, 60))
        assertFalse("심박 미상이면 경계 미표기", e.contains("bpm"))
        assertFalse("케이던스 미상 생략", e.contains("케이던스"))
        assertFalse("기온 미상 생략", e.contains("기온"))
        assertFalse("트리거 없음 생략", e.contains("트리거"))
    }
}
