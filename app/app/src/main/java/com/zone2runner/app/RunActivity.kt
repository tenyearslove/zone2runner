package com.zone2runner.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zone2runner.app.coaching.RuleCoach
import com.zone2runner.app.data.ProfileStore
import com.zone2runner.app.data.SessionStore
import com.zone2runner.app.domain.LiveState
import com.zone2runner.app.pipeline.RunEngine
import com.zone2runner.app.pipeline.Zone2Classifier
import com.zone2runner.app.sim.RunSimulator
import com.zone2runner.app.ui.ReportHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/**
 * 러닝(라이브) — 시뮬레이터가 만든 세션을 가속 재생하며 전체 파이프라인을 구동한다.
 * [HrSource → 이상치가드 → 특징 → MLP판정 → 개인화 → 코칭 → 세션] 전 구간.
 * 종료 시 RunReport를 SessionStore에 저장하고 리포트로 이동. 실센서/실 GPS 연동은 후속(Phase D).
 */
class RunActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var hrView: TextView
    private lateinit var zoneChip: TextView
    private lateinit var timeView: TextView
    private lateinit var distView: TextView
    private lateinit var paceView: TextView
    private lateinit var coachView: TextView
    private lateinit var uEstView: TextView
    private lateinit var startBtn: Button
    private lateinit var subtitle: TextView

    private var classifier: Zone2Classifier? = null
    private var job: Job? = null
    private var line: Polyline? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        classifier = runCatching { Zone2Classifier.fromAssets(this) }.getOrNull()
        setContentView(buildUi())
        val m = classifier?.metrics
        subtitle.text = if (classifier != null)
            "MLP 로드됨 (개인화 acc ${fmt(m?.get("mlp_acc"))}, QA1 ${fmt(m?.get("qa1_coaching_direction"))}) · 시뮬레이션 재생"
        else "MLP 미로드 → 규칙 폴백 · 시뮬레이션 재생"
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C_BG) }

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(37.5665, 126.9780))
        }
        root.addView(map, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.3f))

        val dash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        subtitle = TextView(this).apply { textSize = 11f; setTextColor(C_MUTED) }
        dash.addView(subtitle)

        val hrRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hrView = TextView(this).apply {
            text = "-- bpm"; textSize = 34f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(C_TEXT)
        }
        zoneChip = TextView(this).apply {
            text = "대기"; textSize = 13f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(5), dp(12), dp(5)); background = pill(C_MUTED)
        }
        hrRow.addView(hrView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        hrRow.addView(zoneChip)
        dash.addView(hrRow, mt(4))

        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        timeView = metricVal(); distView = metricVal(); paceView = metricVal()
        metrics.addView(metricCol(timeView, "시간"))
        metrics.addView(metricCol(distView, "거리"))
        metrics.addView(metricCol(paceView, "페이스"))
        dash.addView(metrics, mt(10))

        coachView = TextView(this).apply {
            text = "코칭 대기…"; textSize = 14f; setTextColor(C_ACCENT); setPadding(0, dp(10), 0, 0)
        }
        dash.addView(coachView)

        uEstView = TextView(this).apply { textSize = 11f; setTextColor(C_MUTED); setPadding(0, dp(4), 0, 0) }
        dash.addView(uEstView)

        startBtn = Button(this).apply {
            text = "파이프라인 시뮬레이션 시작"
            setOnClickListener { onPrimary() }
        }
        dash.addView(startBtn, mt(8))

        root.addView(dash, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return root
    }

    private fun onPrimary() {
        if (finished) { openReport(); return }
        if (job?.isActive == true) { job?.cancel(); startBtn.text = "파이프라인 시뮬레이션 시작"; return }
        startReplay()
    }

    private fun openReport() = startActivity(Intent(this, ReportActivity::class.java))

    private fun startReplay() {
        startBtn.text = "정지"
        map.overlays.clear()
        line = Polyline().apply { outlinePaint.color = C_ACCENT; outlinePaint.strokeWidth = 8f }
        map.overlays.add(line)

        val sim = RunSimulator(seed = System.nanoTime())
        val session = sim.generate(durationMin = 30)
        val profile = ProfileStore.load(this)
        val engine = RunEngine(profile, classifier, RuleCoach()).also { it.coachSource = "rule" }
        val startedAt = System.currentTimeMillis()

        job = lifecycleScope.launch {
            var i = 0
            for (s in session.samples) {
                if (!isActive) return@launch
                val state = engine.onSample(s)
                line?.addPoint(GeoPoint(s.lat, s.lon))
                if (i % 5 == 0) {
                    render(state)
                    map.controller.setCenter(GeoPoint(s.lat, s.lon))
                    map.invalidate()
                }
                i++
                delay(14) // 가속 재생(≈70x)
            }
            val report = engine.report().copy(startedAtEpochMs = startedAt, sourceMode = "sim")
            ReportHolder.last = SessionStore.save(this@RunActivity, report)
            render(LiveState(report.durationSec, report.avgHr, null, report.avgPaceMinKm, 0.0, report.distanceM, "세션 종료 · 저장됨", report.uEstEndFrac))
            finished = true
            startBtn.text = "리포트 보기"
        }
    }

    private fun render(s: LiveState) {
        hrView.text = if (s.hr > 0) "${s.hr} bpm" else "-- bpm"
        val j = s.judgment
        if (j != null) {
            zoneChip.text = j.label; zoneChip.background = pill(j.color); hrView.setTextColor(j.color)
        }
        timeView.text = "%02d:%02d".format(s.elapsedSec / 60, s.elapsedSec % 60)
        distView.text = if (s.distanceM < 1000) "${s.distanceM.toInt()}m" else "%.2fkm".format(s.distanceM / 1000)
        paceView.text = if (s.paceMinKm in 0.1..30.0) "%d'%02d\"".format(s.paceMinKm.toInt(), ((s.paceMinKm % 1) * 60).toInt()) else "--"
        if (s.coaching.isNotBlank()) coachView.text = "🗣 ${s.coaching}"
        uEstView.text = "개인 Zone2 상한 추정: ${(s.uEstFrac * 100).toInt()}% HRR (개인화 갱신 중)"
    }

    private fun metricVal() = TextView(this).apply {
        text = "--"; textSize = 17f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(C_TEXT); gravity = Gravity.CENTER
    }
    private fun metricCol(v: TextView, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        addView(v)
        addView(TextView(this@RunActivity).apply { text = label; textSize = 10f; setTextColor(C_MUTED); gravity = Gravity.CENTER })
    }
    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
    private fun mt(v: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(v) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmt(d: Double?) = if (d == null) "-" else "%.2f".format(d)

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }

    private companion object {
        val C_BG = Color.parseColor("#0E1116")
        val C_TEXT = Color.parseColor("#E8EAED")
        val C_MUTED = Color.parseColor("#9AA0A6")
        val C_ACCENT = Color.parseColor("#30D158")
    }
}
