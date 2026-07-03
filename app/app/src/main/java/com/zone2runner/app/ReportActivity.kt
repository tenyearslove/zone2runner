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
import com.zone2runner.app.domain.ZoneJudgment
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
            val judge = if (r.usedModel) "MLP 판정" else "규칙 판정"
            val coach = if (r.coachSource == "llm") "Gemini Nano 코칭" else "규칙 코칭"
            text = "$src · $judge + 개인화 + $coach"
            textSize = 11f; setTextColor(C_MUTED); setPadding(0, dp(2), 0, dp(12))
        })

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
        col.addView(card("요약", grid))

        // 존 분포
        val bar = ZoneBarView(this).also { it.set(r.belowSec, r.inSec, r.aboveSec) }
        col.addView(card("존 체류 분포", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dp(40)))
            addView(TextView(this@ReportActivity).apply {
                text = "미달 ${sec(r.belowSec)} · 유지 ${sec(r.inSec)} · 초과 ${sec(r.aboveSec)}"
                textSize = 11f; setTextColor(C_MUTED); setPadding(0, dp(6), 0, 0)
            })
        }))

        // 유산소 분석: HR 추이(목표 밴드) + 존 타임라인 + 드리프트/평가
        addAerobicAnalysis(col, r)

        // 개인화 결과
        col.addView(card("개인화 (Bayesian 경계 추정)", TextView(this).apply {
            text = "Zone2 상한 추정: ${(r.uEstStartFrac * 100).toInt()}% → ${(r.uEstEndFrac * 100).toInt()}% HRR\n" +
                "(공식 70% 사전값에서 세션 관측으로 개인 경계로 이동. adr-004/spec-004)"
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

        // Zone2 목표 밴드(bpm): HRR 대비 (uEstEnd-0.10) ~ uEstEnd
        val hrr = (r.maxHrProfile - r.restingHr).coerceAtLeast(1)
        val hi = (r.restingHr + r.uEstEndFrac * hrr).toFloat()
        val lo = (r.restingHr + (r.uEstEndFrac - 0.10) * hrr).toFloat()

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
        val z2min = r.inSec / 60
        return "$base\nZone 2 유지 ${z2min}분(${z2}%), 평균 심박 ${r.avgHr} bpm.$driftNote"
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
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(C_CARD); cornerRadius = dp(16).toFloat(); setStroke(dp(1), C_STROKE)
            }
            setPadding(dp(16), dp(12), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
            addView(TextView(this@ReportActivity).apply { text = title; textSize = 12f; setTextColor(C_MUTED); setPadding(0, 0, 0, dp(8)) })
            addView(content)
        }
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
        val C_BG = Color.parseColor("#0E1116")
        val C_CARD = Color.parseColor("#171B22")
        val C_STROKE = Color.parseColor("#2A2F3A")
        val C_TEXT = Color.parseColor("#E8EAED")
        val C_MUTED = Color.parseColor("#9AA0A6")
        val C_BLUE = Color.parseColor("#5AC8FA")
    }
}
