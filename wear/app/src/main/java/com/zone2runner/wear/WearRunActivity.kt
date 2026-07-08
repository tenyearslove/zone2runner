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
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.wear.widget.BoxInsetLayout

/**
 * Zone2Runner — 워치 러닝 대시보드.
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

    private val state: RunState get() = RunBus.state

    private val ticker = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    private var mirror = false // 시뮬 미러 모드(폰 심박 수신, 자기 센서/서비스 안 씀)
    private var mirrorWasLive = false // 미러 세션이 한 번이라도 돌았는지 — IDLE 복귀 시 화면 닫기 판단용
    private var mirrorListener: com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mirror = intent.getBooleanExtra(EXTRA_MIRROR, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        render()
    }

    /**
     * singleTask라 폰이 다시 띄우면(새 세션의 /run/start·/run/mirror) 새 인스턴스 대신 이 인스턴스로
     * 전달된다 → 중복 스택/상태공유 오류 방지. 미러 여부가 바뀌면 리스너를 재동기화하고 다시 그린다.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newMirror = intent.getBooleanExtra(EXTRA_MIRROR, false)
        if (newMirror != mirror) {
            mirror = newMirror
            unregisterMirror()
            if (mirror) registerMirror()
        }
        mirrorWasLive = false // 새 세션 재진입 — 이전 세션의 "돌았음" 플래그로 즉시 닫히지 않게
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
                // 첫 심박에만 세션 시작(IDLE→RUNNING). 이미 돌았던 세션(mirrorWasLive)은 되살리지 않는다 —
                // 정지 직후 늦게 도착한 미러 심박이 세션을 되살려 자동닫기를 막던 레이스 방지(2026-07-09).
                if (RunBus.state == RunState.IDLE && !mirrorWasLive) { RunBus.state = RunState.RUNNING; RunBus.runStart = SystemClock.elapsedRealtime(); RunBus.accumulatedMs = 0 }
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
        unregisterMirror()
    }

    private fun unregisterMirror() {
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

        // 페이스 / 거리 / 속도 — 거리를 화면 정중앙에 '고정'하고, 페이스/속도를 거리 기준 좌우 고정 간격으로.
        // (그룹 가운데정렬은 텍스트 길이가 바뀌면 중심이 흔들려서 RelativeLayout으로 중앙만 못박음.)
        // 각 값은 WRAP_CONTENT라 길이만큼만 차지 → 거리는 중앙에서 대칭으로 커지고 좌우는 바깥으로만 늘어난다.
        paceVal = metricValue(); distVal = metricValue(); spdVal = metricValue()
        val gap = dp(12) // 거리(중앙) 기준 좌우 간격. 취향 따라 조절.
        val distCol = metricCol(distVal, "거리").apply { id = View.generateViewId() }
        val paceCol = metricCol(paceVal, "페이스")
        val spdCol = metricCol(spdVal, "속도")
        val metrics = RelativeLayout(this)
        metrics.addView(distCol, RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL) // 거리 = 화면 정중앙 고정
        })
        metrics.addView(paceCol, RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            addRule(RelativeLayout.LEFT_OF, distCol.id); rightMargin = gap // 페이스 = 거리 왼쪽 gap
        })
        metrics.addView(spdCol, RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            addRule(RelativeLayout.RIGHT_OF, distCol.id); leftMargin = gap // 속도 = 거리 오른쪽 gap
        })
        // 폭 전체(MATCH_PARENT)여야 CENTER_HORIZONTAL이 화면 중앙이 된다.
        content.addView(metrics, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

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

    /** 값+라벨 세로 컬럼. WRAP_CONTENT라 글자 길이만큼만 차지(배치는 호출부 RelativeLayout이 결정). */
    private fun metricCol(value: TextView, label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
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

    // 토크테스트 타이밍 판단은 폰으로 이관(adr-023) — 워치는 RunControlService가 /run/talk 수신 시 설문 표시만.

    // ---- 렌더링 ----

    private fun render() {
        // 미러(시뮬) 모드: 제어가 전부 폰에 있으므로 세션이 끝나면(IDLE 전환) 화면도 닫는다.
        // 폰 정지 버튼/BACK 이탈 모두 /run/stop → RunBus IDLE 경로로 들어온다. 초기 IDLE(첫 심박 전)과
        // 구분하기 위해 한 번이라도 돌았을 때만 닫는다(사용자 보고 2026-07-08: BACK 시 워치 화면이 남음).
        if (mirror) {
            if (state != RunState.IDLE) mirrorWasLive = true
            else if (mirrorWasLive) { finish(); return }
        }
        // 경과시간(서비스가 누적)
        val ms = RunBus.accumulatedMs +
            if (state == RunState.RUNNING) SystemClock.elapsedRealtime() - RunBus.runStart else 0L
        val totalSec = (ms / 1000).toInt()
        timeView.text = "%02d:%02d".format(totalSec / 60, totalSec % 60)

        // HR + 존 (adr-023: 폰이 확정한 표시 존을 그대로 그린다 — 워치는 무로직 뷰어).
        val hr = RunBus.hr
        val running = state != RunState.IDLE
        val zone = phoneZone() // 폰이 보낸 존 인덱스(신선할 때만, 없으면 null)
        if (!running) {
            hrView.text = "--"; hrView.setTextColor(C_MUTED); bpmLabel.setTextColor(C_MUTED)
            zoneLabel.text = RunBus.error ?: "시작 대기"
            zoneLabel.setTextColor(if (RunBus.error != null) C_AMBER else C_MUTED)
            gauge.update(null, 0f, false)
        } else if (zone != null) {
            // 큰 숫자/존/마커 전부 폰이 보낸 값 그대로 → 폰 화면과 구성상 항상 일치.
            val shownHr = if (RunBus.instHr > 0) RunBus.instHr else hr
            hrView.text = if (shownHr > 0) shownHr.toString() else "--"
            hrView.setTextColor(zone.color); bpmLabel.setTextColor(zone.color)
            val tag = when (zone) { HrZone.Z1 -> "존 낮음"; HrZone.Z2 -> "목표 유지"; else -> "존 높음" }
            zoneLabel.text = "${zone.short} ${zone.desc} · $tag"
            zoneLabel.setTextColor(zone.color)
            // 등폭 게이지 마커 각도 = (존인덱스-1 + 존내위치)/5 — 폰이 보낸 값의 표시 산술만
            gauge.update(zone, (zone.idx - 1 + RunBus.zoneFrac / 1000f) / 5f, true)
        } else if (hr > 0) {
            // 폰 판정 미수신/두절(8초+) — 존은 표시하지 않는다(adr-023: 워치 자체 판정 삭제). 숫자는 회색 유지.
            hrView.text = hr.toString(); hrView.setTextColor(C_MUTED); bpmLabel.setTextColor(C_MUTED)
            zoneLabel.text = "폰 동기화 중…"; zoneLabel.setTextColor(C_MUTED)
            gauge.update(null, 0f, true)
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
    }

    /** 폰이 보낸 표시 판정이 신선하면 그 존을 그대로 사용. 없으면 null(폰 동기화 대기, adr-023). */
    private fun phoneZone(): HrZone? {
        val fresh = RunBus.liveMs > 0 &&
            SystemClock.elapsedRealtime() - RunBus.liveMs < LIVE_STALE_MS
        return if (fresh) HrZone.fromIdx(RunBus.zoneIdx) else null
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
