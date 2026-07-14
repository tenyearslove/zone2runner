package com.zone2runner.app.analysis

import com.zone2runner.app.domain.MPS_PER_MIN_KM
import com.zone2runner.app.domain.RunReport
import com.zone2runner.app.domain.SeriesPoint
import com.zone2runner.app.analysis.AnalysisConfig.MINETTI_LEVEL
import com.zone2runner.app.analysis.AnalysisConfig.minettiCr

/**
 * 세션 내 심층 분석(FR6, spec-025) — 수집 시계열을 최대한 활용해 구간/경사/워밍업을 분해한다.
 * 순수 함수(테스트 가능). GAP은 Minetti 다항식(경사 정규화). 시계열이 얕으면 빈 결과.
 */
object SessionAnalytics {

    data class Split(val km: Int, val avgHr: Int, val avgPaceMinKm: Double, val avgGapMinKm: Double)
    data class GradeBand(val label: String, val sec: Int, val avgHr: Int, val avgGapMinKm: Double)
    data class Warmup(val reachSec: Int, val startHr: Int, val plateauHr: Int, val abrupt: Boolean)
    data class DownhillBehavior(val slowerPct: Int, val downSec: Int) // 내리막이 평지보다 몇 % 느렸나(양수=느림)
    data class ExitCauses(
        val aboveSec: Int, val uphillPct: Int, val fastPacePct: Int, val latePct: Int, val otherPct: Int,
        val note: String,
    )

    private fun mps(pace: Double) = MPS_PER_MIN_KM / pace.coerceAtLeast(0.1)   // m/s
    private fun gap(pace: Double, slopePct: Double) = pace * (MINETTI_LEVEL / minettiCr(slopePct / 100.0))
    private fun valid(p: SeriesPoint) = p.hr > 0 && p.paceMinKm in 2.0..20.0

    /** 각 점의 시간 간격(초) — 다운샘플 간격을 추정(최대 5초로 클램프해 이상 구간 방어). */
    private fun dt(series: List<SeriesPoint>, i: Int): Int {
        if (series.size < 2) return 3
        val d = if (i + 1 < series.size) series[i + 1].tSec - series[i].tSec else series[i].tSec - series[i - 1].tSec
        return d.coerceIn(1, 5)
    }

    /** km 구간별 평균 심박/페이스/경사보정 페이스. 누적거리로 버킷팅. */
    fun splits(r: RunReport): List<Split> {
        val s = r.series
        if (s.size < 8) return emptyList()
        val hrSum = HashMap<Int, Double>(); val paceSum = HashMap<Int, Double>()
        val gapSum = HashMap<Int, Double>(); val wSum = HashMap<Int, Double>()
        var dist = 0.0
        for (i in s.indices) {
            val p = s[i]; if (!valid(p)) continue
            val w = dt(s, i).toDouble()
            val km = (dist / 1000.0).toInt()
            hrSum[km] = (hrSum[km] ?: 0.0) + p.hr * w
            paceSum[km] = (paceSum[km] ?: 0.0) + p.paceMinKm * w
            gapSum[km] = (gapSum[km] ?: 0.0) + gap(p.paceMinKm, p.slopePct) * w
            wSum[km] = (wSum[km] ?: 0.0) + w
            dist += mps(p.paceMinKm) * w
        }
        return wSum.keys.sorted().mapNotNull { km ->
            val w = wSum[km] ?: return@mapNotNull null
            if (w < 20) return@mapNotNull null // 20초 미만 조각 구간은 생략(마지막 짧은 꼬리)
            Split(km + 1, Math.round(hrSum[km]!! / w).toInt(), paceSum[km]!! / w, gapSum[km]!! / w)
        }
    }

    /** 오르막(>2%)/평지/내리막(<-2%)별 체류시간+평균 심박+경사보정 페이스. */
    fun gradeBreakdown(r: RunReport): List<GradeBand> {
        val s = r.series
        if (s.size < 8) return emptyList()
        data class Acc(var sec: Double = 0.0, var hr: Double = 0.0, var gap: Double = 0.0)
        val up = Acc(); val flat = Acc(); val down = Acc()
        for (i in s.indices) {
            val p = s[i]; if (!valid(p)) continue
            val w = dt(s, i).toDouble()
            val a = when { p.slopePct > 2 -> up; p.slopePct < -2 -> down; else -> flat }
            a.sec += w; a.hr += p.hr * w; a.gap += gap(p.paceMinKm, p.slopePct) * w
        }
        fun band(label: String, a: Acc): GradeBand? =
            if (a.sec < 20) null else GradeBand(label, a.sec.toInt(), Math.round(a.hr / a.sec).toInt(), a.gap / a.sec)
        return listOfNotNull(band("오르막", up), band("평지", flat), band("내리막", down))
    }

    /**
     * Zone 2 초과(이탈) 원인 분해 — 초과 구간을 오르막/빠른 페이스/후반 드리프트/기타로 귀속(설명용이성 FR6).
     * 우선순위: 오르막 > 빠른 페이스 > 후반(드리프트) > 기타. 초과가 거의 없으면 null.
     */
    fun exitCauses(r: RunReport): ExitCauses? {
        val s = r.series
        if (s.size < 8) return null
        val paces = s.filter { valid(it) }.map { it.paceMinKm }.sorted()
        if (paces.isEmpty()) return null
        val medPace = paces[paces.size / 2]
        val dur = s.last().tSec.coerceAtLeast(1)
        var above = 0.0; var up = 0.0; var fast = 0.0; var late = 0.0; var other = 0.0
        for (i in s.indices) {
            val p = s[i]
            if (p.judgmentIndex != 2) continue // 2 = 초과(ABOVE)
            val w = dt(s, i).toDouble()
            above += w
            when {
                p.slopePct > 2 -> up += w
                p.paceMinKm < medPace * 0.92 -> fast += w   // 세션 중앙값보다 빠름
                p.tSec > dur * 0.6 -> late += w             // 후반(드리프트 추정)
                else -> other += w
            }
        }
        if (above < 20) return null
        fun pct(x: Double) = Math.round(x / above * 100).toInt()
        val parts = ArrayList<String>()
        if (pct(up) > 0) parts += "오르막 ${pct(up)}%"
        if (pct(fast) > 0) parts += "빠른 페이스 ${pct(fast)}%"
        if (pct(late) > 0) parts += "후반 드리프트 ${pct(late)}%"
        val note = "초과 ${(above / 60).toInt()}분 중 " + parts.joinToString(", ") +
            (if (pct(up) >= 50) " — 오르막 전에 미리 페이스를 늦추면 도움돼요." else "")
        return ExitCauses(above.toInt(), pct(up), pct(fast), pct(late), pct(other), note)
    }

    /** 유의한 워밍업 상승 최소폭(bpm, 種類C — spec-025 §9-1). 이보다 덜 오르면 램프가 없다고 본다. */
    private const val WARMUP_MIN_RISE = 8

    /** 워밍업 품질 — 초반 심박 상승. 안정심박대(중반 평균) 도달까지 시간. 급상승이면 abrupt.
     *  심박이 유의하게 오르지 않았으면(이미 높게 시작했거나 하강) 워밍업 램프가 없으므로 null 반환(오도 방지). */
    fun warmup(r: RunReport): Warmup? {
        val s = r.series.filter { valid(it) }
        if (s.size < 20) return null
        val end = s.last().tSec
        if (end < 300) return null // 5분 미만은 워밍업 분해 무의미
        val mid = s.filter { it.tSec in 120..minOf(360, end) }
        if (mid.isEmpty()) return null
        val plateau = Math.round(mid.map { it.hr }.average()).toInt()
        val startHr = s.first().hr
        // 워밍업 = 낮은 출발 → 안정대로 '오르는' 구간. 유의한 상승이 없으면(내려갔거나 이미 높게 시작)
        // 분해가 무의미 → 생략. 그래야 "내려갔는데 올랐다"거나 "도달 0초" 같은 오도 문구가 안 나온다.
        // (시뮬은 존 안에서 시작하기도 해서 이 경우가 흔하다.)
        if (plateau - startHr < WARMUP_MIN_RISE) return null
        val reach = s.firstOrNull { it.hr >= plateau - 5 }?.tSec ?: return null
        if (reach <= 0) return null // 시작부터 안정대면 램프가 없음
        // 급상승: 안정대 도달 90초 이내 + 초반 상승폭이 큼(種類C: 90초/15bpm 선언)
        val abrupt = reach < 90 && (plateau - startHr) >= 15
        return Warmup(reach, startHr, plateau, abrupt)
    }

    /**
     * 내리막 습관(관측, 種類 A, spec-026) — 내리막(경사≤−4%) 평균 페이스 vs 평지(±2%) 평균 페이스 비교.
     * 양수 slowerPct = 내리막에서 더 느리게 뜀(관절 보호로 조심스러운 내리막). GAP는 건드리지 않는다(대사 축).
     * 표본 부족(각 10 미만) 시 null.
     */
    fun downhillBehavior(r: RunReport): DownhillBehavior? {
        val s = r.series.filter { valid(it) }
        val down = s.filter { it.slopePct <= -4.0 }
        val flat = s.filter { it.slopePct in -2.0..2.0 }
        if (down.size < 10 || flat.size < 10) return null
        val downPace = down.map { it.paceMinKm }.average()
        val flatPace = flat.map { it.paceMinKm }.average()
        if (flatPace <= 0.0) return null
        val slowerPct = Math.round((downPace / flatPace - 1.0) * 100).toInt() // +면 내리막이 더 느림
        return DownhillBehavior(slowerPct, down.size)
    }
}
