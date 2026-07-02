package com.zone2runner.sensorpoc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.wear.widget.BoxInsetLayout

/**
 * Wear PoC UI: HrService(포그라운드 서비스)를 시작/중지하고, HrBus를 구독해 상태를 표시.
 * 실제 측정/전송은 서비스가 담당하므로 앱이 백그라운드로 가도 계속 동작한다(adr-009).
 *
 * 위=최종 HR 큰 숫자 / 아래=센서·폰·전송 상태를 실시간 in-place 갱신.
 */
class WearActivity : ComponentActivity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var hrView: TextView
    private lateinit var bpmLabel: TextView
    private lateinit var sensorRow: TextView
    private lateinit var linkRow: TextView
    private lateinit var sendRow: TextView
    private lateinit var button: Button

    private val ticker = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        render()
    }

    override fun onResume() {
        super.onResume()
        HrBus.listener = { render() }
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        HrBus.listener = null
        ui.removeCallbacks(ticker)
    }

    // ---- UI ----

    private fun buildUi(): BoxInsetLayout {
        val box = BoxInsetLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        hrView = TextView(this).apply {
            text = "--"; textSize = 52f; setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(C_GRAY); gravity = Gravity.CENTER
        }
        bpmLabel = TextView(this).apply {
            text = "BPM"; textSize = 11f; setTextColor(C_GRAY); letterSpacing = 0.2f; gravity = Gravity.CENTER
        }
        content.addView(hrView, lp())
        content.addView(bpmLabel, lp())

        content.addView(TextView(this).apply { setBackgroundColor(Color.parseColor("#26FFFFFF")) },
            LinearLayout.LayoutParams(dp(120), dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) })

        sensorRow = statusRow().also { content.addView(it, lp()) }
        linkRow = statusRow().also { content.addView(it, lp()) }
        sendRow = statusRow().also { content.addView(it, lp()) }

        button = Button(this).apply {
            text = "측정 시작"; textSize = 13f
            setOnClickListener { onToggle() }
        }
        content.addView(button, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        box.addView(scroll, BoxInsetLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            boxedEdges = BoxInsetLayout.LayoutParams.BOX_ALL
            gravity = Gravity.CENTER
        })
        return box
    }

    private fun statusRow() = TextView(this).apply {
        textSize = 12f; setTextColor(C_GRAY); gravity = Gravity.CENTER
    }

    // ---- 렌더링 ----

    private fun render() {
        val running = HrBus.running
        // HR
        if (HrBus.hr < 0) {
            hrView.text = "--"; hrView.setTextColor(C_GRAY); bpmLabel.setTextColor(C_GRAY)
        } else {
            hrView.text = HrBus.hr.toString()
            val c = if (running) C_HR else C_GRAY
            hrView.setTextColor(c); bpmLabel.setTextColor(c)
        }

        // 센서
        val (sTxt, sColor) = when {
            !running -> "정지됨" to C_GRAY
            HrBus.error != null -> (HrBus.error ?: "오류") to C_RED
            HrBus.availability.contains("AVAILABLE") && !HrBus.availability.contains("UN") -> "정상" to C_GREEN
            HrBus.availability.contains("ACQUIRING") -> "예열중… (손목 밀착)" to C_AMBER
            HrBus.availability.contains("OFF_BODY") -> "착용 안 됨" to C_RED
            HrBus.availability.contains("UNAVAILABLE") -> "측정 불가" to C_RED
            HrBus.hr < 0 -> "예열중…" to C_AMBER
            else -> "정상" to C_GREEN
        }
        sensorRow.text = dot(sColor, "센서  $sTxt")

        // 폰 연결
        val (lTxt, lColor) = when (HrBus.phoneConnected) {
            true -> "연결됨" to C_GREEN
            false -> "폰 없음" to C_RED
            null -> "확인 안 됨" to C_GRAY
        }
        linkRow.text = dot(lColor, "폰  $lTxt")

        // 전송
        if (HrBus.sentCount == 0) {
            sendRow.text = dot(C_GRAY, "전송  -")
        } else {
            val agoSec = ((SystemClock.elapsedRealtime() - HrBus.lastSentAt) / 1000).toInt()
            val fresh = agoSec <= 3
            sendRow.text = dot(if (fresh) C_GREEN else C_AMBER, "전송  ${HrBus.sentCount}회 · ${agoSec}s 전")
        }

        button.text = if (running) "측정 중지" else "측정 시작"
    }

    private fun dot(color: Int, text: String): CharSequence {
        val sb = android.text.SpannableStringBuilder("● ")
        sb.setSpan(android.text.style.ForegroundColorSpan(color), 0, 1, 0)
        sb.append(text)
        return sb
    }

    // ---- 제어 ----

    private fun onToggle() {
        if (HrBus.running) stopService(Intent(this, HrService::class.java))
        else ensurePermissionAndStart()
    }

    private fun ensurePermissionAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.BODY_SENSORS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isEmpty()) startService()
        else ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
    }

    private fun startService() {
        ContextCompat.startForegroundService(this, Intent(this, HrService::class.java))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 센서/활동 권한만 필수. 알림 권한 거부는 서비스 동작엔 무방(알림만 숨김).
        val bodyIdx = permissions.indexOf(Manifest.permission.BODY_SENSORS)
        val bodyOk = bodyIdx < 0 || grantResults.getOrNull(bodyIdx) == PackageManager.PERMISSION_GRANTED
        if (bodyOk) startService()
        else { HrBus.error = "BODY_SENSORS 권한 거부됨"; render() }
    }

    // ---- helpers ----
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp() = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

    private companion object {
        val C_HR = Color.parseColor("#FF5A5F")
        val C_GREEN = Color.parseColor("#30D158")
        val C_AMBER = Color.parseColor("#FF9F0A")
        val C_RED = Color.parseColor("#FF3B30")
        val C_GRAY = Color.parseColor("#9AA0A6")
    }
}
