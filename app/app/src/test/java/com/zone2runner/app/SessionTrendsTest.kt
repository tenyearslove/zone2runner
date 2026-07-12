package com.zone2runner.app

import com.zone2runner.app.domain.RunReport
import com.zone2runner.app.domain.SessionTrends
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTrendsTest {

    private fun rep(avgHr: Int, submax: Double?, dist: Double = 3000.0, dur: Int = 1200, inSec: Int = 600, startedAt: Long = 0L) =
        RunReport(
            durationSec = dur, distanceM = dist, avgHr = avgHr, maxHr = avgHr + 20,
            belowSec = 0, inSec = inSec, aboveSec = dur - inSec, avgPaceMinKm = 6.0,
            coachingLines = emptyList(), track = emptyList(),
            uEstStartFrac = 0.70, uEstEndFrac = 0.70, restingHr = 55, maxHrProfile = 185,
            avgSpm = 176, submaxHr = submax, startedAtEpochMs = startedAt,
        )

    @Test fun condition_betterWhenEfAboveBaseline() {
        val priors = listOf(rep(150, null), rep(150, null), rep(150, null)) // ef≈1.0
        val current = rep(135, null)                                        // ef≈1.11 (>+3%)
        val c = SessionTrends.condition(priors + current, current)!!
        assertEquals(com.zone2runner.app.domain.Verdict.BETTER, c.verdict)
    }

    @Test fun condition_nullWhenTooFewPriors() {
        val current = rep(140, null)
        assertEquals(null, SessionTrends.condition(listOf(rep(150, null), current), current))
    }

    @Test fun period_rollingWindowAggregates() {
        val day = 86_400_000L
        val now = 30 * day
        val history = listOf(
            rep(150, null, startedAt = 1 * day),   // 창 밖(29일 전보다 오래)
            rep(150, null, startedAt = 26 * day),  // 최근 7일 안
            rep(150, null, startedAt = 28 * day),  // 최근 7일 안
        )
        val week = SessionTrends.period(history, now, 7)
        assertEquals(2, week.sessions)
        assertTrue(week.distanceKm > 5.0)
    }

    @Test fun trends_efIncreasing() {
        val history = listOf(rep(155, 148.0), rep(150, 146.0), rep(145, 143.0), rep(140, 140.0))
        val trends = SessionTrends.trends(history)
        val ef = trends.first { it.label == "효율(EF)" }
        assertEquals(4, ef.values.size)
        assertTrue("EF 우상향", ef.values.last() > ef.values.first())
        assertTrue(ef.higherBetter)
        val submax = trends.first { it.label == "서브맥시멀 심박" }
        assertTrue("서브맥시멀 하강", submax.values.last() < submax.values.first())
    }

    @Test fun records_currentSessionSetsPR() {
        val prev = listOf(rep(155, 148.0), rep(150, 146.0))
        val current = rep(140, 138.0) // 최고 효율 + 최저 서브맥시멀
        val history = prev + current
        val recs = SessionTrends.records(history, current)
        assertTrue(recs.first { it.label == "최고 효율(EF)" }.isNew)
        assertTrue(recs.first { it.label == "최저 서브맥시멀 심박" }.isNew)
        // newRecords는 갱신분만
        assertTrue(SessionTrends.newRecords(history, current).any { it.label == "최고 효율(EF)" })
    }

    @Test fun records_notNewWhenWorse() {
        val prev = listOf(rep(140, 138.0)) // 이미 좋은 기록
        val current = rep(155, 150.0)       // 더 나쁨
        val history = prev + current
        assertTrue(SessionTrends.newRecords(history, current).none { it.label == "최고 효율(EF)" })
    }
}
