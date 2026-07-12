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

    data class Condition(val verdict: Verdict, val note: String)

    /**
     * 그날 컨디션 지표 — 오늘 효율(EF)을 개인 최근 baseline(직전 N세션 중앙값)과 비교(種類B).
     * 높으면 컨디션 좋음, 낮으면 피로/더위/수면부족 신호 가능. 직전 세션 3회 미만이면 null.
     */
    fun condition(history: List<RunReport>, current: RunReport): Condition? {
        val priors = history.filter { it !== current && it.ef > 0 }.takeLast(6)
        if (priors.size < 3 || current.ef <= 0) return null
        val efs = priors.map { it.ef }.sorted()
        val base = efs[efs.size / 2] // 중앙값
        if (base <= 0) return null
        val ratio = current.ef / base
        val pct = Math.round((ratio - 1.0) * 100).toInt()
        return when {
            ratio >= 1.03 -> Condition(Verdict.BETTER, "오늘 효율이 평소보다 %+d%% 높아요 — 컨디션 좋은 날이에요.".format(pct))
            ratio <= 0.97 -> Condition(Verdict.WORSE, "오늘 효율이 평소보다 %d%% 낮아요 — 피로/더위/수면부족 신호일 수 있어요.".format(pct))
            else -> Condition(Verdict.SIMILAR, "오늘 효율이 평소 수준이에요.")
        }
    }

    data class Period(val days: Int, val sessions: Int, val zone2Min: Int, val distanceKm: Double, val avgEf: Double)

    /** 최근 days일 요약(누적 데이터 활용). nowEpochMs 기준 롤링 창. 앱이 현재 시각 주입(테스트 가능). */
    fun period(history: List<RunReport>, nowEpochMs: Long, days: Int): Period {
        val from = nowEpochMs - days.toLong() * 86_400_000L
        val inWin = history.filter { it.startedAtEpochMs in from..nowEpochMs }
        val z2 = inWin.sumOf { it.inSec } / 60
        val dist = inWin.sumOf { it.distanceM } / 1000.0
        val efs = inWin.mapNotNull { if (it.ef > 0) it.ef else null }
        val avgEf = if (efs.isNotEmpty()) efs.average() else 0.0
        return Period(days, inWin.size, z2, dist, avgEf)
    }
}
