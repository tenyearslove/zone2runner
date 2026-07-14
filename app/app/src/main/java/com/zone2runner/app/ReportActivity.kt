package com.zone2runner.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zone2runner.app.domain.RunReport
import com.zone2runner.app.domain.Zone2Prior
import com.zone2runner.app.domain.ZoneJudgment
import com.zone2runner.app.ui.Palette
import com.zone2runner.app.ui.ReportHolder
import com.zone2runner.app.ui.withSystemBarInsets
import com.zone2runner.app.ui.TimeSeriesChartView
import com.zone2runner.app.ui.ZoneBarView
import com.zone2runner.app.ui.ZoneTimelineView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/** 러닝 리포트: 요약 지표 + 존 분포 + 개인화 결과 + 경로 지도(존 색 채색) + 코칭 로그. */
class ReportActivity : AppCompatActivity() {

    private var map: MapView? = null
    private var prevReport: RunReport? = null // 효율곡선 겹치기용 직전 세션

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        val r = ReportHolder.last
        setContentView((if (r == null) emptyView() else buildReport(r)).withSystemBarInsets())
    }

    private fun emptyView() = TextView(this).apply {
        text = "리포트 없음"; setBackgroundColor(C_BG); setTextColor(C_TEXT); gravity = Gravity.CENTER; textSize = 18f
    }

    private fun buildReport(r: RunReport): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(C_BG); setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        col.addView(TextView(this).apply {
            text = "러닝 리포트"; textSize = 24f; setTypeface(typeface, Typeface.BOLD); setTextColor(C_TEXT)
        })
        col.addView(TextView(this).apply {
            val src = if (r.sourceMode == "live") "실센서(GPS+워치HR)" else "시뮬레이션"
            // 판정=규칙(adr-013), 분석=관측 엔진(adr-024), 표현=LLM/규칙(adr-002).
            val coach = if (r.coachSource == "llm") "Gemini Nano 코칭" else "규칙 코칭"
            text = "$src · 규칙 판정 + 관측 분석 + 개인화 + $coach"
            textSize = 11f; setTextColor(C_MUTED); setPadding(0, dp(2), 0, dp(12))
        })

        // 이전 세션 대비 향상/악화(FR6, spec-025) — 데이터를 세션 간으로 활용
        buildCompareCard(r)?.let { col.addView(it) }

        // 세션 간 추세 + 개인 기록 — ★이 세션 시점까지의 이력만(리포트마다 다른 추세). 이후 세션은 제외.
        val curStart = r.startedAtEpochMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val history = com.zone2runner.app.data.SessionStore.allReports(this)
            .filter { it.startedAtEpochMs <= curStart && it.id != r.id }
            .let { it + r } // 현재 세션을 마지막에 포함(저장 여부 무관)
            .sortedBy { it.startedAtEpochMs }
        prevReport = history.filter {
            it.id != r.id && it.startedAtEpochMs in 1 until (r.startedAtEpochMs.takeIf { s -> s > 0 } ?: Long.MAX_VALUE)
        }.maxByOrNull { it.startedAtEpochMs }
        buildConditionCard(history, r)?.let { col.addView(it) }
        buildTrendCard(history)?.let { col.addView(it) }
        buildRecordsCard(history, r)?.let { col.addView(it) }
        buildWeeklyCard(history)?.let { col.addView(it) }

        // 사후 세션 스토리(spec-023 FR2): "이번 러닝에서 왜 이렇게 코칭했나". 세션 종료 시 1회 생성·저장.
        if (r.sessionStory.isNotBlank()) {
            col.addView(card("이번 러닝 이야기", TextView(this).apply {
                text = r.sessionStory; textSize = 14f; setTextColor(C_TEXT); setLineSpacing(dp(3).toFloat(), 1f)
            }))
        }

        // 요약 그리드 (2열 x 3행)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(statRow(
            "거리" to fmtDist(r.distanceM),
            "시간" to "%02d:%02d".format(r.durationSec / 60, r.durationSec % 60)))
        grid.addView(statRow(
            "평균 심박" to "${r.avgHr} bpm",
            "최대 심박" to "${r.maxHr} bpm"))
        grid.addView(statRow(
            "평균 페이스" to fmtPace(r.avgPaceMinKm),
            "Zone 2 비율" to "${r.zone2Pct}%"))
        if (r.avgSpm > 0) grid.addView(statRow(
            "평균 케이던스" to "${r.avgSpm} spm",
            "평균 보폭" to (r.avgStrideM?.let { "%.2f m".format(it) } ?: "--")))
        col.addView(card("요약", grid))

        // 존 분포
        val bar = ZoneBarView(this).also { it.set(r.belowSec, r.inSec, r.aboveSec) }
        // FR4: 판정 "왜 이 존" 미니 설명 — 평균심박 vs 개인 상/하한 → 판정 근거를 한 줄로.
        val hrrB = (r.maxHrProfile - r.restingHr).toDouble()
        val hiB = (r.restingHr + r.uEstEndFrac * hrrB).toInt()
        val loB = (r.restingHr + (r.uEstEndFrac - Zone2Prior.BAND) * hrrB).toInt()
        val whyZone = when {
            r.avgHr > hiB -> "평균 ${r.avgHr} > 상한 ${hiB}bpm → 평균적으로 초과"
            r.avgHr < loB -> "평균 ${r.avgHr} < 하한 ${loB}bpm → 평균적으로 미달"
            else -> "평균 ${r.avgHr}bpm이 개인 범위 ${loB}~${hiB}bpm 안 → Zone 2 유지"
        }
        col.addView(card("존 체류 분포", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dp(40)))
            addView(TextView(this@ReportActivity).apply {
                text = "미달 ${sec(r.belowSec)} · 유지 ${sec(r.inSec)} · 초과 ${sec(r.aboveSec)}"
                textSize = 11f; setTextColor(C_MUTED); setPadding(0, dp(6), 0, 0)
            })
            addView(TextView(this@ReportActivity).apply {
                text = whyZone; textSize = 12f; setTextColor(C_TEXT); setPadding(0, dp(6), 0, 0)
            })
        }))

        // 유산소 분석: HR 추이(목표 밴드) + 존 타임라인 + 드리프트/평가
        addAerobicAnalysis(col, r)

        // 개인화 결과 (내부 uFrac을 실제 심박 bpm으로 환산해 표시 — 설계 용어 노출 안 함)
        val hrr = (r.maxHrProfile - r.restingHr).toDouble()
        val startBpm = (r.restingHr + r.uEstStartFrac * hrr).toInt()
        val endBpm = (r.restingHr + r.uEstEndFrac * hrr).toInt()
        col.addView(card("개인 Zone 2 상단 학습", TextView(this).apply {
            text = "이번 세션 개인 상단: $startBpm → $endBpm bpm\n" +
                "프로필 초기값에서 실주행 관측(말하기 테스트/드리프트)으로 개인 경계로 이동합니다."
            textSize = 13f; setTextColor(C_TEXT)
        }))

        // 경로 지도(존 색)
        val mv = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(15.0)
        }
        map = mv
        drawTrack(mv, r)
        col.addView(card("경로 (존 색상)", LinearLayout(this).apply {
            addView(mv, LinearLayout.LayoutParams(MATCH_PARENT, dp(240)))
        }))

        // 코칭 로그
        val coachBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (r.coachingLines.isEmpty()) {
            coachBox.addView(TextView(this).apply { text = "코칭 없음"; setTextColor(C_MUTED); textSize = 12f })
        } else {
            r.coachingLines.takeLast(12).forEach {
                coachBox.addView(TextView(this).apply { text = it; setTextColor(C_TEXT); textSize = 12f; setPadding(0, dp(2), 0, dp(2)) })
            }
        }
        col.addView(card("코칭 로그 (규칙 방향 + 표현)", coachBox))

        return ScrollView(this).apply { setBackgroundColor(C_BG); addView(col) }
    }

    /** 유산소 존 분석 섹션: HR 추이(Zone2 밴드 음영) + 페이스 추이 + 존 타임라인 + 드리프트/평가. */
    private fun addAerobicAnalysis(col: LinearLayout, r: RunReport) {
        val hrPoints = r.series.filter { it.hr > 0 }
        if (hrPoints.size < 4) return

        // Zone2 목표 밴드(bpm): HRR 대비 (uEstEnd-밴드폭) ~ uEstEnd. 밴드폭은 Zone2Prior.BAND로 통일.
        val hrr = (r.maxHrProfile - r.restingHr).coerceAtLeast(1)
        val hi = (r.restingHr + r.uEstEndFrac * hrr).toFloat()
        val lo = (r.restingHr + (r.uEstEndFrac - com.zone2runner.app.domain.Zone2Prior.BAND) * hrr).toFloat()

        // HR 추이
        val hrChart = TimeSeriesChartView(this).also {
            it.set(hrPoints.map { p -> p.hr.toFloat() }, lo, hi, ZoneJudgment.IN.color, "bpm")
        }
        col.addView(card("심박 추이 (초록 밴드 = Zone 2 목표)", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hrChart, LinearLayout.LayoutParams(MATCH_PARENT, dp(150)))
        }))

        // 페이스 추이
        val paceP = r.series.filter { it.paceMinKm in 0.1..30.0 }
        if (paceP.size >= 4) {
            val paceChart = TimeSeriesChartView(this).also {
                it.set(paceP.map { p -> p.paceMinKm.toFloat() }, Float.NaN, Float.NaN, C_BLUE, "min/km")
            }
            col.addView(card("페이스 추이 (min/km, 낮을수록 빠름)", LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(paceChart, LinearLayout.LayoutParams(MATCH_PARENT, dp(120)))
            }))
        }

        // 존 타임라인
        val timeline = ZoneTimelineView(this).also { it.set(r.series.map { p -> p.judgmentIndex }) }
        col.addView(card("존 타임라인 (시간 순 판정)", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(timeline, LinearLayout.LayoutParams(MATCH_PARENT, dp(22)))
            addView(TextView(this@ReportActivity).apply {
                text = "파랑 미달 · 초록 Zone 2 · 주황 초과"
                textSize = 11f; setTextColor(C_MUTED); setPadding(0, dp(6), 0, 0)
            })
        }))

        // 유산소 평가
        col.addView(card("유산소 분석", TextView(this).apply {
            text = aerobicAssessment(r); textSize = 13f; setTextColor(C_TEXT)
        }))

        // 관측 분석 엔진(FR3, spec-025) 지표 — 항상 계산되는 효율/드리프트 + 게이트 통과한 세션종료 지표
        col.addView(card("관측 분석 지표", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fun row(t: String, muted: Boolean = false) = addView(TextView(this@ReportActivity).apply {
                text = t; textSize = 12f; setTextColor(if (muted) C_MUTED else C_TEXT); setPadding(0, dp(2), 0, dp(2))
            })
            if (r.ef > 0) row("효율(심박당 거리) %.2f — 세션 간 높아지면 유산소 개선".format(r.ef))
            row("심혈관 드리프트 %.1f%% — 낮을수록 안정(같은 페이스에 심박 상승 적음)".format(r.cardiacDriftPct))
            r.submaxHr?.let { row("서브맥시멀 심박 %.0f bpm — 낮아지면 체력 개선".format(it)) }
            r.analysisLines.forEach { ln -> row("· $ln", muted = true) }
        }))

        // 구간(splits) / 경사 분해 / 워밍업 / 이탈원인 / 효율곡선 — 수집 시계열 최대 활용
        buildSplitsCard(r)?.let { col.addView(it) }
        buildGradeCard(r)?.let { col.addView(it) }
        buildWarmupCard(r)?.let { col.addView(it) }
        buildExitCauseCard(r)?.let { col.addView(it) }
        buildScatterCard(r)?.let { col.addView(it) }
    }

    /** 이전 세션 대비 향상/악화 카드(FR6). 직전 세션이 없으면 안내, 비교 항목 없으면 null. */
    private fun buildCompareCard(cur: RunReport): android.view.View? {
        val curStart = cur.startedAtEpochMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val prevSum = com.zone2runner.app.data.SessionStore.listSummaries(this)
            .filter { it.id != cur.id && it.startedAtEpochMs in 1 until curStart }
            .maxByOrNull { it.startedAtEpochMs }
            ?: return card("이전 세션 대비", TextView(this).apply {
                text = "첫 세션이에요. 다음 러닝부터 이전 대비 무엇이 향상/악화됐는지 보여드려요."
                textSize = 12f; setTextColor(C_MUTED)
            })
        val prev = com.zone2runner.app.data.SessionStore.load(this, prevSum.id) ?: return null
        val lines = com.zone2runner.app.domain.SessionCompare.compare(cur, prev)
        if (lines.isEmpty()) return null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lines.forEach { ln ->
            val (color, mark) = when (ln.verdict) {
                com.zone2runner.app.domain.Verdict.BETTER -> C_GREEN to "▲ 개선"
                com.zone2runner.app.domain.Verdict.WORSE -> C_AMBER to "▼ 악화"
                com.zone2runner.app.domain.Verdict.SIMILAR -> C_MUTED to "= 비슷"
            }
            box.addView(TextView(this).apply {
                text = "${ln.label}   ${ln.prev} → ${ln.cur}   $mark (${ln.delta})"
                textSize = 13f; setTextColor(color); setPadding(0, dp(3), 0, dp(3))
            })
        }
        return card("이전 세션 대비", box)
    }

    /** 그날 컨디션 지표(FR6) — 오늘 효율 vs 개인 평소 baseline. */
    private fun buildConditionCard(history: List<RunReport>, cur: RunReport): android.view.View? {
        val c = com.zone2runner.app.domain.SessionTrends.condition(history, cur) ?: return null
        val color = when (c.verdict) {
            com.zone2runner.app.domain.Verdict.BETTER -> C_GREEN
            com.zone2runner.app.domain.Verdict.WORSE -> C_AMBER
            com.zone2runner.app.domain.Verdict.SIMILAR -> C_TEXT
        }
        return card("오늘 컨디션", TextView(this).apply { text = c.note; textSize = 13f; setTextColor(color) })
    }

    /** 최근 7일 / 30일 요약(FR6) — 누적 데이터. */
    private fun buildWeeklyCard(history: List<RunReport>): android.view.View? {
        if (history.size < 2) return null
        val now = System.currentTimeMillis()
        val wk = com.zone2runner.app.domain.SessionTrends.period(history, now, 7)
        val mo = com.zone2runner.app.domain.SessionTrends.period(history, now, 30)
        if (wk.sessions == 0 && mo.sessions == 0) return null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(p: com.zone2runner.app.domain.SessionTrends.Period, label: String) = box.addView(TextView(this).apply {
            text = "$label: ${p.sessions}회 · Zone 2 ${p.zone2Min}분 · %.1f km · 평균 효율 %.2f".format(p.distanceKm, p.avgEf)
            textSize = 12f; setTextColor(C_TEXT); setPadding(0, dp(3), 0, dp(3))
        })
        row(wk, "최근 7일"); row(mo, "최근 30일")
        return card("기간 요약", box)
    }

    /** Zone 2 이탈 원인 분해(FR6, 설명용이성). */
    private fun buildExitCauseCard(r: RunReport): android.view.View? {
        val ec = com.zone2runner.app.analysis.SessionAnalytics.exitCauses(r) ?: return null
        return card("초과 원인 분해", TextView(this).apply {
            text = ec.note; textSize = 13f; setTextColor(C_TEXT); setLineSpacing(dp(2).toFloat(), 1f)
        })
    }

    /** 효율 곡선 산점도(FR6) — 이번 vs 직전 세션 HR-페이스. */
    private fun buildScatterCard(r: RunReport): android.view.View? {
        fun pts(rep: RunReport) = rep.series.filter { it.hr > 0 && it.paceMinKm in 2.0..20.0 }
            .map { it.paceMinKm.toFloat() to it.hr.toFloat() }
        val cur = pts(r); if (cur.size < 8) return null
        val prev = prevReport?.let { pts(it) } ?: emptyList()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(com.zone2runner.app.ui.ScatterView(this).also {
            it.set(cur, prev)
            it.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(160))
        })
        box.addView(TextView(this).apply {
            text = if (prev.isEmpty()) "초록: 이번 세션. 같은 페이스에 심박이 낮아지면 유산소 개선."
            else "초록: 이번 · 회색: 직전. 초록이 회색보다 아래면 같은 페이스에 심박이 낮아진 것(개선)."
            textSize = 11f; setTextColor(C_MUTED); setPadding(0, dp(6), 0, 0)
        })
        return card("효율 곡선 (심박-페이스)", box)
    }

    /** 세션 간 추세 스파크라인(FR6) — 지표별 최근 N세션 미니 그래프. */
    private fun buildTrendCard(history: List<RunReport>): android.view.View? {
        val trends = com.zone2runner.app.domain.SessionTrends.trends(history)
        if (trends.isEmpty()) return null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        trends.forEach { t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = t.label; textSize = 12f; setTextColor(C_TEXT)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            row.addView(com.zone2runner.app.ui.SparklineView(this).apply { set(t.values, t.higherBetter) }.also {
                it.layoutParams = LinearLayout.LayoutParams(dp(96), dp(28))
            })
            row.addView(TextView(this).apply {
                val last = t.values.last()
                text = if (t.unit.isBlank()) "  %.2f".format(last) else "  %.0f%s".format(last, t.unit)
                textSize = 12f; setTextColor(C_MUTED)
            })
            box.addView(row)
        }
        return card("세션 추세 (최근 ${trends.first().values.size}회)", box)
    }

    /** 개인 기록(PR) + 이번 세션 신기록 배지(FR6). */
    private fun buildRecordsCard(history: List<RunReport>, cur: RunReport): android.view.View? {
        val recs = com.zone2runner.app.domain.SessionTrends.records(history, cur)
        if (recs.isEmpty()) return null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recs.forEach { rec ->
            box.addView(TextView(this).apply {
                val badge = if (rec.isNew) "   🏅 이번 세션 신기록!" else ""
                text = "${rec.label}: ${rec.value}$badge"
                textSize = 13f; setTextColor(if (rec.isNew) C_GREEN else C_TEXT); setPadding(0, dp(3), 0, dp(3))
            })
        }
        return card("개인 기록", box)
    }

    /** km 구간 분석(FR6). */
    private fun buildSplitsCard(r: RunReport): android.view.View? {
        val splits = com.zone2runner.app.analysis.SessionAnalytics.splits(r)
        if (splits.size < 2) return null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = "km    심박     페이스     경사보정"; textSize = 11f; setTextColor(C_MUTED); setPadding(0, 0, 0, dp(4))
        })
        splits.forEach { s ->
            box.addView(TextView(this).apply {
                text = "%-4d %d bpm   %s   %s".format(s.km, s.avgHr, fmtPace(s.avgPaceMinKm), fmtPace(s.avgGapMinKm))
                textSize = 12f; setTextColor(C_TEXT); setPadding(0, dp(2), 0, dp(2))
            })
        }
        return card("구간 분석 (km별, 경사보정 포함)", box)
    }

    /** 오르막/평지/내리막 분해(FR6). */
    private fun buildGradeCard(r: RunReport): android.view.View? {
        val bands = com.zone2runner.app.analysis.SessionAnalytics.gradeBreakdown(r)
        if (bands.size < 2) return null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bands.forEach { b ->
            box.addView(TextView(this).apply {
                text = "%s   %d:%02d   평균 %d bpm   경사보정 %s".format(b.label, b.sec / 60, b.sec % 60, b.avgHr, fmtPace(b.avgGapMinKm))
                textSize = 12f; setTextColor(C_TEXT); setPadding(0, dp(2), 0, dp(2))
            })
        }
        return card("경사 구간 분해", box)
    }

    /** 워밍업 품질(FR6). */
    private fun buildWarmupCard(r: RunReport): android.view.View? {
        val w = com.zone2runner.app.analysis.SessionAnalytics.warmup(r) ?: return null
        val note = if (w.abrupt)
            "초반 %d초 만에 심박이 %d→%d bpm으로 급상승했어요. 다음엔 천천히 올리면 후반 드리프트를 줄일 수 있어요.".format(w.reachSec, w.startHr, w.plateauHr)
        else
            "심박이 %d초에 걸쳐 %d→%d bpm으로 완만히 올랐어요. 좋은 워밍업이에요.".format(w.reachSec, w.startHr, w.plateauHr)
        return card("워밍업", TextView(this).apply { text = note; textSize = 13f; setTextColor(C_TEXT); setLineSpacing(dp(2).toFloat(), 1f) })
    }

    private fun aerobicAssessment(r: RunReport): String {
        val z2 = r.zone2Pct
        val drift = r.cardiacDriftPct
        val base = when {
            z2 >= 65 -> "유산소(Zone 2) 비중이 높은 좋은 세션이에요. 지방 연소/기초 지구력 향상에 이상적입니다."
            z2 >= 40 -> "유산소 구간과 그 밖 구간이 섞였어요. 워밍업/오르막에서 존을 벗어난 것으로 보입니다."
            r.aboveSec > r.belowSec -> "강도가 목표보다 높았어요. 다음엔 더 천천히 시작해 Zone 2를 오래 유지해 보세요."
            else -> "강도가 목표보다 낮았어요. 조금 더 밀어 Zone 2까지 심박을 올려보세요."
        }
        val driftNote = when {
            drift >= 8 -> "\n후반 심혈관 드리프트가 %.1f%%로 큰 편이에요. 피로/탈수/더위 신호일 수 있습니다.".format(drift)
            drift >= 4 -> "\n후반부 심박이 %.1f%% 완만히 상승했어요(장시간 러닝의 정상 범위).".format(drift)
            drift >= 0 -> "\n심박이 %.1f%%로 안정적으로 유지됐어요. 좋은 유산소 컨디션입니다.".format(drift)
            else -> "\n후반부 심박이 오히려 안정됐어요(워밍업 후 안정화)."
        }
        // FR5: 드리프트→코칭 인과 연결. 후반 드리프트가 크고 초과 코칭이 있었다면 둘을 원인-결과로 잇는다.
        val overCoach = r.coachingLines.count { it.contains("(초과") }
        val causalNote = if (drift >= 5 && overCoach > 0)
            "\n→ 이 드리프트로 같은 페이스에서도 심박이 올라, 후반에 초과 알림이 ${overCoach}회 나갔어요. 후반엔 페이스를 조금 늦추면 유지에 도움이 됩니다."
        else ""
        val z2min = r.inSec / 60
        return "$base\nZone 2 유지 ${z2min}분(${z2}%), 평균 심박 ${r.avgHr} bpm.$driftNote$causalNote"
    }

    private fun drawTrack(mv: MapView, r: RunReport) {
        if (r.track.isEmpty()) return
        var seg = ArrayList<GeoPoint>()
        var curJ: ZoneJudgment? = r.track.first().judgment
        fun flush() {
            if (seg.size >= 2) {
                val pl = Polyline().apply {
                    outlinePaint.color = curJ?.color ?: Color.GRAY; outlinePaint.strokeWidth = 10f
                    setPoints(seg)
                }
                mv.overlays.add(pl)
            }
        }
        for (tp in r.track) {
            if (tp.judgment != curJ) { flush(); val last = seg.lastOrNull(); seg = ArrayList(); last?.let { seg.add(it) }; curJ = tp.judgment }
            seg.add(GeoPoint(tp.lat, tp.lon))
        }
        flush()
        val mid = r.track[r.track.size / 2]
        mv.controller.setCenter(GeoPoint(mid.lat, mid.lon))
    }

    // ---- UI helpers ----
    private fun card(title: String, content: android.view.View): LinearLayout {
        val info = infoFor(title)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(C_CARD); cornerRadius = dp(16).toFloat(); setStroke(dp(1), C_STROKE)
            }
            setPadding(dp(16), dp(12), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
            addView(TextView(this@ReportActivity).apply {
                text = if (info != null) "$title   ⓘ 설명" else title
                textSize = 12f
                setTextColor(if (info != null) C_GREEN else C_MUTED)
                setPadding(0, 0, 0, dp(8))
                if (info != null) {
                    isClickable = true
                    setOnClickListener { showInfoDialog(title, info) }
                }
            })
            addView(content)
        }
    }

    /** 카드 제목으로 설명문을 찾음(동적 접미사는 접두 매칭). 설명용이성 QA — 각 지표를 터치하면 설명 팝업. */
    private fun infoFor(title: String): String? {
        for ((k, v) in METRIC_INFO) if (title.startsWith(k)) return v
        return null
    }

    private fun showInfoDialog(title: String, body: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun statRow(a: Pair<String, String>, b: Pair<String, String>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(stat(a.first, a.second), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(stat(b.first, b.second), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    private fun stat(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6))
        addView(TextView(this@ReportActivity).apply { text = value; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(C_TEXT) })
        addView(TextView(this@ReportActivity).apply { text = label; textSize = 11f; setTextColor(C_MUTED) })
    }

    private fun fmtDist(m: Double) = if (m < 1000) "${m.toInt()} m" else "%.2f km".format(m / 1000)
    private fun fmtPace(p: Double) = if (p in 0.1..30.0) "%d'%02d\" /km".format(p.toInt(), ((p % 1) * 60).toInt()) else "--"
    private fun sec(s: Int) = "%d:%02d".format(s / 60, s % 60)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() { super.onResume(); map?.onResume() }
    override fun onPause() { super.onPause(); map?.onPause() }

    private companion object {
        val C_BG = Palette.BG
        val C_CARD = Palette.CARD
        val C_STROKE = Palette.STROKE
        val C_TEXT = Palette.TEXT
        val C_MUTED = Palette.MUTED
        val C_BLUE = Palette.BLUE
        val C_GREEN = Palette.ACCENT
        val C_AMBER = Palette.AMBER

        // 지표 설명(설명용이성 QA) — 카드 제목(접두)으로 매칭, 터치 시 팝업. 중학생 눈높이, 내부용어 금지.
        val METRIC_INFO = linkedMapOf(
            "요약" to "이번 러닝의 기본 숫자예요. 거리/시간은 총량, 평균/최대 심박은 세션 내내의 평균과 최고, 평균 페이스는 1km에 걸린 평균 시간(작을수록 빠름)이에요. Zone 2 비율은 전체 시간 중 목표 유산소 구간(Zone 2)에 머문 비율(%)로, 이 앱의 핵심 성적표예요.",
            "존 체류 분포" to "심박 강도를 Z1~Z5로 나눠 각 구간에 머문 시간을 보여줘요. 목표인 Zone 2(초록)에 오래 머물수록 유산소 기초 체력 훈련이 잘 된 거예요.",
            "개인 Zone 2 상단 학습" to "말하기 테스트로 그동안 배운 '내 Zone 2 상한'이 어디로 수렴했는지 보여줘요. 세션이 쌓일수록 남의 공식이 아니라 내 몸에 맞는 경계로 또렷해져요.",
            "유산소 분석" to "효율(EF)과 심혈관 드리프트를 봐요.\n\n- 효율(EF) = 심박 1회당 나아간 거리. 높을수록 같은 심박으로 더 빨리 간다는 뜻(유산소 개선). 코칭계에서 널리 쓰는 개념이라, 남과 비교가 아니라 내 세션끼리 추세로 봐요.\n- 심혈관 드리프트 = 후반에 같은 페이스인데 심박이 얼마나 더 올랐나(%). 낮을수록 좋고, 크면 피로/더위/탈수 신호예요.",
            "관측 분석 지표" to "이번 세션에서 관측만으로 뽑은 파생 지표들이에요(드리프트 기울기/서브맥시멀 심박/심박 회복/케이던스 안정성 등). 미래를 예측한 게 아니라, 실제로 잰 값을 다각도로 분석한 근거 있는 숫자예요.",
            "이전 세션 대비" to "직전 세션과 비교해 무엇이 나아지고 나빠졌는지 봐요. 페이스에 덜 휘둘리는 지표(효율/드리프트/Zone2 비율/서브맥시멀 심박)로 공정하게 비교해요. 조금의 차이는 '비슷'으로 봐요.",
            "오늘 컨디션" to "오늘 효율(EF)을 내 평소(직전 몇 세션의 중앙값)와 비교해요. 평소보다 높으면 컨디션 좋은 날, 낮으면 피로/더위/수면부족 신호일 수 있어요.",
            "기간 요약" to "최근 7일/30일 동안의 세션 수, Zone 2 시간, 총거리, 평균 효율을 모아 보여줘요. 꾸준함을 한눈에 보는 카드예요.",
            "초과 원인 분해" to "Zone 2를 넘어선 시간이 왜 생겼는지 쪼개요 — 오르막 때문인지, 페이스가 빨라서인지, 후반 드리프트인지. 오르막이 원인이면 '오르막 전에 미리 늦추기'를 권해요.",
            "효율 곡선" to "심박과 페이스의 관계를 점으로 흩뿌려, 이번 세션과 직전 세션을 겹쳐 봐요. 같은 심박에서 더 빠른 쪽(점이 위/왼쪽)이 더 효율적인 날이에요.",
            "세션 추세" to "최근 여러 세션의 효율/서브맥시멀 심박/드리프트/Zone2 비율이 어느 방향으로 가는지 작은 꺾은선(스파크라인)으로 보여줘요. 데이터가 쌓일수록 가치가 커지는 카드예요.",
            "개인 기록" to "지금까지 세션 중 최고 기록들이에요(최고 효율/최장 거리/최장 Zone 2 시간/최저 드리프트/최저 서브맥시멀 심박). 이번에 갱신하면 배지가 떠요.",
            "구간 분석" to "1km마다 평균 심박/페이스/경사보정 페이스(GAP)를 끊어 봐요. GAP = 오르막과 내리막을 감안해 '평지였다면 이 페이스'로 환산한 값이라, 언덕 때문에 느려진 걸 벌주지 않아요.",
            "경사 구간 분해" to "오르막/평지/내리막에서 각각 얼마나 뛰었고 그때 심박이 어땠는지 나눠 봐요. 같은 페이스라도 오르막은 심박 비용이 커요.",
            "워밍업" to "초반에 심박이 안정 강도까지 올라간 과정을 봐요. 너무 급하게 올렸으면(급상승) 다음엔 천천히 올리라고 알려줘요. 심박이 오르지 않은 세션이면 이 카드는 생략돼요.",
            "심박 추이" to "세션 내내 심박이 어떻게 변했는지 선으로 봐요. 초록 밴드가 목표 Zone 2 구간이라, 선이 밴드 안에 오래 머물수록 좋아요.",
            "페이스 추이" to "세션 내내 페이스(1km에 걸린 시간)가 어떻게 변했는지 봐요. 값이 낮을수록 빠른 거예요.",
        )
    }
}
