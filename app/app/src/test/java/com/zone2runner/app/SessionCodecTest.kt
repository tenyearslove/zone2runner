package com.zone2runner.app

import com.zone2runner.app.data.SessionStore
import com.zone2runner.app.domain.LlmCallRecord
import com.zone2runner.app.domain.RunReport
import com.zone2runner.app.domain.SeriesPoint
import com.zone2runner.app.domain.TrackPoint
import com.zone2runner.app.domain.ZoneJudgment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 세션 JSON 직렬화 왕복(toJson → fromJson) 무손실 검증. */
class SessionCodecTest {

    @Test fun roundTrip_preservesFields() {
        val original = RunReport(
            durationSec = 1234,
            distanceM = 4321.5,
            avgHr = 142,
            maxHr = 168,
            belowSec = 100, inSec = 900, aboveSec = 234,
            avgPaceMinKm = 6.25,
            coachingLines = listOf("[00:30] 좋아요 유지", "[03:10] 조금 늦춰요"),
            track = listOf(
                TrackPoint(37.5, 127.0, ZoneJudgment.IN),
                TrackPoint(37.5001, 127.0001, ZoneJudgment.ABOVE),
                TrackPoint(37.5002, 127.0002, null),
            ),
            uEstStartFrac = 0.70, uEstEndFrac = 0.72,
            restingHr = 58, maxHrProfile = 183,
            series = listOf(
                SeriesPoint(0, 120, 6.5, 1),
                SeriesPoint(3, 138, 6.2, 2),
                SeriesPoint(6, 110, 7.0, 0),
                SeriesPoint(9, 130, 6.4, -1),
            ),
            id = "s1700000000000",
            startedAtEpochMs = 1700000000000L,
            usedModel = true, coachSource = "llm", sourceMode = "live",
            llmCalls = listOf( // 프롬프트 프로비넌스(spec-027)
                LlmCallRecord(30, "coach", "nano-rewrite", "llm(톤 재작성)",
                    "좋아요 유지", "좋습니다, 이 페이스 유지해요", 812L, 154321),
                LlmCallRecord(190, "coach", "rule", "rule(방향 기각: \"...\")",
                    "러닝 코치입니다. 초과 상태를 알리세요.", "조금 늦춰요", 1430L, -1),
                LlmCallRecord(1234, "story", "nano-summarize", "llm(사실 요약)",
                    "이번 세션 사실 텍스트…", "- 요약 불릿", 2200L, 160000),
            ),
        )

        val restored = SessionStore.fromJson(SessionStore.toJson(original))

        assertEquals(original.durationSec, restored.durationSec)
        assertEquals(original.distanceM, restored.distanceM, 1e-9)
        assertEquals(original.avgHr, restored.avgHr)
        assertEquals(original.inSec, restored.inSec)
        assertEquals(original.avgPaceMinKm, restored.avgPaceMinKm, 1e-9)
        assertEquals(original.coachingLines, restored.coachingLines)
        assertEquals(original.id, restored.id)
        assertEquals(original.startedAtEpochMs, restored.startedAtEpochMs)
        assertEquals(original.usedModel, restored.usedModel)
        assertEquals(original.coachSource, restored.coachSource)
        assertEquals(original.sourceMode, restored.sourceMode)
        // 경로/시계열 크기 + 판정 인덱스 보존
        assertEquals(original.track.size, restored.track.size)
        assertEquals(ZoneJudgment.ABOVE, restored.track[1].judgment)
        assertEquals(null, restored.track[2].judgment)
        assertEquals(original.series.size, restored.series.size)
        assertEquals(2, restored.series[1].judgmentIndex)
        assertEquals(-1, restored.series[3].judgmentIndex)
        // zone2Pct 파생값 재계산 일치
        assertEquals(original.zone2Pct, restored.zone2Pct)
        assertTrue(restored.zone2Pct in 0..100)
        // LLM 프로비넌스(spec-027) 무손실 왕복
        assertEquals(original.llmCalls, restored.llmCalls)
    }

    /** 구버전 세션 JSON(llmCalls 없음)이 정상 로드된다(spec-027 AC-5 하위호환). */
    @Test fun fromJson_withoutLlmCalls_backCompat() {
        val legacy = RunReport(
            durationSec = 60, distanceM = 200.0, avgHr = 120, maxHr = 130,
            belowSec = 10, inSec = 40, aboveSec = 10, avgPaceMinKm = 6.0,
            coachingLines = listOf("[00:30] 유지"), track = emptyList(),
            uEstStartFrac = 0.7, uEstEndFrac = 0.7, restingHr = 60, maxHrProfile = 180,
        )
        val json = SessionStore.toJson(legacy) // llmCalls 비면 키 자체를 안 씀 = 구버전과 동일 형태
        assertTrue(!json.has("llmCalls"))
        assertEquals(emptyList<LlmCallRecord>(), SessionStore.fromJson(json).llmCalls)
    }
}
