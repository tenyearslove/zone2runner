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
import com.zone2runner.app.data.RunLogger
import com.zone2runner.app.data.SessionStore
import com.zone2runner.app.domain.LiveState
import com.zone2runner.app.pipeline.RunEngine
import com.zone2runner.app.sensor.LiveRunSource
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
 * [소스 → 이상치가드 → 규칙판정(ZoneJudge) → 개인화(Bayesian) → 코칭 → 세션]. 종료 시 저장 후 리포트.
 */
class RunActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var hrView: TextView
    private lateinit var zoneChip: TextView
    private lateinit var timeView: TextView
    private lateinit var distView: TextView
    private lateinit var paceView: TextView
    private lateinit var coachView: TextView
    private lateinit var adviceView: TextView // 러닝 조언 표시(추후 분석 엔진용 자리, 현재 미사용)
    private var promptView: TextView? = null // LLM 프롬프트 노출(시뮬/목 모드만, null=라이브)
    private var simDelayMs = 14L // 시뮬 재생 배속(샘플 간 ms): 14≈×70, 33≈×30, 100=×10, 1000=×1. 재생 중 변경 가능
    private val speedChips = LinkedHashMap<Long, TextView>()

    /** 시뮬 입력 모드: 자동(가상러너 시나리오) / 수동 페이스 / 수동 러너(케이던스+보폭, spec-022). */
    private enum class SimInput { AUTO, PACE, RUNNER }
    private var simInput = SimInput.AUTO
    private var manualPace = 7.0
    private var manualChip: TextView? = null
    private var paceRow: LinearLayout? = null
    // 수동 가상러너(spec-022): 신체 스펙 + 조종 슬라이더 + 시계연동
    private var manualBody: com.zone2runner.app.sim.ManualVirtualRunnerSource.Body? = null
    private var manualSpm = 0 // 케이던스 0 = 정지에서 시작(사용자 피드백) — 올리면 달리기 시작
    private var manualStride = 1.00
    private var manualHrOffset = 0
    private var manualSlope = 0.0 // 경사 % — 파이프라인 경사 입력(특징/예측/코칭) 테스트
    private var manualTempC = 20.0 // 기온 ℃ — 더위 코칭 테스트(실측 조회 대신 입력값 주입)
    private var vrRows: LinearLayout? = null
    private var vrSummary: TextView? = null
    // 워치는 항상 페어(실제도 항상 페어 → 시뮬도 동일, 2026-07-08 사용자 결정). 별도 연동 옵션 없음.
    private var talkDialog: android.app.AlertDialog? = null
    private var virtualRunner = com.zone2runner.app.domain.VirtualRunner.DEFAULT // 가상러너(자동 시나리오)
    private var runnerChip: TextView? = null
    private var paceLabel: TextView? = null
    private lateinit var uEstView: TextView
    private lateinit var startBtn: Button
    private lateinit var subtitle: TextView
    private lateinit var watchStatusView: TextView
    private val watchPoll = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var zoneBand: com.zone2runner.app.ui.ZoneBandView
    private lateinit var rangeView: TextView
    private lateinit var slopeView: TextView
    private lateinit var spmView: TextView
    private lateinit var strideView: TextView
    private lateinit var tempView: TextView
    private var profile: com.zone2runner.app.domain.Profile? = null
    private var tempFetched = false

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
    // 표시 존(adr-023): 순간심박+히스테리시스 — 화면 색과 워치 존의 유일 기준(코칭/통계는 60초 평균 유지)
    private val displayJudge = com.zone2runner.app.domain.DisplayZoneJudge()
    private var displayZone: com.zone2runner.app.domain.DisplayZone? = null
    // 토크테스트 설문 타이밍(adr-023: 워치→폰 이관). 조건은 세션 시간(시뮬 가속 반영), 표시 빈도는 벽시계로 제한.
    private var talkElevatedSec = 0      // Zone2 하한 이상 누적(세션 초)
    private var lastTalkAskSimSec = -1   // 마지막 질문 시점(세션 초, -1=아직 없음)
    private var lastTalkTickSimSec = -1  // 마지막 평가 시점(세션 초)
    private var lastTalkPromptWallMs = 0L // 마지막 표시 시각(벽시계) — 가속 재생 팝업 스팸 방지
    private val talkWarmupSec = 120       // 첫 토크테스트 전 워밍업(세션 초). 시작 직후 조기 팝업 방지 — HR가 지속 강도를 대표하려면 시간 필요(가상러너는 존 안에서 시작)
    private var settings = com.zone2runner.app.data.AppSettings() // spec-021, start()에서 로드

    private val mode: String by lazy { intent.getStringExtra(EXTRA_MODE) ?: MODE_SIM }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        settings = com.zone2runner.app.data.SettingsStore.load(this) // spec-021: 코칭 빈도/음성/화면 설정
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.KOREAN
                tts?.setSpeechRate(settings.ttsRate)
                tts?.setPitch(settings.ttsPitch) // spec-024 FR2
                // spec-024 FR1: 선택 보이스 적용. 소실(TTS 앱 업데이트 등)/열거 실패 시 조용히 기기 기본 유지
                settings.voiceName?.let { name ->
                    runCatching { tts?.voices?.firstOrNull { it.name == name }?.let { v -> tts?.voice = v } }
                }
                ttsReady = true
            }
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
        // BACK = 정상 종료(정지·저장 버튼과 동일 — 시뮬/실센서 공통, 사용자 요청 2026-07-08).
        // finalizeSession이 저장/개인화 누적/워치 종료까지 처리하고, 사후 LLM 설명은 규칙 폴백을
        // 먼저 저장하는 구조(spec-023)라 화면이 닫히며 코루틴이 취소돼도 안전하다.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (running) finalizeSession()
                finish()
            }
        })
    }

    /** 개인 Zone2 경계(bpm) 하한/상한 — 표시 판정/밴드/워치 전송이 공유하는 단일 산식. */
    private fun zoneBounds(uEstFrac: Double): Pair<Int, Int>? {
        val p = profile ?: return null
        val hi = (p.restingHr + uEstFrac * p.hrr).toInt()
        val lo = (p.restingHr + (uEstFrac - com.zone2runner.app.domain.Zone2Prior.BAND) * p.hrr).toInt()
        return lo to hi
    }

    /**
     * Zone2 밴드 게이지 + 범위/이탈 텍스트 갱신.
     * hr = 순간 심박 — 표시 존/솔리드 마커 기준(adr-023, 칩과 동일 기준). susHr = 지속 심박(참고 틱).
     */
    private fun updateZoneUi(hr: Int, uEstFrac: Double, susHr: Int = -1) {
        val p = profile ?: return
        val (lo, hi) = zoneBounds(uEstFrac) ?: return
        zoneBand.update(lo, hi, p.maxHr, hr, susHr)
        when {
            hr <= 0 -> { rangeView.text = "Zone 2 목표 $lo ~ $hi bpm"; rangeView.setTextColor(C_MUTED) }
            hr < lo -> { rangeView.text = "Zone 2 목표 $lo ~ $hi · ${lo - hr} bpm 아래"; rangeView.setTextColor(C_BLUE) }
            hr > hi -> { rangeView.text = "Zone 2 목표 $lo ~ $hi · ${hr - hi} bpm 초과"; rangeView.setTextColor(C_AMBER) }
            else -> { rangeView.text = "Zone 2 목표 $lo ~ $hi · 구간 안"; rangeView.setTextColor(C_ACCENT) }
        }
    }

    private fun updateSubtitle() {
        val model = "규칙 판정 + 개인화"
        subtitle.text = when (mode) {
            MODE_LIVE -> "$model · 실센서(GPS+워치HR) — 실기기 필요"
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
        // 지도는 컴팩트하게 — 아래 대시보드에 공간을 준다. 지도/대시보드 둘 다 weight로 화면을 나눠
        // 대시보드가 길어도(시뮬 컨트롤 多) 아래 ScrollView 안에서 스크롤된다.
        root.addView(map, LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.55f))

        val dash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        // 시작/정지 버튼 — 지도 바로 아래(스크롤 없이 즉시 조작, 사용자 요청)
        startBtn = Button(this).apply {
            text = primaryLabel()
            setOnClickListener { onPrimary() }
        }
        dash.addView(startBtn)

        subtitle = TextView(this).apply { textSize = 11f; setTextColor(C_MUTED) }
        dash.addView(subtitle)

        // 워치 연결 상태(항상 페어 전제 — 끊기면 눈에 띄게). 3초 주기 폴링.
        watchStatusView = TextView(this).apply { textSize = 12f; setTextColor(C_MUTED); setPadding(0, dp(2), 0, 0) }
        dash.addView(watchStatusView)

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
        // 밴드/판정 기준 안내(adr-023): 존 색=순간 심박(큰 숫자와 일치, 히스테리시스로 깜빡임 방지).
        dash.addView(TextView(this).apply {
            text = "● 순간 심박(존 색 기준, 큰 숫자와 동일)  |  얇은 틱 = 60초 평균(코칭 기준)"
            textSize = 10f; setTextColor(C_MUTED)
        }, mt(2))

        // 러닝 조언 표시 자리(추후 분석 엔진용). 현재는 비워 둔다.
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

        // 실시간 러닝 지표(경사/케이던스/보폭/드리프트/기온) — 표시용(판정은 규칙)
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

        // 토크테스트(spec-016)는 고정 UI 대신 팝업으로 통일(사용자 피드백) — 타이밍 조건 충족 시
        // 폰 다이얼로그 + 워치 전체화면 설문이 뜬다(updateTalkPrompt → showPhoneTalkDialog / /run/talk).

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
                        simInput = when (simInput) { // 자동 → 수동 페이스 → 수동 러너 순환
                            SimInput.AUTO -> SimInput.PACE
                            SimInput.PACE -> SimInput.RUNNER
                            SimInput.RUNNER -> SimInput.AUTO
                        }
                        if (simInput == SimInput.RUNNER) showBodySpecDialog() // 신체 스펙 입력(spec-022 FR1)
                        updateManualUi()
                    }
                }
            }
            manualRow.addView(manualChip)
            dash.addView(manualRow, mt(6))

            // 수동 가상러너 조종부(spec-022): 케이던스/보폭/심박 보정/경사. RUNNER 모드에서만 표시.
            vrRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            vrSummary = TextView(this).apply { textSize = 12f; setTextColor(C_TEXT); setTypeface(typeface, Typeface.BOLD) }
            vrRows!!.addView(vrSummary, mt(4))
            vrRows!!.addView(vrSeek("케이던스", "0~200 spm", 200, manualSpm, { "$manualSpm spm" }) { p -> // 0 = 정지에서 시작
                manualSpm = p
                (source as? com.zone2runner.app.sim.ManualVirtualRunnerSource)?.targetSpm = manualSpm
            }, mt(2))
            vrRows!!.addView(vrSeek("보폭", "0.60~1.50m", 90, ((manualStride - 0.60) * 100).toInt(), { "%.2fm".format(manualStride) }) { p ->
                manualStride = 0.60 + p * 0.01
                (source as? com.zone2runner.app.sim.ManualVirtualRunnerSource)?.targetStrideM = manualStride
            }, mt(2))
            vrRows!!.addView(vrSeek("심박 보정", "-20~+20", 40, manualHrOffset + 20, { "%+d bpm".format(manualHrOffset) }) { p ->
                manualHrOffset = p - 20
                (source as? com.zone2runner.app.sim.ManualVirtualRunnerSource)?.hrOffsetBpm = manualHrOffset
            }, mt(2))
            vrRows!!.addView(vrSeek("경사", "-10~+10%", 40, ((manualSlope + 10) * 2).toInt(), { "%+.1f%%".format(manualSlope) }) { p ->
                manualSlope = p / 2.0 - 10.0 // 0.5% 단위
                (source as? com.zone2runner.app.sim.ManualVirtualRunnerSource)?.slopePct = manualSlope
            }, mt(2))
            dash.addView(vrRows, mt(4))
            updateVrSummary()

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

        // LLM 프롬프트 투명성(시뮬/목 모드만) — 디버그 정보라 대시보드 맨 아래(사용자 요청)
        if (mode != MODE_LIVE) {
            promptView = TextView(this).apply {
                text = ""; textSize = 10f; setTextColor(C_MUTED)
                typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0)
            }
            dash.addView(promptView)
        }

        // 대시보드를 ScrollView로 감싸 화면을 넘는 데이터/컨트롤을 스크롤 가능하게(사용자 요청).
        val dashScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(dash)
        }
        root.addView(dashScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun primaryLabel(): String = when {
        finished -> "리포트 보기"
        running -> "정지 · 저장"
        mode == MODE_LIVE -> "실센서 러닝 시작"
        else -> "시뮬레이션 시작"
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
            append("• ★ 뛰면서 '편함/보통/벅참'을 누르면 이 범위가 당신 몸에 맞게 이동합니다. 편한데 미달로 나오면 '편함'을 누르세요 — 다음 세션부터 내려갑니다.")
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
                "왜 이 범위인지, 그리고 실제로 뛰며 편함/보통/벅참 버튼을 누르면 개인에 맞게 보정된다는 점을 3~4문장으로 쉽게 설명하세요. 따옴표/이모지 없이."
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
        val c = LlmCoach(this, personaKey = settings.personaKey) // 미가용 기기에선 내부적으로 RuleCoach 폴백. 말투=spec-024
        coach = c
        lifecycleScope.launch { c.prewarm() } // checkStatus+warmup을 첫 코칭 전에 미리
        // coachScope 전달 → 코칭 생성(LLM ~2초)이 샘플 루프/렌더를 멈추지 않음
        val learnedPrior = com.zone2runner.app.data.LearnedZone.uFrac(this) // 세션 누적 학습값(있으면 prior)
        val eng = RunEngine(
            profile, c, coachScope = lifecycleScope,
            priorUFrac = learnedPrior,
            cadence = settings.cadence, // spec-021
            priorDriftFloor = com.zone2runner.app.data.LearnedZone.driftFloor(this), // 드리프트 개인 플로어 누적(spec-025)
        )
        engine = eng
        startedAt = System.currentTimeMillis()
        frame = 0
        // 표시 존/토크테스트 타이밍 초기화(adr-023). 폴백 10분은 세션 시작부터 계산.
        displayJudge.reset(); displayZone = null
        talkElevatedSec = 0; lastTalkAskSimSec = -1; lastTalkTickSimSec = -1; lastTalkPromptWallMs = 0L

        val src: RunSource = when (mode) {
            MODE_LIVE -> LiveRunSource(this, WatchHrProvider(this).also { watchProvider = it })
            else -> when (simInput) {
                SimInput.PACE -> com.zone2runner.app.sim.ManualRunSource(profile, manualPace, delayMs = simDelayMs, seed = System.nanoTime())
                SimInput.RUNNER -> com.zone2runner.app.sim.ManualVirtualRunnerSource(
                    manualBody ?: com.zone2runner.app.sim.ManualVirtualRunnerSource.Body(profile.age, profile.restingHr, profile.maxHr, 7.0),
                    delayMs = simDelayMs, seed = System.nanoTime(),
                ).also {
                    it.targetSpm = manualSpm; it.targetStrideM = manualStride
                    it.hrOffsetBpm = manualHrOffset; it.slopePct = manualSlope
                }
                SimInput.AUTO -> com.zone2runner.app.sim.SimRunnerSource(
                    virtualRunner, delayMs = simDelayMs, seed = System.nanoTime(),
                    onTalkTest = { st ->
                        runOnUiThread {
                            eng.observeTalkTest(st)
                            logger?.event("talktest") { put("t", (System.currentTimeMillis() - startedAt) / 1000); put("state", st.name); put("src", "virtual") }
                        }
                    })
            }
        }
        source = src
        // 시뮬 자동 시나리오: 가상러너의 기온을 코칭 맥락에 주입(더위 취약형 등 검증 가능). 실측 조회는 좌표 확보 시 덮어씀.
        if (mode == MODE_SIM && simInput == SimInput.AUTO) {
            if (settings.heatCoachingEnabled) eng.ambientTempC = virtualRunner.tempC
            tempView.text = "%.0f℃".format(virtualRunner.tempC); tempFetched = true
        }
        // 수동 러너: 기온을 신체 스펙 입력값으로 고정(더위 코칭을 손으로 테스트, 실측 조회 안 함)
        if (mode == MODE_SIM && simInput == SimInput.RUNNER) {
            if (settings.heatCoachingEnabled) eng.ambientTempC = manualTempC
            tempView.text = "%.0f℃".format(manualTempC); tempFetched = true
        }

        // 필드 로그(spec-012): 원시 입력+파이프라인 출력을 1Hz JSONL로 기록(adb pull로 회수)
        val log = RunLogger(this)
        logger = log
        log.meta(mode) {
            put("profile", org.json.JSONObject()
                .put("age", profile.age).put("rhr", profile.restingHr).put("maxHr", profile.maxHr))
            put("model", org.json.JSONObject()
                .put("type", "rule"))
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
        // 워치는 항상 페어(사용자 결정). 실센서면 워치가 자기 센서로 러닝, 시뮬이면 워치를 미러 모드로.
        when {
            mode == MODE_LIVE -> RunLink.send(this, RunLink.PATH_START)
            else -> RunLink.send(this, RunLink.PATH_MIRROR)
        }
        // 러닝 중 화면 유지: LLM 코칭은 포그라운드 전용(adr-007), GPS/파이프라인도 화면off 스로틀 회피.
        // 설정(spec-021)에서 끄면 화면 자동 꺼짐 허용(실센서 모드 배터리 절약).
        if (settings.keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startBtn.text = primaryLabel()

        src.start(lifecycleScope, onSample = sample@{ s ->
            if (remoteStopRequested) { finalizeSession(); return@sample } // 워치에서 종료
            val state = eng.onSample(s)
            src.onFeedback(state) // 폐루프: 판정을 소스로 되먹임(가상러너가 코칭에 반응)
            log.sample(s, state, watchProvider?.lastAgeMs() ?: -1L)
            // 표시 존 판정(adr-023): 순간심박+히스테리시스. 화면 칩/밴드 마커/워치 존의 유일 기준.
            if (state.hr > 0) zoneBounds(state.uEstFrac)?.let { (lo, hi) ->
                displayZone = displayJudge.judge(state.hr, lo, hi, profile.maxHr)
            }
            // 시뮬/목: 심박을 워치로 스트림(미러) — 워치에서 표시+토크테스트 가능
            if (mode != MODE_LIVE && state.hr > 0 && frame % 25 == 0) RunLink.sendMirrorHr(this, state.hr)
            // adr-023: 폰이 확정한 표시 존을 워치로 푸시 → 워치는 무로직 뷰어(항상 폰 연결 전제).
            // MODE_LIVE=매초, 시뮬=미러와 같은 캐던스.
            if (state.hr > 0 && (mode == MODE_LIVE || frame % 25 == 0)) pushWatchZone(state)
            // 토크테스트 설문 타이밍(adr-023: 폰이 판단, 워치는 /run/talk 수신 시 표시만)
            updateTalkPrompt(state)
            if (!tempFetched && s.lat.isFinite() && s.lon.isFinite()) { // 기온 1회 조회(유효 좌표 확보 후)
                tempFetched = true
                lifecycleScope.launch {
                    com.zone2runner.app.data.WeatherProbe.currentTempC(s.lat, s.lon)?.let {
                        tempView.text = "%.0f℃".format(it)
                        // 설정(spec-021)에서 더위 코칭 끄면 표시만 하고 코칭 맥락엔 넣지 않음
                        if (settings.heatCoachingEnabled) eng.ambientTempC = it // 방향은 규칙, 기온은 표현 재료(adr-002/008)
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
                    "very_comfortable" -> com.zone2runner.app.pipeline.TalkState.VERY_COMFORTABLE
                    "comfortable" -> com.zone2runner.app.pipeline.TalkState.COMFORTABLE
                    "hard" -> com.zone2runner.app.pipeline.TalkState.HARD
                    "very_hard" -> com.zone2runner.app.pipeline.TalkState.VERY_HARD
                    else -> com.zone2runner.app.pipeline.TalkState.BORDERLINE
                }
                runOnUiThread {
                    engine?.observeTalkTest(st)
                    logger?.event("talktest") { put("t", (System.currentTimeMillis() - startedAt) / 1000); put("state", st.name); put("src", "watch") }
                    talkDialog?.let { runCatching { it.dismiss() } } // 워치에서 답함 → 폰 팝업도 닫기(중복 응답 방지)
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
        displayZone = null // 종료 요약 렌더가 마지막 존 색을 재사용하지 않게
        talkDialog?.let { runCatching { it.dismiss() } } // 폰 토크 팝업 정리(spec-022)
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
        val finalU = eng.currentUFrac()
        com.zone2runner.app.data.LearnedZone.set(this, finalU, eng.talkObservations(), eng.currentSigmaBpm()) // uFrac 이력 + 관측 + σ(spec-020)
        eng.driftFloorState().let { (m, v, n) -> com.zone2runner.app.data.LearnedZone.setDriftFloor(this, m, v, n) } // 드리프트 개인 플로어 저장(spec-025)
        logger?.event("boundary") {
            put("uFrac", finalU); put("talk", eng.talkObserved)
            put("n", com.zone2runner.app.data.LearnedZone.sessionCount(this@RunActivity))
        }
        val base = eng.report().copy(
            startedAtEpochMs = startedAt, sourceMode = mode,
            coachSource = coach?.sessionSource() ?: "rule",
        )
        // 사후 세션 스토리(spec-023 FR2): 규칙 팩트를 먼저 저장(폴백 보장) → 아래에서 LLM 풀이로 덮어씀.
        val report = base.copy(sessionStory = com.zone2runner.app.coaching.SessionExplainer.facts(base))
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
        // 설명 서비스(spec-023): 개인화 설명을 세션 종료 시 1회 생성해 저장 → 프로필이 LLM 재호출 없이 재사용.
        generateAndStorePersonalizationExplanation()
        // 사후 세션 스토리(FR2): LLM 가용 시 자연어 풀이로 저장된 규칙 폴백을 덮어쓴다(1회, 이후 재호출 없음).
        ReportHolder.last?.id?.let { savedId ->
            lifecycleScope.launch {
                val out = runCatching {
                    com.zone2runner.app.coaching.LlmCoach(this@RunActivity)
                        .freeform(com.zone2runner.app.coaching.SessionExplainer.prompt(report))
                }.getOrNull()
                if (!out.isNullOrBlank()) SessionStore.updateStory(this@RunActivity, savedId, out)
            }
        }
        render(LiveState(elapsedSec = report.durationSec, hr = report.avgHr, smoothedHr = report.avgHr,
            judgment = null, paceMinKm = report.avgPaceMinKm, distanceM = report.distanceM,
            coaching = "세션 종료 · 저장됨", uEstFrac = report.uEstEndFrac))
        finished = true
        startBtn.text = primaryLabel()
        Toast.makeText(this, "세션 저장됨", Toast.LENGTH_SHORT).show()
    }

    /** 개인화 설명을 세션 종료 시 1회 생성(규칙 팩트 우선 저장 → LLM 가용 시 풀이로 덮어씀)해 저장. spec-023 FR1. */
    private fun generateAndStorePersonalizationExplanation() {
        val p = profile ?: return
        val prior = com.zone2runner.app.domain.Zone2Prior.of(p)
        val status = com.zone2runner.app.domain.PersonalizationStatus(
            restingHr = p.restingHr, hrr = p.hrr, priorUFrac = prior.uFrac0,
            learnedUFrac = com.zone2runner.app.data.LearnedZone.uFrac(this),
            band = com.zone2runner.app.domain.Zone2Prior.BAND,
            sessions = com.zone2runner.app.data.LearnedZone.sessionCount(this),
            talkObs = com.zone2runner.app.data.LearnedZone.talkObs(this),
            sigmaBpm = com.zone2runner.app.data.LearnedZone.sigmaBpm(this),
            uFracHistory = com.zone2runner.app.data.LearnedZone.history(this),
        )
        // 규칙 팩트를 먼저 저장(폴백 보장) → LLM 가용 시 자연어 풀이로 덮어쓴다.
        com.zone2runner.app.data.LearnedZone.setExplanation(this, com.zone2runner.app.coaching.PersonalizationExplainer.facts(status))
        lifecycleScope.launch {
            val out = runCatching {
                com.zone2runner.app.coaching.LlmCoach(this@RunActivity)
                    .freeform(com.zone2runner.app.coaching.PersonalizationExplainer.prompt(status))
            }.getOrNull()
            if (!out.isNullOrBlank()) com.zone2runner.app.data.LearnedZone.setExplanation(this@RunActivity, out)
        }
    }

    /**
     * adr-023: 폰이 확정한 표시 존을 워치로 푸시 — 워치는 계산 없이 그대로 그린다(무로직 뷰어).
     * 존인덱스(1~5) + 존내위치(0~1000, 등폭 게이지 마커 각도용) + 순간심박(큰 숫자 동일).
     */
    private fun pushWatchZone(s: LiveState) {
        val p = profile ?: return
        val z = displayZone ?: return
        if (s.hr <= 0) return
        val (lo, hi) = zoneBounds(s.uEstFrac) ?: return
        val frac = com.zone2runner.app.domain.DisplayZones.withinFrac(s.hr, z, lo, hi, p.maxHr)
        RunLink.sendLive(this, s.hr, z.idx, (frac * 1000).toInt())
    }

    /**
     * 토크테스트 설문 타이밍(adr-023: 워치에서 폰으로 이관 — 워치는 /run/talk 수신 시 표시만).
     *  (A) 지속 심박이 Zone2 하한 이상에 30초+ 머묾(첫 질문은 즉시, 이후 3분 간격 — active learning).
     *  (B) 10분 무질문 폴백 — 낮은 심박도 그 사람에겐 힘들 수 있으니.
     * 조건은 세션 시간 기준(시뮬 가속에서도 생리적 타이밍 유지). 라이브(1Hz)에선 벽시계와 동일.
     * 실제 표시(팝업/워치 전송)는 벽시계 30초 간격으로 제한 — ×70 가속에서 스팸 방지.
     */
    private fun updateTalkPrompt(s: LiveState) {
        val simSec = s.elapsedSec
        if (simSec <= lastTalkTickSimSec) return // 같은 세션 초는 1회만 평가
        val dt = if (lastTalkTickSimSec < 0) 1 else simSec - lastTalkTickSimSec
        lastTalkTickSimSec = simSec
        val (lo, _) = zoneBounds(s.uEstFrac) ?: return
        val sus = if (s.smoothedHr > 0) s.smoothedHr else s.hr
        val elevated = sus > 0 && sus >= lo // Zone2 이상(지속 심박 기준 — 개인화 관측과 같은 채널)
        if (elevated) talkElevatedSec += dt else talkElevatedSec = 0
        val sinceAsk = simSec - lastTalkAskSimSec
        val warmedUp = simSec >= talkWarmupSec // 워밍업 전엔 안 물음(시작 직후 조기 팝업 방지)
        val elevatedAsk = elevated && warmedUp && talkElevatedSec >= 30 && (lastTalkAskSimSec < 0 || sinceAsk > 3 * 60)
        val fallback = sus > 0 && (simSec - maxOf(lastTalkAskSimSec, 0)) > 10 * 60
        val now = android.os.SystemClock.elapsedRealtime()
        val wallOk = now - lastTalkPromptWallMs > 30_000L || lastTalkPromptWallMs == 0L
        if ((elevatedAsk || fallback) && wallOk) {
            lastTalkAskSimSec = simSec
            lastTalkPromptWallMs = now
            RunLink.send(this, RunLink.PATH_TALK)
            // 폰에도 5지선다 팝업(사용자 확정: 폰/워치 모두 팝업) — 고정 칩 UI는 제거됨.
            // 시뮬 소스는 팝업 동안 시간 정지, 실센서는 무시(현실은 안 멈춤).
            showPhoneTalkDialog()
        }
    }

    /**
     * 폰 토크테스트 팝업(spec-022) — 30초 무응답 자동 닫힘, 중복 표시 방지.
     * 떠 있는 동안 시뮬 시간 정지(사용자 요청) — 가속 재생 중에도 "지금 이 강도"에 대해 답하게.
     */
    private fun showPhoneTalkDialog() {
        if (talkDialog?.isShowing == true) return
        val states = listOf(
            "아주편함" to com.zone2runner.app.pipeline.TalkState.VERY_COMFORTABLE,
            "편함" to com.zone2runner.app.pipeline.TalkState.COMFORTABLE,
            "보통" to com.zone2runner.app.pipeline.TalkState.BORDERLINE,
            "벅참" to com.zone2runner.app.pipeline.TalkState.HARD,
            "매우벅참" to com.zone2runner.app.pipeline.TalkState.VERY_HARD,
        )
        val d = android.app.AlertDialog.Builder(this)
            .setTitle("대화 되나요? (지금 강도)")
            .setItems(states.map { it.first }.toTypedArray()) { _, i ->
                engine?.observeTalkTest(states[i].second)
                logger?.event("talktest") { put("t", (System.currentTimeMillis() - startedAt) / 1000); put("state", states[i].second.name); put("src", "phone_popup") }
                RunLink.send(this, RunLink.PATH_TALK_DONE) // 폰에서 답함 → 워치 설문도 닫기
                Toast.makeText(this, "기록됨 · 개인 경계에 반영", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("건너뛰기", null)
            .create()
        d.setOnDismissListener {
            if (talkDialog === d) talkDialog = null
            source?.setPaused(false) // 팝업 닫힘 → 시뮬 시간 재개
        }
        d.show()
        talkDialog = d
        source?.setPaused(true) // 팝업 표시 중 시뮬 시간 정지(실센서 소스는 무시)
        hrView.postDelayed({ if (talkDialog === d) runCatching { d.dismiss() } }, 30_000L)
    }

    private fun render(s: LiveState) {
        hrView.text = if (s.hr > 0) "${s.hr} bpm" else "-- bpm"
        // 존 칩/숫자 색 = 표시 존(adr-023: 순간심박+히스테리시스, 워치와 동일 존/색)
        val z = displayZone
        if (z != null && s.hr > 0) {
            val zc = Color.parseColor(z.colorHex)
            zoneChip.text = "${z.short} ${z.desc}"; zoneChip.background = pill(zc); hrView.setTextColor(zc)
        } else s.judgment?.let { j -> // 표시 존 없음(재생/요약 렌더 등) — 기존 판정 칩 폴백
            zoneChip.text = j.label; zoneChip.background = pill(j.color); hrView.setTextColor(j.color)
        }
        updateZoneUi(s.hr, s.uEstFrac, if (s.smoothedHr > 0) s.smoothedHr else -1)
        timeView.text = "%02d:%02d".format(s.elapsedSec / 60, s.elapsedSec % 60)
        distView.text = if (s.distanceM < 1000) "${s.distanceM.toInt()}m" else "%.2fkm".format(s.distanceM / 1000)
        // 움직이기 전(정지/GPS 미확보)엔 페이스/케이던스/보폭을 근거 없이 표시하지 않는다("--").
        // paceMinKm 20은 LiveRunSource의 정지 sentinel이라 유효 범위에서 제외.
        val moving = s.speedKmh > 0.8 && s.paceMinKm in 0.1..19.5
        paceView.text = if (moving) "%d'%02d\"".format(s.paceMinKm.toInt(), ((s.paceMinKm % 1) * 60).toInt()) else "--"

        // 실시간 러닝 지표(표시용)
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

        // 관측 분석 엔진(FR3) 라이브 표시: 안전 경고 우선, 없으면 드리프트/경사보정 페이스.
        val advice = buildString {
            val safety = s.safetyAlert
            if (!safety.isNullOrBlank()) {
                append("⚠ $safety")
            } else {
                s.driftSlope?.let { d ->
                    if (kotlin.math.abs(d) >= 0.5)
                        append("드리프트 %+.1f bpm/분%s".format(d, if (s.driftRising) " ↑" else ""))
                }
                s.gapPaceMinKm?.let { g ->
                    if (moving && kotlin.math.abs(s.slopePct) > 2.0) {
                        if (isNotEmpty()) append("   ")
                        append("경사보정 %.2f min/km".format(g))
                    }
                }
            }
        }
        adviceView.text = advice
        adviceView.setTextColor(if (s.safetyAlert != null) C_AMBER else C_MUTED)
    }

    /** 코칭 문장을 음성으로(llm-verify에서 검증한 TTS end-to-end). 세션 종료 문구는 제외. */
    private fun speak(text: String) {
        if (!settings.voiceEnabled) return // 음성 코칭 off(spec-021) — 화면 문구는 그대로 표시
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
            (source as? com.zone2runner.app.sim.ManualVirtualRunnerSource)?.delayMs = ms
            highlightSpeed()
        }
    }

    private fun updateRunnerChip() {
        runnerChip?.text = virtualRunner.name
        // 수동 모드(페이스/러너)에선 가상러너 프리셋 무의미 → 흐리게
        runnerChip?.alpha = if (simInput != SimInput.AUTO) 0.4f else 1f
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
        // 🎲 랜덤 러너: 누를 때마다 모집단에서 새 사람 샘플(spec-019). 매번 다른 신체/스타일/코스/기온.
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(C_CARD); cornerRadius = dp(12).toFloat(); setStroke(dp(1), C_ACCENT)
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
            isClickable = true
            addView(TextView(this@RunActivity).apply {
                text = "🎲 랜덤 러너"; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(C_ACCENT)
            })
            addView(TextView(this@RunActivity).apply {
                text = "누를 때마다 새로운 사람 — 신체/스타일/코스/기온이 매번 다릅니다"
                textSize = 11f; setTextColor(C_MUTED); setPadding(0, dp(4), 0, 0)
            })
            setOnClickListener {
                virtualRunner = com.zone2runner.app.domain.VirtualRunner.randomHuman(System.nanoTime())
                updateRunnerChip(); dialog.dismiss()
                Toast.makeText(this@RunActivity, "새 러너: ${virtualRunner.name} (${virtualRunner.summary})", Toast.LENGTH_SHORT).show()
            }
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
                    val terrain = when { r.terrainHilliness > 0.6 -> "언덕많음"; r.terrainHilliness > 0.3 -> "완만"; else -> "평지" }
                    text = "나이 ${r.age} · 안정 ${r.restingHr} · 최대 ${r.maxHr}bpm · 진짜임계 ${(r.trueZone2UpperHrmaxFrac * 100).toInt()}%\n" +
                        "페이스 규율 ${(r.pacingDiscipline * 100).toInt()}% · 코칭 반응 ${(r.coachingResponsiveness * 100).toInt()}% · 코스 $terrain · ${r.tempC.toInt()}℃"
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
        val manual = simInput != SimInput.AUTO
        manualChip?.apply {
            text = when (simInput) {
                SimInput.AUTO -> "자동 시나리오"
                SimInput.PACE -> "수동 페이스"
                SimInput.RUNNER -> "수동 러너"
            }
            setTextColor(if (manual) Color.WHITE else C_TEXT)
            background = GradientDrawable().apply {
                setColor(if (manual) C_ACCENT_DIM else C_CARD)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), if (manual) C_ACCENT else C_STROKE)
            }
        }
        paceRow?.visibility = if (simInput == SimInput.PACE) android.view.View.VISIBLE else android.view.View.GONE
        vrRows?.visibility = if (simInput == SimInput.RUNNER) android.view.View.VISIBLE else android.view.View.GONE
        updatePaceLabel()
        updateVrSummary()
        updateRunnerChip()
    }

    /**
     * 수동 러너 조종 슬라이더 한 줄 — 라벨(범위 범례 포함) + SeekBar + 현재 값(사용자 피드백: 범례 표시).
     * value()가 현재 값 문자열을 만들고, onChange 후 값 라벨과 요약이 함께 갱신된다.
     */
    private fun vrSeek(title: String, legend: String, max: Int, initial: Int, value: () -> String, onChange: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@RunActivity).apply { text = title; textSize = 11f; setTextColor(C_TEXT) })
            addView(TextView(this@RunActivity).apply { text = legend; textSize = 9f; setTextColor(C_MUTED) })
        }, LinearLayout.LayoutParams(dp(64), WRAP_CONTENT))
        val valueView = TextView(this).apply {
            text = value(); textSize = 12f; setTextColor(C_ACCENT); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END
        }
        row.addView(android.widget.SeekBar(this).apply {
            this.max = max; progress = initial.coerceIn(0, max)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    onChange(p); valueView.text = value(); updateVrSummary()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(valueView, LinearLayout.LayoutParams(dp(76), WRAP_CONTENT))
        return row
    }

    /** 조종 값 요약: 케이던스 x 보폭 → 페이스 환산 + 심박 보정. 케이던스 0 = 정지. */
    private fun updateVrSummary() {
        val paceTxt = if (manualSpm <= 0) "정지" else {
            val pace = 1000.0 / (manualSpm * manualStride)
            if (pace > 20.0) "걷기 미만" else "%d'%02d\"".format(pace.toInt(), ((pace % 1) * 60).toInt())
        }
        vrSummary?.text = "%d spm x %.2fm → %s  ·  심박 보정 %+d bpm"
            .format(manualSpm, manualStride, paceTxt, manualHrOffset)
    }

    /** 신체 스펙 입력(spec-022 FR1) — 기본값은 활성 프로필. 수동 러너 모드 선택 시 표시. */
    private fun showBodySpecDialog() {
        val p = profile ?: ProfileStore.load(this).also { profile = it }
        val cur = manualBody ?: com.zone2runner.app.sim.ManualVirtualRunnerSource.Body(p.age, p.restingHr, p.maxHr, 7.0)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
        }
        // 값이 미리 채워지면 hint가 안 보이므로 각 항목 위에 라벨을 명시(사용자 피드백)
        fun field(label: String, value: String): android.widget.EditText {
            col.addView(TextView(this).apply {
                text = label; textSize = 12f; setTextColor(C_MUTED); setPadding(0, dp(8), 0, dp(2))
            })
            return android.widget.EditText(this).apply {
                setText(value)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED // 기온 영하 입력
                col.addView(this)
            }
        }
        val age = field("나이(세)", cur.age.toString())
        val rhr = field("안정 심박(bpm)", cur.restingHr.toString())
        val mhr = field("최대 심박(bpm)", cur.maxHr.toString())
        val bp = field("기준 페이스(min/km) - 편하게 달릴 때", cur.basePaceMinKm.toString())
        val tmp = field("기온(℃) - 더위 코칭 테스트용", manualTempC.toString())
        android.app.AlertDialog.Builder(this)
            .setTitle("수동 러너 신체 스펙")
            .setView(android.widget.ScrollView(this).apply { addView(col) })
            .setPositiveButton("확인") { _, _ ->
                manualBody = com.zone2runner.app.sim.ManualVirtualRunnerSource.Body(
                    age.text.toString().toIntOrNull()?.coerceIn(10, 90) ?: cur.age,
                    rhr.text.toString().toIntOrNull()?.coerceIn(35, 90) ?: cur.restingHr,
                    mhr.text.toString().toIntOrNull()?.coerceIn(120, 230) ?: cur.maxHr,
                    bp.text.toString().toDoubleOrNull()?.coerceIn(3.5, 12.0) ?: cur.basePaceMinKm,
                )
                manualTempC = tmp.text.toString().toDoubleOrNull()?.coerceIn(-15.0, 45.0) ?: manualTempC
            }
            .setNegativeButton("취소", null)
            .show()
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

    override fun onResume() { super.onResume(); map.onResume(); watchPoll.post(watchTick) }
    override fun onPause() { super.onPause(); map.onPause(); watchPoll.removeCallbacks(watchTick) }
    override fun onDestroy() {
        super.onDestroy(); source?.stop()
        isRunning = false
        // BACK/화면 이탈로 종료해도 워치가 계속 돌지 않게 종료 신호 전송(멈춤은 멱등 — finalizeSession이 이미 보냈어도 무해). 2026-07-08 버그 수정.
        if (isFinishing) RunLink.send(this, RunLink.PATH_STOP)
        watchPoll.removeCallbacks(watchTick)
        unregisterTalkListener()
        logger?.close(); logger = null // 중도 이탈 시에도 로그 파일 마감
        tts?.stop(); tts?.shutdown()
    }

    /** 워치 연결 상태 3초 폴링 → 상태 텍스트 갱신(항상 페어 전제라 끊기면 빨간 경고). */
    private val watchTick = object : Runnable {
        override fun run() {
            RunLink.watchConnected(this@RunActivity) { connected ->
                if (::watchStatusView.isInitialized) {
                    watchStatusView.text = if (connected) "⌚ 워치 연결됨" else "⌚ 워치 연결 끊김 — 페어링/블루투스 확인"
                    watchStatusView.setTextColor(if (connected) C_ACCENT else C_RED)
                }
            }
            watchPoll.postDelayed(this, 3000)
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SIM = "sim"
        const val MODE_LIVE = "live"
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
        private val C_RED = Palette.RED
        private val C_CARD = Palette.CARD
        private val C_STROKE = Palette.STROKE
    }
}
