package com.zone2runner.app

import com.zone2runner.app.data.SessionStore
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
    }
}
