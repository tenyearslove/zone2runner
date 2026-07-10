package com.zone2runner.app

import com.zone2runner.app.domain.RunReport
import com.zone2runner.app.domain.SessionCompare
import com.zone2runner.app.domain.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCompareTest {

    private fun report(dur: Int, dist: Double, avgHr: Int, inSec: Int, submax: Double?, spm: Int) =
        RunReport(
            durationSec = dur, distanceM = dist, avgHr = avgHr, maxHr = avgHr + 20,
            belowSec = 0, inSec = inSec, aboveSec = dur - inSec, avgPaceMinKm = 6.0,
            coachingLines = emptyList(), track = emptyList(),
            uEstStartFrac = 0.70, uEstEndFrac = 0.70, restingHr = 55, maxHrProfile = 185,
            submaxHr = submax, avgSpm = spm,
        )

    private fun verdictOf(lines: List<com.zone2runner.app.domain.CompareLine>, label: String) =
        lines.first { it.label == label }.verdict

    @Test fun fitterSession_showsImprovements() {
        // 같은 거리/시간(같은 속도)인데 심박 낮음 → 효율↑, Zone2 비율↑, 서브맥시멀↓
        val prev = report(1200, 3000.0, 150, inSec = 600, submax = 145.0, spm = 170)
        val cur = report(1200, 3000.0, 140, inSec = 800, submax = 138.0, spm = 172)
        val lines = SessionCompare.compare(cur, prev)

        assertEquals(Verdict.BETTER, verdictOf(lines, "Zone 2 비율"))       // 50→66%
        assertEquals(Verdict.BETTER, verdictOf(lines, "효율(심박당 거리)")) // 1.00→1.07
        assertEquals(Verdict.BETTER, verdictOf(lines, "서브맥시멀 심박"))   // 145→138
        assertEquals(Verdict.SIMILAR, verdictOf(lines, "평균 케이던스"))    // 170→172 (<3)
    }

    @Test fun worseSession_showsRegression() {
        val prev = report(1200, 3000.0, 140, inSec = 800, submax = 138.0, spm = 176)
        val cur = report(1200, 3000.0, 152, inSec = 500, submax = 147.0, spm = 176)
        val lines = SessionCompare.compare(cur, prev)
        assertEquals(Verdict.WORSE, verdictOf(lines, "Zone 2 비율"))        // 66→41%
        assertEquals(Verdict.WORSE, verdictOf(lines, "효율(심박당 거리)"))  // 낮아짐
        assertEquals(Verdict.WORSE, verdictOf(lines, "서브맥시멀 심박"))    // 138→147 상승=악화
    }

    @Test fun submaxOmitted_whenMissing() {
        val prev = report(1200, 3000.0, 145, inSec = 700, submax = null, spm = 175)
        val cur = report(1200, 3000.0, 143, inSec = 720, submax = 140.0, spm = 175)
        val lines = SessionCompare.compare(cur, prev)
        // 이전에 서브맥시멀 없으면 그 줄은 생략
        assertEquals(0, lines.count { it.label == "서브맥시멀 심박" })
    }
}
