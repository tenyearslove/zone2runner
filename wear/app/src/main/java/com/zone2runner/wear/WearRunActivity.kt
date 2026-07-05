package com.zone2runner.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.wear.widget.BoxInsetLayout

/**
 * Zone2 Runner — 워치 러닝 대시보드.
 * 한 화면(스크롤 없음)에 HR, 현재 존, 페이스/거리/속도, 경과시간, 조작 버튼을 배치한다.
 * 측정/누적은 RunService(포그라운드 서비스, adr-009)가 소유 — 화면이 꺼져도 세션 유지.
 * 이 액티비티는 RunBus를 구독해 렌더하고, 버튼은 서비스에 액션 인텐트만 보낸다.
 */
class WearRunActivity : ComponentActivity() {

    private val ui = Handler(Looper.getMainLooper())

    // 뷰
    private lateinit var gauge: ZoneGaugeView
    private lateinit var timeView: TextView
    private lateinit var hrView: TextView
    private lateinit var bpmLabel: TextView
    private lateinit var zoneLabel: TextView
    private lateinit var paceVal: TextView
    private lateinit var distVal: TextView
    private lateinit var spdVal: TextView
    private lateinit var btnRow: LinearLayout
    private lateinit var talkRow: LinearLayout

    // 토크테스트 프롬프트(심박 높을 때 가끔 물어봄 → 폰 개인화에 반영)
    private var lastTalkAskMs = 0L
    private var talkShownAt = 0L
    private var elevatedSec = 0

    private val state: RunState get() = RunBus.state

    private val ticker = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    private var mirror = false // 시뮬 미러 모드(폰 심박 수신, 자기 센서/서비스 안 씀)
    private var mirrorListener: com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mirror = intent.getBooleanExtra(EXTRA_MIRROR, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Zones.load(this) // 폰에서 동기화된 개인 존 경계 적용(없으면 기본값)
        setContentView(buildUi())
        render()
    }

    override fun onResume() {
        super.onResume()
        RunBus.listener = { render() }
        ui.post(ticker)
        if (mirror) registerMirror()
    }

    /** 미러: 폰 시뮬 심박(/run/mirrorhr)을 받아 RunBus에 반영 → 화면 표시 + 토크테스트 동작. */
    private fun registerMirror() {
        if (mirrorListener != null) return
        val l = com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener { e ->
            if (e.path == RunLink.PATH_MIRROR_HR) {
                val bpm = runCatching { String(e.data).trim().toInt() }.getOrNull() ?: return@OnMessageReceivedListener
                RunBus.hr = bpm
                if (RunBus.state == RunState.IDLE) { RunBus.state = RunState.RUNNING; RunBus.runStart = SystemClock.elapsedRealtime(); RunBus.accumulatedMs = 0 }
                runOnUiThread { RunBus.notifyUi() }
            }
        }
        runCatching { com.google.android.gms.wearable.Wearable.getMessageClient(this).addListener(l) }
        mirrorListener = l
    }

    override fun onPause() {
        super.onPause()
        RunBus.listener = null
        ui.removeCallbacks(ticker)
        mirrorListener?.let { runCatching { com.google.android.gms.wearable.Wearable.getMessageClient(this).removeListener(it) }; mirrorListener = null }
    }

    // ---- UI ----

    private fun buildUi(): BoxInsetLayout {
        val box = BoxInsetLayout(this).apply { setBackgroundColor(Color.BLACK) }

        gauge = ZoneGaugeView(this)
        box.addView(gauge, BoxInsetLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        timeView = TextView(this).apply {
            text = "00:00"; textSize = 13f; setTextColor(C_MUTED)
            typeface = Typeface.MONOSPACE
        }
        content.addView(timeView, centered())

        // HR + BPM
        hrView = TextView(this).apply {
            text = "--"; textSize = 40f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(C_MUTED)
        }
        bpmLabel = TextView(this).apply {
            text = "BPM"; textSize = 9f; setTextColor(C_MUTED); letterSpacing = 0.15f
            gravity = Gravity.CENTER
        }
        content.addView(hrView, centered())
        content.addView(bpmLabel, centered())

        zoneLabel = TextView(this).apply {
            text = "시작 대기"; textSize = 12f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(C_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(1), 0, dp(4))
        }
        content.addView(zoneLabel, centered())

        // 페이스 / 거리 / 속도
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        paceVal = metricValue(); distVal = metricValue(); spdVal = metricValue()
        metrics.addView(metricCol(paceVal, "페이스"))
        metrics.addView(metricCol(distVal, "거리"))
        metrics.addView(metricCol(spdVal, "속도"))
        content.addView(metrics, centered())

        // 버튼
        btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        content.addView(btnRow, centered())

        // 토크테스트 프롬프트(기본 숨김): 심박 높을 때 가끔 "대화 되나요?" 5단계. 화면이 작아 2줄로 배치.
        talkRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            visibility = View.GONE; setPadding(0, dp(6), 0, 0)
        }
        val talkR1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        talkR1.addView(talkChip("아주편함", "very_comfortable"))
        talkR1.addView(talkChip("편함", "comfortable"))
        talkR1.addView(talkChip("보통", "borderline"))
        val talkR2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        talkR2.addView(talkChip("벅참", "hard"))
        talkR2.addView(talkChip("매우벅참", "very_hard"))
        talkRow.addView(talkR1); talkRow.addView(talkR2)
        content.addView(talkRow, centered())

        // BOX_ALL(내접 사각형)은 원형 480px에서 실사용 폭이 ~70%로 줄어 페이스 줄바꿈/버튼 잘림 발생(실기기).
        // 콘텐츠가 세로 중앙 정렬이라 중앙 행은 원의 전체 폭을 쓸 수 있으므로 고정 패딩으로 대체.
        content.setPadding(dp(14), dp(6), dp(14), dp(6))
        box.addView(content, BoxInsetLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            gravity = Gravity.CENTER
        })
        return box
    }

    private fun metricValue() = TextView(this).apply {
        text = "--"; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(C_TEXT)
        gravity = Gravity.CENTER
        isSingleLine = true // 원형 화면 폭에서 페이스("5'30\"") 줄바꿈 방지 (실기기 확인)
        ellipsize = null
        // 좁은 3열에서 값이 열 폭에 맞춰 자동 축소(예: "10.5", "1.23km"). 위아래 잘림 방지로 폰트패딩 유지.
        setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }

    private fun metricCol(value: TextView, label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            layoutParams = lp
            setPadding(dp(5), 0, dp(5), 0) // 열 간 여백 확대(겹침 방지)
            addView(value)
            addView(TextView(this@WearRunActivity).apply {
                text = label; textSize = 9f; setTextColor(C_MUTED); gravity = Gravity.CENTER
                isSingleLine = true
            })
        }
    }

    private fun pill(text: String, color: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 13f; setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                setColor(color); cornerRadius = dp(20).toFloat()
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun rebuildButtons() {
        btnRow.removeAllViews()
        if (mirror) { // 미러 모드: 제어는 폰이 함 → 버튼 대신 안내만
            btnRow.addView(TextView(this).apply {
                text = "시뮬 미러 · 대화 테스트만"; textSize = 10f; setTextColor(C_MUTED); gravity = Gravity.CENTER
            })
            return
        }
        when (state) {
            RunState.IDLE -> btnRow.addView(pill("시작", C_GREEN) { start() })
            RunState.RUNNING -> {
                btnRow.addView(pill("일시정지", C_AMBER) { sendAction(RunService.ACTION_PAUSE) })
                btnRow.addView(space())
                btnRow.addView(pill("종료", C_RED) { sendAction(RunService.ACTION_STOP) })
            }
            RunState.PAUSED -> {
                btnRow.addView(pill("재개", C_GREEN) { sendAction(RunService.ACTION_RESUME) })
                btnRow.addView(space())
                btnRow.addView(pill("종료", C_RED) { sendAction(RunService.ACTION_STOP) })
            }
        }
    }

    private fun space() = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) }

    /** 토크테스트 답변 칩 → 폰으로 전송(/talk/<state>) → 폰 개인화(observeTalkTest)에 반영. */
    private fun talkChip(label: String, stateName: String) = TextView(this).apply {
        text = label; textSize = 11f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(7), dp(5), dp(7), dp(5))
        background = GradientDrawable().apply { setColor(Color.parseColor("#3A3F4A")); cornerRadius = dp(14).toFloat() }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT); lp.marginStart = dp(3); lp.marginEnd = dp(3)
        layoutParams = lp
        isClickable = true
        setOnClickListener {
            RunLink.send(this@WearRunActivity, "/talk/$stateName")
            lastTalkAskMs = SystemClock.elapsedRealtime()
            talkRow.visibility = View.GONE
            android.widget.Toast.makeText(this@WearRunActivity, "기록됨", android.widget.Toast.LENGTH_SHORT).show()
        }
    }


    /**
     * 토크테스트 타이밍 — 심박이 높아져 특정 구간에 '머물' 때만 가끔 묻는다(사용자 요청, 고정 주기 아님).
     *  (A) 지속 심박이 Zone 2 이상(개인 경계 기준)에 30초 이상 머물렀고, 최근 3분 안에 안 물었을 때.
     *      = 경계 근처/이상에 안정적으로 있을 때가 "이 강도 편해?" 확인이 가장 유용(active learning).
     *  (B) 오래(10분) 아예 안 물었으면 폴백 — 낮은 심박도 그 사람껜 힘들 수 있으니.
     * 프롬프트가 뜨면 짧게 진동. 30초 무응답이면 닫는다. 러너는 언제든 직접 답할 수 있음.
     */
    private fun updateTalkPrompt(hr: Int) {
        val running = state == RunState.RUNNING && hr > 0
        // Zone 2 이상(개인 경계 기준)에 '머무는' 시간 누적. Z1로 내려가면 리셋.
        val inZone2Plus = running && Zones.zoneOf(hr) != HrZone.Z1
        if (inZone2Plus) elevatedSec++ else elevatedSec = 0
        val now = SystemClock.elapsedRealtime()
        if (talkRow.visibility != View.VISIBLE) {
            val since = now - lastTalkAskMs
            val elevatedAsk = inZone2Plus && elevatedSec >= 30 && since > 3 * 60 * 1000L
            val fallback = running && since > 10 * 60 * 1000L
            if (elevatedAsk || fallback) { talkRow.visibility = View.VISIBLE; talkShownAt = now; buzz() }
        } else {
            zoneLabel.text = "대화 되나요?"; zoneLabel.setTextColor(C_TEXT) // 질문 문구로 대체
            if (now - talkShownAt > 30_000L) { talkRow.visibility = View.GONE; lastTalkAskMs = now }
        }
    }

    /** 토크 프롬프트 등장 시 짧은 진동(놓치지 않게). */
    private fun buzz() {
        runCatching {
            getSystemService(android.os.Vibrator::class.java)
                ?.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ---- 렌더링 ----

    private fun render() {
        // 경과시간(서비스가 누적)
        val ms = RunBus.accumulatedMs +
            if (state == RunState.RUNNING) SystemClock.elapsedRealtime() - RunBus.runStart else 0L
        val totalSec = (ms / 1000).toInt()
        timeView.text = "%02d:%02d".format(totalSec / 60, totalSec % 60)

        // HR + 존
        val hr = RunBus.hr
        val running = state != RunState.IDLE
        if (hr < 0) {
            hrView.text = "--"; hrView.setTextColor(C_MUTED); bpmLabel.setTextColor(C_MUTED)
            zoneLabel.text = when {
                state == RunState.IDLE -> "시작 대기"
                RunBus.error != null -> RunBus.error
                else -> "센서 예열중…"
            }
            zoneLabel.setTextColor(if (state == RunState.IDLE) C_MUTED else C_AMBER)
            gauge.update(null, 0f, running)
        } else {
            val zone = Zones.zoneOf(hr)
            hrView.text = hr.toString(); hrView.setTextColor(zone.color); bpmLabel.setTextColor(zone.color)
            val tag = if (zone == HrZone.Z2) "목표 유지" else if (hr < Zones.zone2Bpm.first) "존 낮음" else "존 높음"
            zoneLabel.text = "${zone.short} ${zone.desc} · $tag"
            zoneLabel.setTextColor(zone.color)
            gauge.update(zone, Zones.gaugeFraction(hr), running)
        }

        // 페이스/거리/속도
        val speedKmh = RunBus.speedKmh
        val distanceM = RunBus.distanceM
        paceVal.text = formatPace(speedKmh)
        distVal.text = if (distanceM < 1000) "${distanceM.toInt()}m" else "%.2fkm".format(distanceM / 1000)
        spdVal.text = if (speedKmh < 0.3) "--" else "%.1f".format(speedKmh)

        if (btnRow.childCount == 0 || btnTagMismatch()) rebuildButtons()
        updateTalkPrompt(hr)
    }

    private var lastBtnState: RunState? = null
    private fun btnTagMismatch(): Boolean {
        val mismatch = lastBtnState != state
        if (mismatch) lastBtnState = state
        return mismatch
    }

    private fun formatPace(kmh: Double): String {
        if (kmh < 0.5) return "--'--\""
        val paceMin = 60.0 / kmh
        val m = paceMin.toInt()
        val s = ((paceMin - m) * 60).toInt()
        return "%d'%02d\"".format(m, s)
    }

    // ---- 제어(서비스 위임) ----

    private fun start() {
        if (!hasPerms()) { requestPerms(); return }
        val intent = Intent(this, RunService::class.java).setAction(RunService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendAction(action: String) {
        startService(Intent(this, RunService::class.java).setAction(action))
    }

    // ---- 권한 ----

    private fun hasPerms(): Boolean {
        // 필수 = 심박(BODY_SENSORS) + 위치(GPS). 이 둘이면 러닝 시작 가능.
        // 케이던스(ACTIVITY_RECOGNITION)는 선택 — 없으면 RunService가 심박만으로 시작(하드 크래시 방지).
        val body = ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
        val loc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return body && loc
    }

    private fun requestPerms() {
        val perms = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPerms()) start()
        else { zoneLabel.text = "권한 필요(심박/위치)"; zoneLabel.setTextColor(C_RED) }
    }

    // ---- helpers ----
    private fun centered() = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MIRROR = "mirror" // 시뮬 미러 모드로 실행(폰 심박 수신 + 토크테스트만)
        private val C_TEXT = Color.parseColor("#E8EAED")
        private val C_MUTED = Color.parseColor("#9AA0A6")
        private val C_GREEN = Color.parseColor("#30D158")
        private val C_AMBER = Color.parseColor("#FF9F0A")
        private val C_RED = Color.parseColor("#FF3B30")
    }
}
