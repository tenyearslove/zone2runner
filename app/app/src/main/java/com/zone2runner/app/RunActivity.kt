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
import com.zone2runner.app.sensor.LiveRunSource
import com.zone2runner.app.sensor.MockRunSource
import com.zone2runner.app.sensor.RunSource
import com.zone2runner.app.sensor.SimulatedRunSource
import com.zone2runner.app.sensor.WatchHrProvider
import com.zone2runner.app.ui.Palette
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
    private lateinit var adviceView: TextView // 동역학 NN: 목표 페이스 제안 + 60초 예측(spec-014)
    private var promptView: TextView? = null // LLM 프롬프트 노출(시뮬/목 모드만, null=라이브)
    private var simDelayMs = 14L // 시뮬 재생 배속(샘플 간 ms): 14≈×70, 33≈×30, 100=×10, 1000=×1. 재생 중 변경 가능
    private val speedChips = LinkedHashMap<Long, TextView>()
    private var manualMode = false // 시뮬 수동 페이스 모드(페이스 슬라이더 → 심박이 따라옴)
    private var manualPace = 7.0
    private var manualChip: TextView? = null
    private var paceRow: LinearLayout? = null
    private var virtualRunner = com.zone2runner.app.domain.VirtualRunner.DEFAULT // 가상러너(자동 시나리오)
    private var runnerChip: TextView? = null
    private var paceLabel: TextView? = null
    private lateinit var talkRow: LinearLayout
    private lateinit var uEstView: TextView
    private lateinit var startBtn: Button
    private lateinit var subtitle: TextView
    private lateinit var zoneBand: com.zone2runner.app.ui.ZoneBandView
    private lateinit var rangeView: TextView
    private lateinit var slopeView: TextView
    private lateinit var spmView: TextView
    private lateinit var strideView: TextView
    private lateinit var tempView: TextView
    private var profile: com.zone2runner.app.domain.Profile? = null
    private var tempFetched = false

    private var dynamics: com.zone2runner.app.pipeline.HrDynamics? = null
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
        dynamics = runCatching { com.zone2runner.app.pipeline.HrDynamics.fromAssets(this) }.getOrNull()
        // 역치 추정 NN(adr-014)은 adr-016으로 강등 — 개인화는 Bayesian+토크테스트 전담. 로드하지 않음.
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) { tts?.language = java.util.Locale.KOREAN; ttsReady = true }
        }
        setContentView((buildUi()).withSystemBarInsets())
        updateSubtitle()
        // 시작 전에도 목표 구간을 보여준다. 세션 누적 학습값(LearnedZone)이 있으면 그것을, 없으면 공식 prior.
        profile = ProfileStore.load(this)
        val startUFrac = com.zone2runner.app.data.LearnedZone.uFrac(this)
            ?: com.zone2runner.app.domain.Zone2Prior.of(profile!!).uFrac0
        updateZoneUi(-1, startUFrac)
        // 지도 초기 위치: 서울 고정 좌표 대신 마지막 알려진 위치로 즉시 센터링(GPS 새 fix 전에도 근처 표시)
        centerMapOnLastFix()
    }

    /**
     * Zone2 밴드 게이지 + 범위/이탈 텍스트 갱신.
     * hr = 지속 심박(최근 60초 평균) — 마커/텍스트 기준(판정 칩과 동일 기준). instantHr = 순간 심박(참고 틱).
     */
    private fun updateZoneUi(hr: Int, uEstFrac: Double, instantHr: Int = -1) {
        val p = profile ?: return
        val hi = (p.restingHr + uEstFrac * p.hrr).toInt()
        val lo = (p.restingHr + (uEstFrac - com.zone2runner.app.domain.Zone2Prior.BAND) * p.hrr).toInt()
        zoneBand.update(lo, hi, p.maxHr, hr, instantHr)
        when {
            hr <= 0 -> { rangeView.text = "Zone 2 목표 $lo ~ $hi bpm"; rangeView.setTextColor(C_MUTED) }
            hr < lo -> { rangeView.text = "Zone 2 목표 $lo ~ $hi · ${lo - hr} bpm 아래"; rangeView.setTextColor(C_BLUE) }
            hr > hi -> { rangeView.text = "Zone 2 목표 $lo ~ $hi · ${hr - hi} bpm 초과"; rangeView.setTextColor(C_AMBER) }
            else -> { rangeView.text = "Zone 2 목표 $lo ~ $hi · 구간 안"; rangeView.setTextColor(C_ACCENT) }
        }
    }

    private fun updateSubtitle() {
        val m = dynamics?.metrics
        val model = if (dynamics != null)
            "동역학 NN 로드됨 (60초 예측 RMSE ${fmt(m?.get("rmse_bpm_60"))}bpm, 규칙판정+페이스제안)"
        else "동역학 NN 미로드 → 규칙 판정만"
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
            controller.setZoom(18.0) // 러닝 반경이 좁으니 확대 — 경로가 화면을 채우게
            controller.setCenter(GeoPoint(37.5665, 126.9780))
        }
        // 지도는 컴팩트하게(0.6) — 아래 판정 요소 대시보드에 공간을 준다
        root.addView(map, LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.6f))

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

        // Zone 2 밴드 게이지: 목표 구간(bpm) + 현재 심박 위치/이탈 정도. 터치 시 계산 근거 팝업.
        zoneBand = com.zone2runner.app.ui.ZoneBandView(this)
        zoneBand.isClickable = true
        zoneBand.setOnClickListener { showZoneExplanation() }
        dash.addView(zoneBand, mt(2))
        rangeView = TextView(this).apply {
            textSize = 12f; setTextColor(C_ACCENT); isClickable = true
            setOnClickListener { showZoneExplanation() }
        }
        dash.addView(rangeView, mt(2))
        dash.addView(TextView(this).apply {
            text = "ⓘ Zone 2 구간을 탭하면 계산 방식을 설명해드려요"
            textSize = 10f; setTextColor(C_MUTED)
        }, mt(2))
        // 밴드/판정 기준 안내: 큰 숫자=순간 심박, 마커·판정=지속 심박(최근 60초). 순간 스파이크로 색이 튀지 않음.
        dash.addView(TextView(this).apply {
            text = "● 지속 심박(최근 60초, 판정 기준)  |  큰 숫자·얇은 틱 = 순간 심박"
            textSize = 10f; setTextColor(C_MUTED)
        }, mt(2))

        // 동역학 NN 출력(spec-014): Zone2 목표 페이스 제안 + 60초 뒤 예측 심박
        adviceView = TextView(this).apply {
            text = ""; textSize = 13f; setTextColor(C_ACCENT); setTypeface(typeface, Typeface.BOLD)
        }
        dash.addView(adviceView, mt(4))

        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        timeView = metricVal(); distView = metricVal(); paceView = metricVal()
        metrics.addView(metricCol(timeView, "시간"))
        metrics.addView(metricCol(distView, "거리"))
        metrics.addView(metricCol(paceView, "페이스"))
        dash.addView(metrics, mt(8))

        // 실시간 판정 요소(MLP 특징 표시) + 보폭(속도/케이던스 파생) + 기온(참고)
        val factors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        slopeView = metricVal(); spmView = metricVal(); strideView = metricVal(); tempView = metricVal()
        factors.addView(metricCol(slopeView, "경사"))
        factors.addView(metricCol(spmView, "케이던스"))
        factors.addView(metricCol(strideView, "보폭"))
        factors.addView(metricCol(tempView, "기온"))
        dash.addView(factors, mt(6))

        coachView = TextView(this).apply {
            text = "코칭 대기…"; textSize = 14f; setTextColor(C_ACCENT); setPadding(0, dp(10), 0, 0)
        }
        dash.addView(coachView)

        // LLM 프롬프트 투명성(시뮬/목 모드만): 코칭 문장이 어떤 프롬프트/경로에서 나왔는지 노출
        if (mode != MODE_LIVE) {
            promptView = TextView(this).apply {
                text = ""; textSize = 10f; setTextColor(C_MUTED)
                typeface = Typeface.MONOSPACE; setPadding(0, dp(4), 0, 0)
            }
            dash.addView(promptView)
        }

        // 토크 테스트 자가관측(arch/zone2-physiology §6, spec-016): 참값 없는 경계를 무비용으로 보정하는 독립 채널
        dash.addView(TextView(this).apply {
            text = "대화 가능?"; textSize = 12f; setTextColor(C_MUTED)
        }, mt(8))
        // 5단계 척도(강도 오름차순): 아주 편함 → 매우 벅참. 폰 가로폭에 맞춰 균등 배분.
        talkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        talkRow.addView(talkChip("아주편함", com.zone2runner.app.pipeline.TalkState.VERY_COMFORTABLE))
        talkRow.addView(talkChip("편함", com.zone2runner.app.pipeline.TalkState.COMFORTABLE))
        talkRow.addView(talkChip("애매", com.zone2runner.app.pipeline.TalkState.BORDERLINE))
        talkRow.addView(talkChip("벅참", com.zone2runner.app.pipeline.TalkState.HARD))
        talkRow.addView(talkChip("매우벅참", com.zone2runner.app.pipeline.TalkState.VERY_HARD))
        dash.addView(talkRow, mt(4))

        uEstView = TextView(this).apply { textSize = 11f; setTextColor(C_MUTED); setPadding(0, dp(4), 0, 0) }
        dash.addView(uEstView)

        // 시뮬 재생 배속(시뮬 모드만): 재생 중에도 즉시 반영
        if (mode == MODE_SIM) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = "재생 배속"; textSize = 12f; setTextColor(C_MUTED)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            listOf("×70" to 14L, "×30" to 33L, "×10" to 100L, "×1" to 1000L).forEach { (label, ms) ->
                row.addView(speedChip(label, ms))
            }
            dash.addView(row, mt(8))
            highlightSpeed()

            // 수동 페이스 모드: 심박을 직접 정하는 대신 페이스를 정하면 심박이 생리 모델로 따라온다
            val manualRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            manualRow.addView(TextView(this).apply {
                text = "시뮬 입력"; textSize = 12f; setTextColor(C_MUTED)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            manualChip = TextView(this).apply {
                textSize = 12f; gravity = Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT); lp.marginStart = dp(6); layoutParams = lp
                isClickable = true
                setOnClickListener {
                    if (running) {
                        Toast.makeText(this@RunActivity, "시작 전에만 전환할 수 있어요", Toast.LENGTH_SHORT).show()
                    } else {
                        manualMode = !manualMode
                        updateManualUi()
                    }
                }
            }
            manualRow.addView(manualChip)
            dash.addView(manualRow, mt(6))

            // 가상러너 선택(자동 시나리오 모드): 신체/스타일/코칭반응이 다른 프리셋. 폐루프로 개인화 검증.
            val runnerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            runnerRow.addView(TextView(this).apply {
                text = "가상러너"; textSize = 12f; setTextColor(C_MUTED)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            runnerChip = TextView(this).apply {
                textSize = 12f; gravity = Gravity.CENTER; setTextColor(C_TEXT)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT); lp.marginStart = dp(6); layoutParams = lp
                isClickable = true
                background = GradientDrawable().apply { setColor(C_CARD); cornerRadius = dp(14).toFloat(); setStroke(dp(1), C_STROKE) }
                setOnClickListener {
                    if (running) { Toast.makeText(this@RunActivity, "시작 전에만 바꿀 수 있어요", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    showRunnerPicker()
                }
            }
            runnerRow.addView(runnerChip)
            dash.addView(runnerRow, mt(6))
            updateRunnerChip()

            // 페이스 슬라이더(수동 모드만 표시): 3'30"~12'00", 재생 중에도 즉시 반영
            paceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            paceLabel = TextView(this).apply { textSize = 13f; setTextColor(C_TEXT); setTypeface(typeface, Typeface.BOLD) }
            val seek = android.widget.SeekBar(this).apply {
                max = 85 // 3.5 + 0.1*progress → 3.5~12.0 min/km
                progress = ((manualPace - 3.5) * 10).toInt()
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                        manualPace = 3.5 + p * 0.1
                        (source as? com.zone2runner.app.sim.ManualRunSource)?.targetPace = manualPace
                        updatePaceLabel()
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                })
            }
            paceRow!!.addView(seek, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            paceRow!!.addView(paceLabel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
            dash.addView(paceRow, mt(4))
            updateManualUi()
        }

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

    /** Zone 2 계산 근거 팝업 — 규칙 기반 설명(항상) + LLM 자연어 설명(가능 시 비동기 교체). */
    private fun showZoneExplanation() {
        val p = profile ?: return
        val learned = com.zone2runner.app.data.LearnedZone.uFrac(this)
        val nSess = com.zone2runner.app.data.LearnedZone.sessionCount(this)
        val uFrac = learned ?: com.zone2runner.app.domain.Zone2Prior.of(p).uFrac0
        val hrr = p.hrr
        val hi = (p.restingHr + uFrac * hrr).toInt()
        val lo = (p.restingHr + (uFrac - com.zone2runner.app.domain.Zone2Prior.BAND) * hrr).toInt()
        val tanaka = com.zone2runner.app.domain.Profile.tanakaMaxHr(p.age).toInt()
        val loPct = lo * 100 / p.maxHr; val hiPct = hi * 100 / p.maxHr // %최대심박(재보정 기준)
        val src = if (learned != null) "${nSess}회 러닝으로 보정됨 (말하기 테스트/드리프트 → 개인 맞춤)"
                  else "프로필 기반 초기값 (아직 러닝 보정 전)"
        val hrMaxHigh = p.maxHr > tanaka + 8
        val hmaxNote = if (hrMaxHigh)
            "\n\n※ 최대심박이 ${p.maxHr}으로 설정돼 있어요(${p.age}세 표준 추정은 약 ${tanaka}). 실제로 전력질주해서 나온 값이면 맞습니다."
        else ""
        val ruleText = buildString {
            append("현재 Zone 2 목표: $lo ~ $hi bpm  (최대심박의 ${loPct}~${hiPct}%)\n\n")
            append("어떻게 계산됐나\n")
            append("• Zone 2 = 유산소 기초 강도 ≈ 최대심박의 60~70% (San Millan/LT1 기준)\n")
            append("• 최대심박 ${p.maxHr} → 상단 ≈ 70% = $hi, 하단 ≈ ${loPct}% = $lo\n")
            append("• 지금 값 출처: $src\n")
            append("• ★ 뛰면서 '편함/애매/벅참'을 누르면 이 범위가 당신 몸에 맞게 이동합니다. 편한데 미달로 나오면 '편함'을 누르세요 — 다음 세션부터 내려갑니다.")
            append(hmaxNote)
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Zone 2가 왜 이 범위인가요?")
            .setMessage(ruleText)
            .setPositiveButton("확인", null)
            .show()
        // LLM으로 더 쉬운 설명(가능 시 교체)
        val c = coach ?: LlmCoach(this)
        lifecycleScope.launch {
            val prompt = "러닝 코치입니다. 사용자의 Zone 2 심박 구간이 $lo~$hi bpm(최대심박 ${p.maxHr}의 ${loPct}~${hiPct}%)으로 계산됐습니다. " +
                "Zone 2는 최대심박 60~70%의 유산소 기초 강도입니다. 지금 값 출처: $src. " +
                "왜 이 범위인지, 그리고 실제로 뛰며 편함/애매/벅참 버튼을 누르면 개인에 맞게 보정된다는 점을 3~4문장으로 쉽게 설명하세요. 따옴표/이모지 없이."
            val llm = c.freeform(prompt)
            if (llm != null && dialog.isShowing) {
                dialog.setMessage(llm + "\n\n─ 계산 근거 ─\n" + ruleText)
            }
        }
    }

    /** 현재 위치 마커(파란 점 + 흰 테두리). 경로선과 별개로 "내가 지금 어디"를 표시. */
    private var posMarker: org.osmdroid.views.overlay.Marker? = null
    private fun ensurePosMarker(): org.osmdroid.views.overlay.Marker {
        posMarker?.let { if (map.overlays.contains(it)) return it }
        val m = org.osmdroid.views.overlay.Marker(map).apply {
            setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
            icon = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4C8DF6"))
                setStroke(dp(3), Color.WHITE)
                setSize(dp(18), dp(18))
            }
            infoWindow = null
        }
        map.overlays.add(m)
        posMarker = m
        return m
    }

    private fun updatePosMarker(lat: Double, lon: Double) {
        ensurePosMarker().position = GeoPoint(lat, lon)
    }

    /** 마지막 알려진 위치(캐시)로 지도 센터링 — cold GPS라도 화면이 즉시 내 근처를 보여준다. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun centerMapOnLastFix() {
        if (!hasLocationPermission()) return
        runCatching {
            com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
                .lastLocation.addOnSuccessListener { loc ->
                    if (loc != null && !running) {
                        map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
                        updatePosMarker(loc.latitude, loc.longitude)
                        map.invalidate()
                    }
                }
        }
    }

    private fun startRun() {
        if (mode == MODE_LIVE && !hasLocationPermission()) { requestLocationPermission(); return }

        map.overlays.clear()
        posMarker = null // clear로 제거됐으니 다음 갱신 때 재생성
        line = Polyline().apply { outlinePaint.color = C_ACCENT; outlinePaint.strokeWidth = 8f }
        map.overlays.add(line)

        val profile = ProfileStore.load(this).also { this.profile = it }
        tempFetched = false
        val c = LlmCoach(this) // 미가용 기기에선 내부적으로 RuleCoach 폴백
        coach = c
        lifecycleScope.launch { c.prewarm() } // checkStatus+warmup을 첫 코칭 전에 미리
        // coachScope 전달 → 코칭 생성(LLM ~2초)이 샘플 루프/렌더를 멈추지 않음
        val learnedPrior = com.zone2runner.app.data.LearnedZone.uFrac(this) // 세션 누적 학습값(있으면 prior)
        val eng = RunEngine(profile, dynamics, c, coachScope = lifecycleScope, priorUFrac = learnedPrior)
        engine = eng
        startedAt = System.currentTimeMillis()
        frame = 0

        val src: RunSource = when (mode) {
            MODE_LIVE -> LiveRunSource(this, WatchHrProvider(this).also { watchProvider = it })
            MODE_MOCK -> MockRunSource(MockConfigStore.load(this), seed = System.nanoTime())
            else ->
                if (manualMode) com.zone2runner.app.sim.ManualRunSource(profile, manualPace, delayMs = simDelayMs, seed = System.nanoTime())
                else com.zone2runner.app.sim.SimRunnerSource(
                    virtualRunner, delayMs = simDelayMs, seed = System.nanoTime(),
                    onTalkTest = { st ->
                        runOnUiThread {
                            eng.observeTalkTest(st)
                            logger?.event("talktest") { put("t", (System.currentTimeMillis() - startedAt) / 1000); put("state", st.name); put("src", "virtual") }
                        }
                    })
        }
        source = src

        // 필드 로그(spec-012): 원시 입력+파이프라인 출력을 1Hz JSONL로 기록(adb pull로 회수)
        val log = RunLogger(this)
        logger = log
        log.meta(mode) {
            put("profile", org.json.JSONObject()
                .put("age", profile.age).put("rhr", profile.restingHr).put("maxHr", profile.maxHr))
            put("model", org.json.JSONObject()
                .put("loaded", dynamics != null)
                .put("rmse_bpm_60", dynamics?.metrics?.get("rmse_bpm_60") ?: -1.0))
        }
        eng.onCoachingRecorded = { tSec, lineText, tookMs ->
            log.event("coach") { put("t", tSec); put("text", lineText); put("tookMs", tookMs) }
            // 프롬프트 투명성(시뮬/목): 이 문장이 나온 프롬프트와 경로(llm/폴백 사유) 표시
            promptView?.text = c.lastPrompt?.let { p ->
                "경로 ${c.lastPath} · ${tookMs}ms\n프롬프트: $p"
            } ?: ""
        }

        running = true; finished = false
        isRunning = true; remoteStopRequested = false
        registerTalkListener() // 워치 토크테스트 답변(/talk)은 모든 모드에서 수신(시뮬 미러 포함)
        // 실센서면 워치가 자기 센서로 러닝, 시뮬/목이면 워치를 미러 모드로(폰 심박 표시+토크테스트)
        when (mode) {
            MODE_LIVE -> RunLink.send(this, RunLink.PATH_START)
            else -> RunLink.send(this, RunLink.PATH_MIRROR)
        }
        // 러닝 중 화면 유지: LLM 코칭은 포그라운드 전용(adr-007), GPS/파이프라인도 화면off 스로틀 회피
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startBtn.text = primaryLabel()

        src.start(lifecycleScope, onSample = sample@{ s ->
            if (remoteStopRequested) { finalizeSession(); return@sample } // 워치에서 종료
            val state = eng.onSample(s)
            src.onFeedback(state) // 폐루프: 판정을 소스로 되먹임(가상러너가 코칭에 반응)
            log.sample(s, state, watchProvider?.lastAgeMs() ?: -1L)
            // 시뮬/목: 심박을 워치로 스트림(미러) — 워치에서 표시+토크테스트 가능
            if (mode != MODE_LIVE && state.hr > 0 && frame % 25 == 0) RunLink.sendMirrorHr(this, state.hr)
            if (!tempFetched && s.lat.isFinite() && s.lon.isFinite()) { // 기온 1회 조회(유효 좌표 확보 후)
                tempFetched = true
                lifecycleScope.launch {
                    com.zone2runner.app.data.WeatherProbe.currentTempC(s.lat, s.lon)?.let {
                        tempView.text = "%.0f℃".format(it)
                    }
                }
            }
            val hasCoord = s.lat.isFinite() && s.lon.isFinite()
            if (hasCoord) {
                line?.addPoint(GeoPoint(s.lat, s.lon)) // GPS 미확보(NaN) 전엔 그리지 않음
                updatePosMarker(s.lat, s.lon)          // 현재 위치 점
            }
            // 배속이 느리면 매 샘플 렌더(저배속에서 5샘플 스킵 = 수 초간 화면 정지로 보임)
            val renderEvery = if (src.realtime || simDelayMs >= 100L) 1 else 5
            if (frame % renderEvery == 0) {
                render(state)
                if (hasCoord) map.controller.setCenter(GeoPoint(s.lat, s.lon))
                map.invalidate()
            }
            frame++
        }, onComplete = {
            finalizeSession()
        })
    }

    /** 워치 토크테스트 답변(/talk/<state>) 수신 → 개인화 경계에 반영(watch=프롬프트, phone=brain). */
    private var talkListener: com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener? = null
    private fun registerTalkListener() {
        if (talkListener != null) return
        val l = com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener { e ->
            if (e.path.startsWith("/talk/")) {
                val st = when (e.path.substringAfterLast('/')) {
                    "comfortable" -> com.zone2runner.app.pipeline.TalkState.COMFORTABLE
                    "hard" -> com.zone2runner.app.pipeline.TalkState.HARD
                    else -> com.zone2runner.app.pipeline.TalkState.BORDERLINE
                }
                runOnUiThread {
                    engine?.observeTalkTest(st)
                    logger?.event("talktest") { put("t", (System.currentTimeMillis() - startedAt) / 1000); put("state", st.name); put("src", "watch") }
                    Toast.makeText(this, "워치 응답 반영: ${st.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        runCatching { com.google.android.gms.wearable.Wearable.getMessageClient(this).addListener(l) }
        talkListener = l
    }
    private fun unregisterTalkListener() {
        talkListener?.let { runCatching { com.google.android.gms.wearable.Wearable.getMessageClient(this).removeListener(it) } }
        talkListener = null
    }

    private fun finalizeSession() {
        if (!running) return
        running = false
        isRunning = false
        unregisterTalkListener()
        // 러닝 종료면 워치도 종료(실센서 자동기동/시뮬 미러 모두). 원격 종료가 아닐 때만 되쏨.
        if (!remoteStopRequested) RunLink.send(this, RunLink.PATH_STOP)
        remoteStopRequested = false
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        source?.stop()
        val eng = engine ?: run { logger?.close(); logger = null; return }
        // 세션 종료 시 개인 Zone2 경계 누적(adr-016): 개인화는 온라인 Bayesian이 전담한다.
        // 토크테스트(정답에 가장 가까운 라벨) + 디커플링으로 세션 중 갱신된 '최종 경계'를 저장 →
        // 다음 세션이 여기서 시작 → 실주행 말하기 테스트가 세션을 넘어 누적/수렴.
        // (NN은 심박 예측 전담 — 개인화 경계엔 관여하지 않음. 역치 추정 NN은 adr-014→adr-016으로 강등)
        val finalU = eng.currentUFrac()
        com.zone2runner.app.data.LearnedZone.set(this, finalU)
        logger?.event("boundary") {
            put("uFrac", finalU); put("talk", eng.talkObserved)
            put("n", com.zone2runner.app.data.LearnedZone.sessionCount(this@RunActivity))
        }
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
        render(LiveState(elapsedSec = report.durationSec, hr = report.avgHr, smoothedHr = report.avgHr,
            judgment = null, paceMinKm = report.avgPaceMinKm, distanceM = report.distanceM,
            coaching = "세션 종료 · 저장됨", uEstFrac = report.uEstEndFrac))
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
        updateZoneUi(if (s.smoothedHr > 0) s.smoothedHr else s.hr, s.uEstFrac, s.hr)
        timeView.text = "%02d:%02d".format(s.elapsedSec / 60, s.elapsedSec % 60)
        distView.text = if (s.distanceM < 1000) "${s.distanceM.toInt()}m" else "%.2fkm".format(s.distanceM / 1000)
        // 움직이기 전(정지/GPS 미확보)엔 페이스/케이던스/보폭을 근거 없이 표시하지 않는다("--").
        // paceMinKm 20은 LiveRunSource의 정지 sentinel이라 유효 범위에서 제외.
        val moving = s.speedKmh > 0.8 && s.paceMinKm in 0.1..19.5
        paceView.text = if (moving) "%d'%02d\"".format(s.paceMinKm.toInt(), ((s.paceMinKm % 1) * 60).toInt()) else "--"

        // 실시간 판정 요소(MLP 입력 특징)
        when {
            s.slopePct > 2 -> { slopeView.text = "↑%.1f%%".format(s.slopePct); slopeView.setTextColor(C_AMBER) }
            s.slopePct < -2 -> { slopeView.text = "↓%.1f%%".format(-s.slopePct); slopeView.setTextColor(C_BLUE) }
            else -> { slopeView.text = "평지"; slopeView.setTextColor(C_TEXT) }
        }
        spmView.text = if (moving && s.spm > 0) "${s.spm}" else "--"
        spmView.setTextColor(if (moving && s.spm in 1..161) C_AMBER else C_TEXT) // 저케이던스 경고(부상 예방, spec-005 근거)
        // 보폭(m) = 속도(m/min) / 케이던스 = 1000/(pace*spm)
        strideView.text = if (moving && s.spm > 0)
            "%.2fm".format(1000.0 / (s.paceMinKm * s.spm)) else "--"
        if (s.coaching.isNotBlank()) {
            coachView.text = "🗣 ${s.coaching}"
            if (s.coaching != lastSpoken) { lastSpoken = s.coaching; speak(s.coaching) }
        }
        // 개인 상단을 실제 심박(bpm)으로 표시 — 내부 비율/설계 용어 노출 안 함
        profile?.let { pr ->
            val upBpm = (pr.restingHr + s.uEstFrac * pr.hrr).toInt()
            uEstView.text = "개인 Zone 2 상단: $upBpm bpm (러닝하며 보정 중)"
        }

        // 동역학 NN 출력: 목표 페이스 제안 + 60초 예측(워밍업 완료 후)
        if (s.recommendedPaceMinKm > 0.0 && s.predictedHr60 > 0) {
            val rp = s.recommendedPaceMinKm
            val paceTxt = "%d'%02d\"".format(rp.toInt(), ((rp % 1) * 60).toInt())
            adviceView.text = "🎯 Zone2 페이스 $paceTxt · 이대로면 60초 뒤 ~${s.predictedHr60} bpm"
        } else if (running) {
            adviceView.text = when {
                s.hr <= 0 -> "심박 신호 대기 중 (워치 연결 확인)"
                !moving -> "움직이면 페이스를 제안해요"
                else -> "페이스 제안 준비 중 (워밍업 ~2분)"
            }
        }
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
        if (hasLocationPermission()) { centerMapOnLastFix(); startRun() }
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
    private fun talkChip(label: String, state: com.zone2runner.app.pipeline.TalkState) = TextView(this).apply {
        text = label; textSize = 11f; setTextColor(C_TEXT); gravity = Gravity.CENTER
        setPadding(dp(4), dp(6), dp(4), dp(6))
        background = GradientDrawable().apply { setColor(C_CARD); cornerRadius = dp(14).toFloat(); setStroke(dp(1), C_STROKE) }
        // 5칩 균등 배분(weight 1): 폰 가로폭에서 라벨이 잘리지 않게 padding/textSize 축소.
        val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f); lp.marginStart = dp(4); layoutParams = lp
        isClickable = true
        setOnClickListener {
            val eng = engine
            if (!running || eng == null) {
                Toast.makeText(this@RunActivity, "러닝 중에만 기록됩니다", Toast.LENGTH_SHORT).show()
            } else {
                eng.observeTalkTest(state)
                logger?.event("talktest") { put("t", (System.currentTimeMillis() - startedAt) / 1000); put("state", state.name) }
                Toast.makeText(this@RunActivity, "기록됨 · 개인 경계에 반영", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 시뮬 재생 배속 칩. 재생 중이면 소스에 즉시 반영(@Volatile delayMs). */
    private fun speedChip(label: String, ms: Long) = TextView(this).apply {
        text = label; textSize = 12f; setTextColor(C_TEXT); gravity = Gravity.CENTER
        setPadding(dp(14), dp(6), dp(14), dp(6))
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT); lp.marginStart = dp(6); layoutParams = lp
        isClickable = true
        speedChips[ms] = this
        setOnClickListener {
            simDelayMs = ms
            (source as? SimulatedRunSource)?.delayMs = ms
            (source as? com.zone2runner.app.sim.ManualRunSource)?.delayMs = ms
            (source as? com.zone2runner.app.sim.SimRunnerSource)?.delayMs = ms
            highlightSpeed()
        }
    }

    private fun updateRunnerChip() {
        runnerChip?.text = virtualRunner.name
        // 수동 모드에선 가상러너 무의미 → 흐리게
        runnerChip?.alpha = if (manualMode) 0.4f else 1f
    }

    /** 가상러너 선택 — 카드형(현재 선택 강조 + 특성 표시). 텍스트 목록보다 선택 화면임이 명확. */
    private fun showRunnerPicker() {
        val presets = com.zone2runner.app.domain.VirtualRunner.PRESETS
        val dialog = android.app.AlertDialog.Builder(this).create()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        col.addView(TextView(this).apply {
            text = "가상러너 선택 — 탭하면 선택됩니다"; textSize = 12f; setTextColor(C_MUTED); setPadding(0, dp(4), 0, dp(10))
        })
        for (r in presets) {
            val selected = r.name == virtualRunner.name
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(if (selected) C_ACCENT_DIM else C_CARD)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(if (selected) 2 else 1), if (selected) C_ACCENT else C_STROKE)
                }
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) }
                isClickable = true
                addView(TextView(this@RunActivity).apply {
                    text = (if (selected) "✓ " else "") + r.name + (if (selected) "  (현재)" else "")
                    textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (selected) Color.WHITE else C_TEXT)
                })
                addView(TextView(this@RunActivity).apply {
                    text = "나이 ${r.age} · 안정 ${r.restingHr} · 최대 ${r.maxHr}bpm · 진짜임계 ${(r.trueZone2UpperHrmaxFrac * 100).toInt()}%\n" +
                        "페이스 규율 ${(r.pacingDiscipline * 100).toInt()}% · 코칭 반응 ${(r.coachingResponsiveness * 100).toInt()}%"
                    textSize = 11f; setTextColor(if (selected) Color.WHITE else C_MUTED); setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener { virtualRunner = r; updateRunnerChip(); dialog.dismiss() }
            }
            col.addView(card)
        }
        dialog.setView(android.widget.ScrollView(this).apply { addView(col) })
        dialog.show()
    }

    private fun updateManualUi() {
        manualChip?.apply {
            text = if (manualMode) "수동 페이스" else "자동 시나리오"
            setTextColor(if (manualMode) Color.WHITE else C_TEXT)
            background = GradientDrawable().apply {
                setColor(if (manualMode) C_ACCENT_DIM else C_CARD)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), if (manualMode) C_ACCENT else C_STROKE)
            }
        }
        paceRow?.visibility = if (manualMode) android.view.View.VISIBLE else android.view.View.GONE
        updatePaceLabel()
        updateRunnerChip()
    }

    private fun updatePaceLabel() {
        paceLabel?.text = "목표 %d'%02d\"".format(manualPace.toInt(), ((manualPace % 1) * 60).toInt())
    }

    private fun highlightSpeed() {
        speedChips.forEach { (ms, v) ->
            val sel = ms == simDelayMs
            v.setTextColor(if (sel) Color.WHITE else C_TEXT)
            v.background = GradientDrawable().apply {
                setColor(if (sel) C_ACCENT_DIM else C_CARD)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), if (sel) C_ACCENT else C_STROKE)
            }
        }
    }

    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
    private fun mt(v: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(v) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmt(d: Double?) = if (d == null) "-" else "%.2f".format(d)

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
    override fun onDestroy() {
        super.onDestroy(); source?.stop()
        isRunning = false
        unregisterTalkListener()
        logger?.close(); logger = null // 중도 이탈 시에도 로그 파일 마감
        tts?.stop(); tts?.shutdown()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SIM = "sim"
        const val MODE_LIVE = "live"
        const val MODE_MOCK = "mock"
        // 워치 원격 제어 연동(RunControlService)용 프로세스 전역 상태
        @Volatile var isRunning = false          // 러닝 중이면 워치발 /run/start 중복 실행 방지
        @Volatile var remoteStopRequested = false // 워치에서 종료 → 폰 러닝 루프가 감지해 종료
        // 전역 팔레트(ui.Palette) 참조 — 단일 소스. 로컬 전용 색만 별도 정의.
        private val C_BG = Palette.BG
        private val C_TEXT = Palette.TEXT
        private val C_MUTED = Palette.MUTED
        private val C_ACCENT = Palette.ACCENT
        private val C_ACCENT_DIM = Color.parseColor("#1E7A38") // 배속/선택 칩 배경(팔레트 외 로컬)
        private val C_BLUE = Palette.BLUE
        private val C_AMBER = Palette.AMBER
        private val C_CARD = Palette.CARD
        private val C_STROKE = Palette.STROKE
    }
}
