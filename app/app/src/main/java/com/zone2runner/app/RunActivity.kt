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
import com.zone2runner.app.data.MockConfigStore
import com.zone2runner.app.data.ProfileStore
import com.zone2runner.app.data.RunLogger
import com.zone2runner.app.data.SessionStore
import com.zone2runner.app.domain.LiveState
import com.zone2runner.app.pipeline.RunEngine
import com.zone2runner.app.pipeline.Zone2Classifier
import com.zone2runner.app.sensor.LiveRunSource
import com.zone2runner.app.sensor.MockRunSource
import com.zone2runner.app.sensor.RunSource
import com.zone2runner.app.sensor.SimulatedRunSource
import com.zone2runner.app.sensor.WatchHrProvider
import com.zone2runner.app.ui.ReportHolder
import com.zone2runner.app.ui.withSystemBarInsets
import kotlinx.coroutines.launch
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
    private lateinit var zoneBand: com.zone2runner.app.ui.ZoneBandView
    private lateinit var rangeView: TextView
    private lateinit var slopeView: TextView
    private lateinit var spmView: TextView
    private lateinit var strideView: TextView
    private lateinit var driftView: TextView
    private lateinit var tempView: TextView
    private var profile: com.zone2runner.app.domain.Profile? = null
    private var tempFetched = false

    private var classifier: Zone2Classifier? = null
    private var source: RunSource? = null
    private var engine: RunEngine? = null
    private var coach: LlmCoach? = null
    private var logger: RunLogger? = null
    private var watchProvider: WatchHrProvider? = null
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
        setContentView((buildUi()).withSystemBarInsets())
        updateSubtitle()
        // 시작 전에도 프로필 prior 기반 목표 구간을 보여준다(개인화 갱신 시 밴드가 함께 이동)
        profile = ProfileStore.load(this)
        updateZoneUi(-1, com.zone2runner.app.domain.Zone2Prior.of(profile!!).uFrac0)
    }

    /** Zone2 밴드 게이지 + 범위/이탈 텍스트 갱신. */
    private fun updateZoneUi(hr: Int, uEstFrac: Double) {
        val p = profile ?: return
        val hi = (p.restingHr + uEstFrac * p.hrr).toInt()
        val lo = (p.restingHr + (uEstFrac - com.zone2runner.app.domain.Zone2Prior.BAND) * p.hrr).toInt()
        zoneBand.update(lo, hi, p.maxHr, hr)
        when {
            hr <= 0 -> { rangeView.text = "Zone 2 목표 $lo ~ $hi bpm"; rangeView.setTextColor(C_MUTED) }
            hr < lo -> { rangeView.text = "Zone 2 목표 $lo ~ $hi · ${lo - hr} bpm 아래"; rangeView.setTextColor(C_BLUE) }
            hr > hi -> { rangeView.text = "Zone 2 목표 $lo ~ $hi · ${hr - hi} bpm 초과"; rangeView.setTextColor(C_AMBER) }
            else -> { rangeView.text = "Zone 2 목표 $lo ~ $hi · 구간 안"; rangeView.setTextColor(C_ACCENT) }
        }
    }

    private fun updateSubtitle() {
        val m = classifier?.metrics
        val model = if (classifier != null)
            "MLP 로드됨 (개인화 acc ${fmt(m?.get("mlp_acc"))}, QA1 ${fmt(m?.get("qa1_coaching_direction"))})"
        else "MLP 미로드 → 규칙 폴백"
        subtitle.text = when (mode) {
            MODE_LIVE -> "$model · 실센서(GPS+워치HR) — 실기기 필요"
            MODE_MOCK -> "$model · 가짜 라이브(테스트) — 워치 없이 실시간 합성"
            else -> "$model · 시뮬레이션 재생"
        }
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C_BG) }

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(37.5665, 126.9780))
        }
        // 지도는 컴팩트하게(0.7) — 아래 판정 요소 대시보드에 공간을 준다
        root.addView(map, LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.7f))

        val dash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
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
        dash.addView(hrRow, mt(2))

        // Zone 2 밴드 게이지: 목표 구간(bpm) + 현재 심박 위치/이탈 정도
        zoneBand = com.zone2runner.app.ui.ZoneBandView(this)
        dash.addView(zoneBand, mt(2))
        rangeView = TextView(this).apply { textSize = 12f; setTextColor(C_ACCENT) }
        dash.addView(rangeView, mt(2))

        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        timeView = metricVal(); distView = metricVal(); paceView = metricVal()
        metrics.addView(metricCol(timeView, "시간"))
        metrics.addView(metricCol(distView, "거리"))
        metrics.addView(metricCol(paceView, "페이스"))
        dash.addView(metrics, mt(8))

        // 실시간 판정 요소(MLP 특징 표시) + 보폭(속도/케이던스 파생) + 기온(참고)
        val factors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        slopeView = metricVal(); spmView = metricVal(); strideView = metricVal(); driftView = metricVal(); tempView = metricVal()
        factors.addView(metricCol(slopeView, "경사"))
        factors.addView(metricCol(spmView, "케이던스"))
        factors.addView(metricCol(strideView, "보폭"))
        factors.addView(metricCol(driftView, "드리프트"))
        factors.addView(metricCol(tempView, "기온"))
        dash.addView(factors, mt(6))

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
        mode == MODE_MOCK -> "가짜 라이브 러닝 시작"
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

        val profile = ProfileStore.load(this).also { this.profile = it }
        tempFetched = false
        val c = LlmCoach(this) // 미가용 기기에선 내부적으로 RuleCoach 폴백
        coach = c
        lifecycleScope.launch { c.prewarm() } // checkStatus+warmup을 첫 코칭 전에 미리
        // coachScope 전달 → 코칭 생성(LLM ~2초)이 샘플 루프/렌더를 멈추지 않음
        val eng = RunEngine(profile, classifier, c, coachScope = lifecycleScope)
        engine = eng
        startedAt = System.currentTimeMillis()
        frame = 0

        val src: RunSource = when (mode) {
            MODE_LIVE -> LiveRunSource(this, WatchHrProvider(this).also { watchProvider = it })
            MODE_MOCK -> MockRunSource(MockConfigStore.load(this), seed = System.nanoTime())
            else -> SimulatedRunSource(durationMin = 30, seed = System.nanoTime(), profile = profile)
        }
        source = src
        val renderEvery = if (src.realtime) 1 else 5

        // 필드 로그(spec-012): 원시 입력+파이프라인 출력을 1Hz JSONL로 기록(adb pull로 회수)
        val log = RunLogger(this)
        logger = log
        log.meta(mode) {
            put("profile", org.json.JSONObject()
                .put("age", profile.age).put("rhr", profile.restingHr).put("maxHr", profile.maxHr))
            put("model", org.json.JSONObject()
                .put("loaded", classifier != null)
                .put("mlp_acc", classifier?.metrics?.get("mlp_acc") ?: -1.0))
        }
        eng.onCoachingRecorded = { tSec, lineText, tookMs ->
            log.event("coach") { put("t", tSec); put("text", lineText); put("tookMs", tookMs) }
        }

        running = true; finished = false
        // 러닝 중 화면 유지: LLM 코칭은 포그라운드 전용(adr-007), GPS/파이프라인도 화면off 스로틀 회피
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startBtn.text = primaryLabel()

        src.start(lifecycleScope, onSample = { s ->
            val state = eng.onSample(s)
            log.sample(s, state, watchProvider?.lastAgeMs() ?: -1L)
            if (!tempFetched) { // 기온 1회 조회(참고 표시 — 판정 특징 아님)
                tempFetched = true
                lifecycleScope.launch {
                    com.zone2runner.app.data.WeatherProbe.currentTempC(s.lat, s.lon)?.let {
                        tempView.text = "%.0f℃".format(it)
                    }
                }
            }
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
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        source?.stop()
        val eng = engine ?: run { logger?.close(); logger = null; return }
        val report = eng.report().copy(
            startedAtEpochMs = startedAt, sourceMode = mode,
            coachSource = coach?.sessionSource() ?: "rule",
        )
        if (report.durationSec < 5) { // 데이터 너무 적으면 저장 생략
            logger?.event("end") { put("saved", false); put("durationSec", report.durationSec) }
            logger?.close(); logger = null
            Toast.makeText(this, "기록이 너무 짧아 저장하지 않았어요", Toast.LENGTH_SHORT).show()
            finished = false; startBtn.text = primaryLabel(); return
        }
        ReportHolder.last = SessionStore.save(this, report)
        logger?.event("end") {
            put("saved", true); put("savedId", ReportHolder.last?.id ?: "")
            put("durationSec", report.durationSec); put("distanceM", report.distanceM.toInt())
            put("zone2Pct", report.zone2Pct); put("coachSource", report.coachSource)
            put("directionRejects", coach?.directionRejects ?: 0)
        }
        logger?.close(); logger = null
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
        updateZoneUi(s.hr, s.uEstFrac)
        timeView.text = "%02d:%02d".format(s.elapsedSec / 60, s.elapsedSec % 60)
        distView.text = if (s.distanceM < 1000) "${s.distanceM.toInt()}m" else "%.2fkm".format(s.distanceM / 1000)
        paceView.text = if (s.paceMinKm in 0.1..30.0) "%d'%02d\"".format(s.paceMinKm.toInt(), ((s.paceMinKm % 1) * 60).toInt()) else "--"

        // 실시간 판정 요소(MLP 입력 특징)
        when {
            s.slopePct > 2 -> { slopeView.text = "↑%.1f%%".format(s.slopePct); slopeView.setTextColor(C_AMBER) }
            s.slopePct < -2 -> { slopeView.text = "↓%.1f%%".format(-s.slopePct); slopeView.setTextColor(C_BLUE) }
            else -> { slopeView.text = "평지"; slopeView.setTextColor(C_TEXT) }
        }
        spmView.text = if (s.spm > 0) "${s.spm}" else "--"
        spmView.setTextColor(if (s.spm in 1..161) C_AMBER else C_TEXT) // 저케이던스 경고(부상 예방, spec-005 근거)
        // 보폭(m) = 속도(m/min) / 케이던스 = 1000/(pace*spm)
        strideView.text = if (s.spm > 0 && s.paceMinKm in 0.1..30.0)
            "%.2fm".format(1000.0 / (s.paceMinKm * s.spm)) else "--"
        val dec = s.decoupling
        if (dec == null) { driftView.text = "--"; driftView.setTextColor(C_MUTED) }
        else {
            driftView.text = "%+.1f%%".format(dec * 100)
            driftView.setTextColor(if (dec > 0.05) C_AMBER else C_TEXT) // 드리프트 커지면 경고색(피로/더위 신호)
        }
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
        logger?.close(); logger = null // 중도 이탈 시에도 로그 파일 마감
        tts?.stop(); tts?.shutdown()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SIM = "sim"
        const val MODE_LIVE = "live"
        const val MODE_MOCK = "mock"
        private val C_BG = Color.parseColor("#0E1116")
        private val C_TEXT = Color.parseColor("#E8EAED")
        private val C_MUTED = Color.parseColor("#9AA0A6")
        private val C_ACCENT = Color.parseColor("#30D158")
        private val C_BLUE = Color.parseColor("#5AC8FA")
        private val C_AMBER = Color.parseColor("#FF9F0A")
    }
}
