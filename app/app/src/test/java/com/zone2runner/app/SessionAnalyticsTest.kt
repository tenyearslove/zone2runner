package com.zone2runner.app

import com.zone2runner.app.analysis.SessionAnalytics
import com.zone2runner.app.domain.RunReport
import com.zone2runner.app.domain.SeriesPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAnalyticsTest {

    private fun rep(series: List<SeriesPoint>) = RunReport(
        durationSec = series.lastOrNull()?.tSec ?: 0, distanceM = 2000.0, avgHr = 140, maxHr = 160,
        belowSec = 0, inSec = 400, aboveSec = 0, avgPaceMinKm = 6.0,
        coachingLines = emptyList(), track = emptyList(),
        uEstStartFrac = 0.70, uEstEndFrac = 0.70, restingHr = 55, maxHrProfile = 185, series = series,
    )

    @Test fun splits_perKm_fromSeries() {
        // 600초 정속 페이스 6.0(≈2.78 m/s → 약 1.67km) → km1 완주 + km2 조각
        val s = (0..600 step 3).map { SeriesPoint(it, 140, 6.0, 1, 0.0) }
        val splits = SessionAnalytics.splits(rep(s))
        assertTrue("최소 1개 구간", splits.isNotEmpty())
        assertEquals(1, splits.first().km)
        assertEquals(140, splits.first().avgHr)
        assertEquals(6.0, splits.first().avgPaceMinKm, 0.1)
        // 평지라 GAP≈실제 페이스
        assertEquals(6.0, splits.first().avgGapMinKm, 0.1)
    }

    @Test fun gradeBreakdown_uphillFlat() {
        val s = ArrayList<SeriesPoint>()
        var t = 0
        for (i in 0 until 100) { s += SeriesPoint(t, 140, 6.0, 1, 0.0); t += 3 }   // 평지 300초
        for (i in 0 until 100) { s += SeriesPoint(t, 150, 6.5, 2, 8.0); t += 3 }   // 오르막 300초
        val bands = SessionAnalytics.gradeBreakdown(rep(s))
        val up = bands.first { it.label == "오르막" }
        val flat = bands.first { it.label == "평지" }
        assertEquals(150, up.avgHr)
        assertEquals(140, flat.avgHr)
        assertTrue("오르막 GAP는 실제보다 빠른 등가", up.avgGapMinKm < 6.5)
    }

    @Test fun warmup_abruptDetected() {
        val s = ArrayList<SeriesPoint>()
        var t = 0
        // 60초 만에 110→145 급상승, 이후 400초 유지
        for (i in 0 until 20) { s += SeriesPoint(t, 110 + i * 2, 6.0, 1, 0.0); t += 3 } // 0~57s 110→148
        for (i in 0 until 140) { s += SeriesPoint(t, 145, 6.0, 1, 0.0); t += 3 }        // 유지
        val w = SessionAnalytics.warmup(rep(s))
        assertNotNull(w)
        assertTrue("급상승 감지", w!!.abrupt)
        assertTrue(w.reachSec < 90)
    }

    @Test fun warmup_noRise_returnsNull() {
        // 심박이 오르지 않고 오히려 내려가는 세션(시뮬이 존 안에서 시작) — 워밍업 카드는 생략돼야 한다
        // (버그: 136→128인데 "0초에 걸쳐 완만히 올랐어요"가 나오던 문제)
        val s = ArrayList<SeriesPoint>()
        var t = 0
        for (i in 0 until 160) { s += SeriesPoint(t, 136 - (i / 40), 6.0, 1, 0.0); t += 3 } // 136에서 ~133으로 완만 하강
        assertNull("상승 없으면 워밍업 null", SessionAnalytics.warmup(rep(s)))
    }

    @Test fun downhillBehavior_slowerOnDownhill_positivePct() {
        // spec-026: 내리막(경사 −6%)에서 평지보다 느리게 뛰면 slowerPct 양수(관절 보호 습관 관측)
        val s = ArrayList<SeriesPoint>()
        var t = 0
        for (i in 0 until 60) { s += SeriesPoint(t, 140, 6.0, 1, 0.0); t += 1 }   // 평지 페이스 6.0
        for (i in 0 until 60) { s += SeriesPoint(t, 135, 7.0, 1, -6.0); t += 1 }  // 내리막 페이스 7.0(더 느림)
        val b = SessionAnalytics.downhillBehavior(rep(s))
        assertNotNull(b)
        assertTrue("내리막이 더 느림(양수): ${b!!.slowerPct}", b.slowerPct > 0)
    }

    @Test fun exitCauses_uphillDominant() {
        val s = ArrayList<SeriesPoint>()
        var t = 0
        for (i in 0 until 100) { s += SeriesPoint(t, 140, 6.0, 1, 0.0); t += 3 }  // IN 평지
        for (i in 0 until 100) { s += SeriesPoint(t, 152, 6.0, 2, 8.0); t += 3 }  // 초과 오르막
        val ec = SessionAnalytics.exitCauses(rep(s))!!
        assertTrue("초과 시간 누적", ec.aboveSec > 200)
        assertEquals(100, ec.uphillPct)
        assertTrue(ec.note.contains("오르막"))
    }

    @Test fun exitCauses_noAbove_null() {
        val s = (0..200 step 3).map { SeriesPoint(it, 140, 6.0, 1, 0.0) } // 전부 IN
        assertNull(SessionAnalytics.exitCauses(rep(s)))
    }

    @Test fun shortSession_emptyAnalytics() {
        val s = (0..15 step 3).map { SeriesPoint(it, 130, 6.0, 1, 0.0) }
        assertTrue(SessionAnalytics.splits(rep(s)).isEmpty())
        assertTrue(SessionAnalytics.gradeBreakdown(rep(s)).isEmpty())
    }
}
