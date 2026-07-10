package com.zone2runner.app.domain

/**
 * 세션 간 추세/개인 기록(FR6, spec-025) — 누적 세션을 최대한 활용해 "쌓일수록" 가치를 보인다.
 * 순수 함수(테스트 가능). history는 시간순(오래된→최근), 현재 세션 포함.
 */
object SessionTrends {

    data class Trend(val label: String, val values: List<Double>, val higherBetter: Boolean, val unit: String)
    data class Record(val label: String, val value: String, val isNew: Boolean) // 개인 기록(PR)

    private const val SPARK_N = 12 // 스파크라인에 표시할 최근 세션 수

    /** 지표별 최근 N세션 시퀀스(스파크라인용). 값 없는 세션은 제외한 시퀀스. */
    fun trends(history: List<RunReport>): List<Trend> {
        fun seq(sel: (RunReport) -> Double?, label: String, higher: Boolean, unit: String): Trend? {
            val vs = history.mapNotNull(sel).takeLast(SPARK_N)
            return if (vs.size >= 2) Trend(label, vs, higher, unit) else null
        }
        return listOfNotNull(
            seq({ if (it.ef > 0) it.ef else null }, "효율(EF)", true, ""),
            seq({ it.submaxHr }, "서브맥시멀 심박", false, "bpm"),
            seq({ if (it.series.size >= 8) it.cardiacDriftPct else null }, "드리프트", false, "%"),
            seq({ it.zone2Pct.toDouble() }, "Zone 2 비율", true, "%"),
        )
    }

    /** 개인 기록 목록 + 현재 세션이 갱신했는지. current는 history의 마지막이어야 정확. */
    fun records(history: List<RunReport>, current: RunReport): List<Record> {
        if (history.isEmpty()) return emptyList()
        val out = ArrayList<Record>()

        fun best(sel: (RunReport) -> Double?, higher: Boolean, label: String, fmt: (Double) -> String) {
            val vals = history.mapNotNull { r -> sel(r)?.let { r to it } }
            if (vals.isEmpty()) return
            val bestPair = if (higher) vals.maxByOrNull { it.second }!! else vals.minByOrNull { it.second }!!
            val cur = sel(current)
            val isNew = cur != null && bestPair.first === current
            out += Record(label, fmt(bestPair.second), isNew)
        }

        best({ if (it.ef > 0) it.ef else null }, true, "최고 효율(EF)") { "%.2f".format(it) }
        best({ if (it.distanceM > 100) it.distanceM else null }, true, "최장 거리") {
            if (it >= 1000) "%.2f km".format(it / 1000.0) else "%.0f m".format(it)
        }
        best({ if (it.inSec > 0) it.inSec.toDouble() else null }, true, "최장 Zone 2 시간") {
            "%d분".format((it / 60).toInt())
        }
        best({ if (it.series.size >= 8) it.cardiacDriftPct else null }, false, "최저 드리프트") { "%.1f%%".format(it) }
        best({ it.submaxHr }, false, "최저 서브맥시멀 심박") { "%.0f bpm".format(it) }
        return out
    }

    /** 현재 세션이 세운 신기록만(코칭 마일스톤/배지 강조용). */
    fun newRecords(history: List<RunReport>, current: RunReport): List<Record> =
        records(history, current).filter { it.isNew }
}
