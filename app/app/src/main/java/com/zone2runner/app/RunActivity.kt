package com.zone2runner.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zone2runner.app.coaching.LlmCoach
import com.zone2runner.app.data.ProfileStore
import com.zone2runner.app.data.SessionStore
import com.zone2runner.app.domain.LiveState
import com.zone2runner.app.pipeline.RunEngine
import com.zone2runner.app.pipeline.Zone2Classifier
import com.zone2runner.app.sensor.LiveRunSource
import com.zone2runner.app.sensor.RunSource
import com.zone2runner.app.sensor.SimulatedRunSource
import com.zone2runner.app.sensor.WatchHrProvider
import com.zone2runner.app.ui.ReportHolder
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/**
 * 러닝(라이브) — 입력 소스(RunSource)를 갈아끼워 전체 파이프라인을 구동한다.
 *   sim  = 물리 시뮬레이터 가속 재생(실기기 없이 데모).
 *   live = 실 GPS(FusedLocation) + 워치 심박(Data Layer). 위치 권한 필요.
 * [소스 → 이상치가드 → 특징 → MLP판정 → 개인화 → 코칭 → 세션]. 종료 시 SessionStore 저장 후 리포트.
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
    private var source: RunSource? = null
    private var engine: RunEngine? = null
    private var coach: LlmCoach? = null
    private var line: Polyline? = null
    private var running = false
    private var finished = false
    private var startedAt = 0L
    private var frame = 0
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpoken = ""

    private val mode: String by lazy { intent.getStringExtra(EXTRA_MODE) ?: MODE_SIM }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        classifier = runCatching { Zone2Classifier.fromAssets(this) }.getOrNull()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) { tts?.language = java.util.Locale.KOREAN; ttsReady = true }
        }
        setContentView(buildUi())
        updateSubtitle()
    }

    private fun updateSubtitle() {
        val m = classifier?.metrics
        val model = if (classifier != null)
            "MLP 로드됨 (개인화 acc ${fmt(m?.get("mlp_acc"))}, QA1 ${fmt(m?.get("qa1_coaching_direction"))})"
        else "MLP 미로드 → 규칙 폴백"
        subtitle.text = if (mode == MODE_LIVE)
            "$model · 실센서(GPS+워치HR) 모드 — 실기기 필요"
        else "$model · 시뮬레이션 재생"
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
            text = primaryLabel()
            setOnClickListener { onPrimary() }
        }
        dash.addView(startBtn, mt(8))

        root.addView(dash, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return root
    }

    private fun primaryLabel(): String = when {
        finished -> "리포트 보기"
        running -> "정지 · 저장"
        mode == MODE_LIVE -> "실센서 러닝 시작"
        else -> "파이프라인 시뮬레이션 시작"
    }

    private fun onPrimary() {
        when {
            finished -> startActivity(Intent(this, ReportActivity::class.java))
            running -> finalizeSession()
            else -> startRun()
        }
    }

    private fun startRun() {
        if (mode == MODE_LIVE && !hasLocationPermission()) { requestLocationPermission(); return }

        map.overlays.clear()
        line = Polyline().apply { outlinePaint.color = C_ACCENT; outlinePaint.strokeWidth = 8f }
        map.overlays.add(line)

        val profile = ProfileStore.load(this)
        val c = LlmCoach(this) // 미가용 기기에선 내부적으로 RuleCoach 폴백
        coach = c
        val eng = RunEngine(profile, classifier, c)
        engine = eng
        startedAt = System.currentTimeMillis()
        frame = 0

        val src: RunSource = if (mode == MODE_LIVE)
            LiveRunSource(this, WatchHrProvider(this))
        else
            SimulatedRunSource(durationMin = 30, seed = System.nanoTime())
        source = src
        val renderEvery = if (src.realtime) 1 else 5

        running = true; finished = false
        startBtn.text = primaryLabel()

        src.start(lifecycleScope, onSample = { s ->
            val state = eng.onSample(s)
            line?.addPoint(GeoPoint(s.lat, s.lon))
            if (frame % renderEvery == 0) {
                render(state)
                map.controller.setCenter(GeoPoint(s.lat, s.lon))
                map.invalidate()
            }
            frame++
        }, onComplete = {
            finalizeSession()
        })
    }

    private fun finalizeSession() {
        if (!running) return
        running = false
        source?.stop()
        val eng = engine ?: return
        val report = eng.report().copy(
            startedAtEpochMs = startedAt, sourceMode = mode,
            coachSource = coach?.sessionSource() ?: "rule",
        )
        if (report.durationSec < 5) { // 데이터 너무 적으면 저장 생략
            Toast.makeText(this, "기록이 너무 짧아 저장하지 않았어요", Toast.LENGTH_SHORT).show()
            finished = false; startBtn.text = primaryLabel(); return
        }
        ReportHolder.last = SessionStore.save(this, report)
        render(LiveState(report.durationSec, report.avgHr, null, report.avgPaceMinKm, 0.0, report.distanceM, "세션 종료 · 저장됨", report.uEstEndFrac))
        finished = true
        startBtn.text = primaryLabel()
        Toast.makeText(this, "세션 저장됨", Toast.LENGTH_SHORT).show()
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
        if (s.coaching.isNotBlank()) {
            coachView.text = "🗣 ${s.coaching}"
            if (s.coaching != lastSpoken) { lastSpoken = s.coaching; speak(s.coaching) }
        }
        uEstView.text = "개인 Zone2 상한 추정: ${(s.uEstFrac * 100).toInt()}% HRR (개인화 갱신 중)"
    }

    /** 코칭 문장을 음성으로(llm-verify에서 검증한 TTS end-to-end). 세션 종료 문구는 제외. */
    private fun speak(text: String) {
        if (!ttsReady || !running || text.contains("세션 종료")) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "coach")
    }

    // ---- 권한 ----
    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BODY_SENSORS), 1)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasLocationPermission()) startRun()
        else Toast.makeText(this, "위치 권한이 필요합니다", Toast.LENGTH_SHORT).show()
    }

    // ---- helpers ----
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
    override fun onDestroy() {
        super.onDestroy(); source?.stop()
        tts?.stop(); tts?.shutdown()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SIM = "sim"
        const val MODE_LIVE = "live"
        private val C_BG = Color.parseColor("#0E1116")
        private val C_TEXT = Color.parseColor("#E8EAED")
        private val C_MUTED = Color.parseColor("#9AA0A6")
        private val C_ACCENT = Color.parseColor("#30D158")
    }
}
