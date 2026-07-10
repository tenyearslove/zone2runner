package com.zone2runner.app.domain

import kotlin.math.abs

/**
 * 세션 간 비교(FR6, spec-025) — 이번 세션이 이전 대비 무엇이 향상/악화됐는지 도출한다.
 * 순수 함수(테스트 가능). 페이스 차이로 심박을 직접 비교하면 오도되므로, 효율(EF)/드리프트/Zone2 비율/
 * 서브맥시멀 HR 처럼 강도에 덜 휘둘리는 지표로 비교한다(정직).
 */
enum class Verdict { BETTER, WORSE, SIMILAR }

data class CompareLine(
    val label: String,
    val prev: String,
    val cur: String,
    val verdict: Verdict,
    val delta: String, // 부호 포함 변화량 표시("+13%p" 등)
)

object SessionCompare {

    /** cur(이번) vs prev(직전) 비교 라인들. 비교 불가한 항목은 생략. */
    fun compare(cur: RunReport, prev: RunReport): List<CompareLine> {
        val out = ArrayList<CompareLine>()

        // Zone 2 비율(%) — 높을수록 좋음
        out += line("Zone 2 비율", prev.zone2Pct.toDouble(), cur.zone2Pct.toDouble(),
            higherBetter = true, similar = 3.0, unit = "%", deltaUnit = "%p", fmt = "%.0f")

        // 효율(심박당 거리, EF) — 높을수록 좋음
        if (cur.ef > 0.0 && prev.ef > 0.0) {
            out += line("효율(심박당 거리)", prev.ef, cur.ef,
                higherBetter = true, similar = 0.03, unit = "", deltaUnit = "", fmt = "%.2f")
        }

        // 심혈관 드리프트(%) — 낮을수록 좋음
        out += line("심혈관 드리프트", prev.cardiacDriftPct, cur.cardiacDriftPct,
            higherBetter = false, similar = 1.5, unit = "%", deltaUnit = "%p", fmt = "%.1f")

        // 서브맥시멀 심박(같은 페이스 구간) — 낮을수록 좋음(둘 다 있을 때)
        val cs = cur.submaxHr; val ps = prev.submaxHr
        if (cs != null && ps != null) {
            out += line("서브맥시멀 심박", ps, cs,
                higherBetter = false, similar = 2.0, unit = " bpm", deltaUnit = " bpm", fmt = "%.0f")
        }

        // 평균 케이던스(spm) — 방향성 약함, 정보성(큰 변화만 표기)
        if (cur.avgSpm > 0 && prev.avgSpm > 0) {
            out += line("평균 케이던스", prev.avgSpm.toDouble(), cur.avgSpm.toDouble(),
                higherBetter = true, similar = 3.0, unit = " spm", deltaUnit = " spm", fmt = "%.0f",
                informationalOnly = true)
        }

        return out
    }

    private fun line(
        label: String, prev: Double, cur: Double,
        higherBetter: Boolean, similar: Double, unit: String, deltaUnit: String, fmt: String,
        informationalOnly: Boolean = false,
    ): CompareLine {
        val d = cur - prev
        val verdict = when {
            abs(d) < similar -> Verdict.SIMILAR
            informationalOnly -> Verdict.SIMILAR
            (d > 0) == higherBetter -> Verdict.BETTER
            else -> Verdict.WORSE
        }
        val sign = if (d > 0) "+" else ""
        return CompareLine(
            label = label,
            prev = fmt.format(prev) + unit,
            cur = fmt.format(cur) + unit,
            verdict = verdict,
            delta = sign + fmt.format(d) + deltaUnit,
        )
    }
}
