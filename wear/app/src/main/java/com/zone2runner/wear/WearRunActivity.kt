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

    // 토크테스트 프롬프트(심박 높을 때 가끔 물어봄 → 폰 개인화에 반영). 설문은 별도 전체화면 TalkTestActivity.
    private var lastTalkAskMs = 0L
    private var talkActive = false // 설문 화면이 떠 있는 동안 중복 실행 방지
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
        talkActive = false // 설문 화면에서 돌아옴 → 다시 프롬프트 가능
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

        // 페이스 / 거리 / 속도 — 각 값은 WRAP_CONTENT(글자 길이만큼만 차지)라 잘리지 않는다.
        // 그룹을 가운데 정렬(거리가 중앙) + 좌우(페이스/속도)를 거리 기준 고정 여백 GAP만큼 떨어뜨린다.
        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        paceVal = metricValue(); distVal = metricValue(); spdVal = metricValue()
        val gap = 12 // 숫자 사이 여백(기존 3등분 대비 절반 수준). 취향 따라 조절.
        metrics.addView(metricCol(paceVal, "페이스"))
        metrics.addView(metricCol(distVal, "거리", gap))
        metrics.addView(metricCol(spdVal, "속도", gap))
        content.addView(metrics, centered())

        // 버튼
        btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        content.addView(btnRow, centered())

        // BOX_ALL(내접 사각형)은 원형 480px에서 실사용 폭이 ~70%로 줄어 페이스 줄바꿈/버튼 잘림 발생(실기기).
        // 콘텐츠가 세로 중앙 정렬이라 중앙 행은 원의 전체 폭을 쓸 수 있으므로 고정 패딩으로 대체.
        content.setPadding(dp(6), dp(6), dp(6), dp(6)) // 좌우 여백 축소 — 3열 숫자 폭 최대 확보(실기기 잘림 대응)
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
        includeFontPadding = false // 세로 여백 제거로 폭 대비 글자 크게
        textSize = 16f // 고정 크기 — 영역(WRAP)이 글자 길이에 맞춰 늘어난다(자동축소 대신, 짧은 숫자라 안전)
    }

    /** 값+라벨 세로 컬럼. WRAP_CONTENT라 글자 길이만큼만 차지. marginStartDp = 왼쪽 이웃과의 여백. */
    private fun metricCol(value: TextView, label: String, marginStartDp: Int = 0): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(marginStartDp)
            }
            addView(value, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(TextView(this@WearRunActivity).apply {
                text = label; textSize = 9f; setTextColor(C_MUTED); gravity = Gravity.CENTER
                isSingleLine = true
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
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

    /**
     * 토크테스트 타이밍 — 심박이 높아져 특정 구간에 '머물' 때만 가끔 묻는다(사용자 요청, 고정 주기 아님).
     *  (A) 지속 심박(폰 존)이 Zone 2 이상에 30초 이상 머물렀고, 최근 3분 안에 안 물었을 때.
     *      = 경계 근처/이상에 안정적으로 있을 때가 "이 강도 편해?" 확인이 가장 유용(active learning).
     *  (B) 오래(10분) 아예 안 물었으면 폴백 — 낮은 심박도 그 사람껜 힘들 수 있으니.
     * 조건 충족 시 전체화면 설문(TalkTestActivity)을 띄운다 — 좁은 대시보드에 끼워넣지 않는다.
     */
    private fun updateTalkPrompt() {
        val zone = phoneZone() // 폰 판정 존(지속 심박 기준). 폰 상태가 없으면 프롬프트 억제.
        val running = state == RunState.RUNNING && zone != null
        val inZone2Plus = running && zone != HrZone.Z1
        if (inZone2Plus) elevatedSec++ else elevatedSec = 0
        if (talkActive) return // 설문 화면이 떠 있는 동안엔 재실행 안 함(onResume에서 해제)
        val now = SystemClock.elapsedRealtime()
        val since = now - lastTalkAskMs
        val elevatedAsk = inZone2Plus && elevatedSec >= 30 && since > 3 * 60 * 1000L
        val fallback = running && since > 10 * 60 * 1000L
        if (elevatedAsk || fallback) {
            lastTalkAskMs = now
            talkActive = true
            runCatching { startActivity(android.content.Intent(this, TalkTestActivity::class.java)) }
        }
    }

    // ---- 렌더링 ----

    private fun render() {
        // 경과시간(서비스가 누적)
        val ms = RunBus.accumulatedMs +
            if (state == RunState.RUNNING) SystemClock.elapsedRealtime() - RunBus.runStart else 0L
        val totalSec = (ms / 1000).toInt()
        timeView.text = "%02d:%02d".format(totalSec / 60, totalSec % 60)

        // HR + 존 (adr-022: 존은 폰 판정을 미러. 큰 숫자 = 워치 순간 심박(폰 대시보드와 동일 구성)).
        val hr = RunBus.hr
        val running = state != RunState.IDLE
        val zone = phoneZone() // 폰이 보낸 지속 심박+개인 경계로 계산(없으면 null)
        if (!running) {
            hrView.text = "--"; hrView.setTextColor(C_MUTED); bpmLabel.setTextColor(C_MUTED)
            zoneLabel.text = RunBus.error ?: "시작 대기"
            zoneLabel.setTextColor(if (RunBus.error != null) C_AMBER else C_MUTED)
            gauge.update(null, 0f, false)
        } else if (zone != null) {
            // 큰 숫자 = 폰이 표시하는 순간 심박(정제됨) → 폰과 동일 값. 존 = 폰 지속 심박 기준.
            val shownHr = if (RunBus.instHr > 0) RunBus.instHr else RunBus.susHr
            hrView.text = shownHr.toString()
            hrView.setTextColor(zone.color); bpmLabel.setTextColor(zone.color)
            val tag = when { zone == HrZone.Z2 -> "목표 유지"; RunBus.susHr < RunBus.boundLo -> "존 낮음"; else -> "존 높음" }
            zoneLabel.text = "${zone.short} ${zone.desc} · $tag"
            zoneLabel.setTextColor(zone.color)
            gauge.update(zone, Zones.gaugeFraction(RunBus.susHr, RunBus.boundLo, RunBus.boundHi, RunBus.boundMax), true)
        } else if (hr > 0) {
            // 폰 판정 미수신(폰 러닝 화면 미실행 등). 폰이 피드하면 위 미러로 자동 전환되므로 불일치 걱정 없음.
            // 그 전까진 워치 자체 존(동기화된 경계/기본값)으로 표시 — 빈 화면보다 유용(사용자 요청).
            val localZone = Zones.zoneOf(hr)
            hrView.text = hr.toString(); hrView.setTextColor(localZone.color); bpmLabel.setTextColor(localZone.color)
            val tag = when { localZone == HrZone.Z2 -> "목표 유지"; hr < Zones.zone2Bpm.first -> "존 낮음"; else -> "존 높음" }
            zoneLabel.text = "${localZone.short} ${localZone.desc} · $tag"
            zoneLabel.setTextColor(localZone.color)
            gauge.update(localZone, Zones.gaugeFraction(hr), true)
        } else {
            hrView.text = "--"; hrView.setTextColor(C_MUTED); bpmLabel.setTextColor(C_MUTED)
            zoneLabel.text = "센서 예열중…"; zoneLabel.setTextColor(C_AMBER)
            gauge.update(null, 0f, true)
        }

        // 페이스/거리/속도
        val speedKmh = RunBus.speedKmh
        val distanceM = RunBus.distanceM
        paceVal.text = formatPace(speedKmh)
        distVal.text = if (distanceM < 1000) "${distanceM.toInt()}m" else "%.2fkm".format(distanceM / 1000)
        spdVal.text = if (speedKmh < 0.3) "--" else "%.1f".format(speedKmh)

        if (btnRow.childCount == 0 || btnTagMismatch()) rebuildButtons()
        updateTalkPrompt()
    }

    /** 폰이 보낸 판정 상태가 신선하면 그 지속 심박+개인 경계로 존을 계산. 없으면 null(폰 판정 대기). */
    private fun phoneZone(): HrZone? {
        val fresh = RunBus.liveMs > 0 &&
            SystemClock.elapsedRealtime() - RunBus.liveMs < LIVE_STALE_MS &&
            RunBus.susHr > 0 && RunBus.boundHi > RunBus.boundLo
        return if (fresh) Zones.zoneOf(RunBus.susHr, RunBus.boundLo, RunBus.boundHi, RunBus.boundMax) else null
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
        if (!hasPerms()) { requestPerms(); return }              // 1단계: 전경(심박/위치)
        if (needsBodyBackground()) { requestBodyBackground(); return } // 2단계: 배경 심박(화면off HR 위임)
        launchService()
    }

    /** 실제 서비스 시작(권한 게이트 통과 후). 배경 심박 거부 시에도 전경 러닝은 되도록 여기서 바로 띄운다. */
    private fun launchService() {
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
        // 1단계(전경): 심박/위치/케이던스/알림. 배경 심박은 전경 승인 뒤 '따로' 요청해야 한다(아래 2단계).
        val perms = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_FOREGROUND)
    }

    /**
     * 배경 심박(BODY_SENSORS_BACKGROUND, API33+) — Health Services가 별도 프로세스에서 우리를 대신해
     * 센서를 읽으려면(화면off 지속 HR, adr-009) 이 배경 권한 위임이 필요하다. 없으면 시작 직후
     * "WHS_PermissionPolicy: healthservices doesn't have permission BODY_SENSORS" 경고와 함께 HR이 막힌다.
     * 전경 권한이 이미 있어야 요청 가능하고, 반드시 단독으로 요청한다(전경과 함께 요청하면 무시됨).
     */
    private fun needsBodyBackground(): Boolean = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(this, "android.permission.BODY_SENSORS_BACKGROUND") != PackageManager.PERMISSION_GRANTED

    private fun requestBodyBackground() =
        ActivityCompat.requestPermissions(this, arrayOf("android.permission.BODY_SENSORS_BACKGROUND"), REQ_BODY_BG)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_FOREGROUND -> when {
                !hasPerms() -> { zoneLabel.text = "권한 필요(심박/위치)"; zoneLabel.setTextColor(C_RED) }
                needsBodyBackground() -> requestBodyBackground() // 전경 OK → 배경 심박 단독 요청
                else -> launchService()
            }
            // 배경 심박 결과(승인/거부 무관): 러닝은 시작한다. 거부 시 화면off HR이 제한될 수 있음(전경은 동작).
            REQ_BODY_BG -> launchService()
        }
    }

    // ---- helpers ----
    private fun centered() = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MIRROR = "mirror" // 시뮬 미러 모드로 실행(폰 심박 수신 + 토크테스트만)
        private const val REQ_FOREGROUND = 1 // 권한 요청 코드: 1단계 전경(심박/위치)
        private const val REQ_BODY_BG = 2    // 2단계 배경 심박(화면off HR 위임)
        private const val LIVE_STALE_MS = 8000L // 폰 판정 상태 신선도 한계(1Hz 수신, BT 끊김 관용)
        private val C_TEXT = Color.parseColor("#E8EAED")
        private val C_MUTED = Color.parseColor("#9AA0A6")
        private val C_GREEN = Color.parseColor("#30D158")
        private val C_AMBER = Color.parseColor("#FF9F0A")
        private val C_RED = Color.parseColor("#FF3B30")
    }
}
