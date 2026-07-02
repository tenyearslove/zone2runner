package com.zone2runner.sensorpoc

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.wearable.Wearable

/**
 * Phone PoC: 워치 HR을 HrReceiverService(WearableListenerService)가 백그라운드에서 수신 →
 * HrStore에 저장. 이 Activity는 HrStore를 구독해 표시하고, 위치/고도/경사를 직접 수집한다.
 *
 * 텍스트를 이어붙이지 않고 고정 카드(연결/HR/위치)를 실시간 in-place 갱신.
 */
class PhoneActivity : AppCompatActivity() {

    private lateinit var fused: FusedLocationProviderClient
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var hrValue: TextView
    private lateinit var hrSub: TextView
    private lateinit var watchValue: TextView
    private lateinit var watchSub: TextView
    private lateinit var locValue: TextView
    private lateinit var locSub: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private var lastLoc: Location? = null
    private var lastFixAt = 0L
    private var loggedCount = 0
    private val logLines = ArrayDeque<String>()
    private var startElapsed = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val prev = lastLoc
            val slope = if (prev != null) {
                val d = prev.distanceTo(loc)
                if (d > 1f) "%.1f%%".format((loc.altitude - prev.altitude) / d * 100) else "~0%"
            } else "-"
            lastLoc = loc
            lastFixAt = SystemClock.elapsedRealtime()
            ui.post { renderLocation(loc, slope) }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            renderFreshness()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startElapsed = SystemClock.elapsedRealtime()
        setContentView(buildUi())
        fused = LocationServices.getFusedLocationProviderClient(this)
        renderHr(); renderFreshness()
        log("대기 중 — 워치에서 측정을 시작하세요 (수신은 백그라운드 서비스가 담당)")
        ensurePermissions()
        refreshWatchNodes()
    }

    override fun onResume() {
        super.onResume()
        HrStore.listener = { renderHr() }
        startLocation()
        refreshWatchNodes()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        HrStore.listener = null
        fused.removeLocationUpdates(locationCallback)
        ui.removeCallbacks(ticker)
    }

    // ---- UI ----

    private fun buildUi(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
        }
        ViewCompat.setOnApplyWindowInsetsListener(col) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(16) + b.left, dp(16) + b.top, dp(16) + b.right, dp(16) + b.bottom); insets
        }

        col.addView(TextView(this).apply {
            text = "Zone2 Sensor PoC"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(C_TEXT)
        })
        col.addView(TextView(this).apply {
            text = "워치 HR 백그라운드 수신 · 위치/고도/경사 · adr-008/009"
            textSize = 12f; setTextColor(C_MUTED); setPadding(0, dp(2), 0, dp(12))
        })

        val hrCard = card("심박수 (워치, 백그라운드 수신)")
        hrValue = bigValue("-- bpm", C_HR).also { hrCard.addView(it) }
        hrSub = subLine("수신 대기").also { hrCard.addView(it) }
        col.addView(hrCard, cardLp())

        val watchCard = card("워치 연결 (Data Layer)")
        watchValue = valueLine("확인 중…").also { watchCard.addView(it) }
        watchSub = subLine("").also { watchCard.addView(it) }
        col.addView(watchCard, cardLp())

        val locCard = card("위치 / 고도 / 경사")
        locValue = valueLine("위치 대기…").also { locCard.addView(it) }
        locSub = subLine("").also { locCard.addView(it) }
        col.addView(locCard, cardLp())

        val logCard = card("이벤트 로그")
        logView = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(C_MUTED) }
        logScroll = ScrollView(this).apply { addView(logView) }
        logCard.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(150)))
        col.addView(logCard, cardLp())

        return ScrollView(this).apply {
            setBackgroundColor(C_BG)
            addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(C_CARD); cornerRadius = dp(16).toFloat(); setStroke(dp(1), C_STROKE)
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        c.addView(TextView(this).apply { text = title; textSize = 12f; setTextColor(C_MUTED); letterSpacing = 0.03f })
        return c
    }

    private fun bigValue(t: String, color: Int) = TextView(this).apply {
        text = t; textSize = 40f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(color); setPadding(0, dp(2), 0, 0)
    }

    private fun valueLine(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(C_TEXT); setPadding(0, dp(4), 0, 0)
    }

    private fun subLine(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(C_MUTED); setPadding(0, dp(2), 0, 0)
    }

    // ---- 렌더링 ----

    private fun renderHr() {
        if (HrStore.hr < 0) {
            hrValue.text = "-- bpm"; hrValue.setTextColor(C_MUTED)
        } else {
            hrValue.text = "${HrStore.hr} bpm"; hrValue.setTextColor(C_HR)
        }
    }

    private fun renderLocation(loc: Location, slope: String) {
        locValue.text = "${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}"
        locSub.text = "고도 ${"%.1f".format(loc.altitude)} m   ·   경사 $slope   ·   정확도 ±${"%.0f".format(loc.accuracy)}m"
        locSub.setTextColor(C_MUTED)
    }

    private fun renderFreshness() {
        val now = SystemClock.elapsedRealtime()
        // 새 수신 로그
        if (HrStore.count > loggedCount) {
            loggedCount = HrStore.count
            log("HR ← 워치  ${HrStore.hr} bpm")
        }
        // HR 신선도
        if (HrStore.hr >= 0) {
            val ago = ((now - HrStore.lastAt) / 1000).toInt()
            val stale = ago > 5
            hrSub.text = if (stale) "⚠ ${ago}s 무수신 (끊김?)" else "방금 수신 · 총 ${HrStore.count}회 · ${ago}s 전"
            hrSub.setTextColor(if (stale) C_RED else C_GREEN)
        } else {
            hrSub.text = "수신 대기 (워치에서 측정 시작)"; hrSub.setTextColor(C_MUTED)
        }
        // 위치 신선도
        if (lastFixAt > 0L) {
            val ago = ((now - lastFixAt) / 1000).toInt()
            if (ago > 8) {
                val cur = locSub.text?.toString() ?: ""
                if (!cur.endsWith(")")) locSub.text = "$cur   (${ago}s 전)"
            }
        }
    }

    private fun refreshWatchNodes() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    watchValue.text = dot(C_RED, "연결 안 됨"); watchSub.text = "워치 페어링/워치 앱 실행 확인"
                } else {
                    watchValue.text = dot(C_GREEN, "연결됨 (${nodes.size})")
                    watchSub.text = nodes.joinToString(", ") { it.displayName }
                }
            }
            .addOnFailureListener { watchValue.text = dot(C_RED, "노드 조회 실패"); watchSub.text = it.message ?: "" }
    }

    private fun dot(color: Int, text: String): CharSequence {
        val sb = SpannableStringBuilder("● ")
        sb.setSpan(ForegroundColorSpan(color), 0, 1, 0)
        sb.append(text)
        return sb
    }

    private fun log(s: String) {
        val t = ((SystemClock.elapsedRealtime() - startElapsed) / 1000)
        logLines.addLast("[%3ds] %s".format(t, s))
        while (logLines.size > 40) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---- 권한/위치 ----

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L).build()
        fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val locIdx = permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (locIdx >= 0 && grantResults.getOrNull(locIdx) == PackageManager.PERMISSION_GRANTED) startLocation()
        else if (locIdx >= 0) { locValue.text = "위치 권한 거부됨"; locValue.setTextColor(C_RED); locSub.text = "위치/고도/경사 미표시" }
    }

    // ---- helpers ----
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun cardLp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }

    private companion object {
        val C_BG = Color.parseColor("#0E1116")
        val C_CARD = Color.parseColor("#171B22")
        val C_STROKE = Color.parseColor("#2A2F3A")
        val C_TEXT = Color.parseColor("#E8EAED")
        val C_MUTED = Color.parseColor("#9AA0A6")
        val C_HR = Color.parseColor("#FF5A5F")
        val C_GREEN = Color.parseColor("#30D158")
        val C_RED = Color.parseColor("#FF3B30")
    }
}
