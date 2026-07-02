package com.zone2runner.sensorpoc

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import androidx.wear.widget.BoxInsetLayout
import com.google.android.gms.wearable.Wearable

/**
 * Wear PoC: Health Services로 실시간 HR 측정 → Data Layer(MessageClient /hr)로 폰 전송.
 * adr-008 PoC. "심박이 실제로 오는지" 검증.
 *
 * UI 원칙(디버깅 최적화):
 *  - BoxInsetLayout으로 원형 화면 안전영역(내접 사각형)에 배치 → 하단 잘림 방지.
 *  - 위: 최종 HR 큰 숫자. 아래: 센서/연결/전송 상태를 실시간 in-place 갱신.
 *  - 예열(ACQUIRING) 상태를 명확히 표시 → "안 되는 줄 알고 다시 누르는" 문제 제거.
 *  - 시작/중지 토글 + measuring 플래그로 콜백 중복 등록 방지.
 */
class WearActivity : ComponentActivity() {

    private val measureClient by lazy { HealthServices.getClient(this).measureClient }
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var hrView: TextView
    private lateinit var bpmLabel: TextView
    private lateinit var sensorRow: TextView
    private lateinit var linkRow: TextView
    private lateinit var sendRow: TextView
    private lateinit var button: Button

    // 상태
    private var measuring = false
    private var lastHr = -1
    private var lastHrAt = 0L        // SystemClock.elapsedRealtime
    private var sentCount = 0
    private var lastSentAt = 0L
    private var phoneConnected: Boolean? = null
    private var availability: DataTypeAvailability = DataTypeAvailability.UNKNOWN

    private val hrCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, avail: Availability) {
            if (avail is DataTypeAvailability) {
                availability = avail
                ui.post { renderStatus() }
            }
        }

        override fun onDataReceived(data: DataPointContainer) {
            val hr = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value ?: return
            val bpm = hr.toInt()
            lastHr = bpm
            lastHrAt = SystemClock.elapsedRealtime()
            ui.post { renderHr() }
            sendToPhone(bpm)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            renderStatus()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // PoC 디버깅 중 화면 꺼짐 방지
        setContentView(buildUi())
        renderHr()
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    // ---- UI 구성 ----

    private fun buildUi(): BoxInsetLayout {
        val box = BoxInsetLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        // 위: HR 큰 숫자
        hrView = TextView(this).apply {
            text = "--"
            textSize = 52f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(C_GRAY)
            gravity = Gravity.CENTER
        }
        bpmLabel = TextView(this).apply {
            text = "BPM"
            textSize = 11f
            setTextColor(C_GRAY)
            letterSpacing = 0.2f
            gravity = Gravity.CENTER
        }
        content.addView(hrView, lp())
        content.addView(bpmLabel, lp())

        // 구분선
        content.addView(TextView(this).apply {
            setBackgroundColor(Color.parseColor("#26FFFFFF"))
        }, LinearLayout.LayoutParams(dp(120), dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) })

        // 아래: 상태 3줄
        sensorRow = statusRow().also { content.addView(it, lp()) }
        linkRow = statusRow().also { content.addView(it, lp()) }
        sendRow = statusRow().also { content.addView(it, lp()) }

        // 버튼
        button = Button(this).apply {
            text = "측정 시작"
            textSize = 13f
            setOnClickListener { onToggle() }
        }
        content.addView(button, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })

        // 스크롤 가능 + 내접 사각형 안에 배치(원형 하단 잘림 방지)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val boxParams = BoxInsetLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            boxedEdges = BoxInsetLayout.LayoutParams.BOX_ALL
            gravity = Gravity.CENTER
        }
        box.addView(scroll, boxParams)
        return box
    }

    private fun statusRow() = TextView(this).apply {
        textSize = 12f
        setTextColor(C_GRAY)
        gravity = Gravity.CENTER
    }

    // ---- 렌더링 ----

    private fun renderHr() {
        if (lastHr < 0) {
            hrView.text = "--"
            hrView.setTextColor(C_GRAY)
            bpmLabel.setTextColor(C_GRAY)
        } else {
            hrView.text = lastHr.toString()
            val c = if (measuring) C_HR else C_GRAY
            hrView.setTextColor(c)
            bpmLabel.setTextColor(c)
        }
    }

    private fun renderStatus() {
        // 센서
        val (sTxt, sColor) = when {
            !measuring -> "정지됨" to C_GRAY
            availability == DataTypeAvailability.AVAILABLE -> "정상" to C_GREEN
            availability == DataTypeAvailability.ACQUIRING -> "예열중… (손목 밀착)" to C_AMBER
            availability == DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY -> "착용 안 됨" to C_RED
            availability == DataTypeAvailability.UNAVAILABLE -> "측정 불가" to C_RED
            else -> "대기…" to C_AMBER
        }
        sensorRow.text = dot(sColor, "센서  $sTxt")

        // 폰 연결
        val (lTxt, lColor) = when (phoneConnected) {
            true -> "연결됨" to C_GREEN
            false -> "폰 없음" to C_RED
            null -> "확인 안 됨" to C_GRAY
        }
        linkRow.text = dot(lColor, "폰  $lTxt")

        // 전송
        if (sentCount == 0) {
            sendRow.text = dot(C_GRAY, "전송  -")
        } else {
            val agoSec = ((SystemClock.elapsedRealtime() - lastSentAt) / 1000).toInt()
            val fresh = agoSec <= 3
            sendRow.text = dot(if (fresh) C_GREEN else C_AMBER, "전송  ${sentCount}회 · ${agoSec}s 전")
        }

        button.text = if (measuring) "측정 중지" else "측정 시작"
    }

    private fun dot(color: Int, text: String): CharSequence {
        val sb = android.text.SpannableStringBuilder("● ")
        sb.setSpan(android.text.style.ForegroundColorSpan(color), 0, 1, 0)
        sb.append(text)
        return sb
    }

    // ---- 측정 제어 ----

    private fun onToggle() {
        if (measuring) stopMeasure() else ensurePermissionAndStart()
    }

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BODY_SENSORS), 1)
        } else {
            startMeasure()
        }
    }

    private fun startMeasure() {
        if (measuring) return // 중복 등록 방지
        measuring = true
        availability = DataTypeAvailability.ACQUIRING
        lastHr = -1
        measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, hrCallback)
        checkPhoneNode()
        renderHr()
        renderStatus()
    }

    private fun stopMeasure() {
        if (!measuring) return
        measuring = false
        runCatching {
            measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, hrCallback)
        }
        availability = DataTypeAvailability.UNKNOWN
        renderHr()
        renderStatus()
    }

    private fun checkPhoneNode() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                phoneConnected = nodes.isNotEmpty()
                ui.post { renderStatus() }
            }
            .addOnFailureListener {
                phoneConnected = false
                ui.post { renderStatus() }
            }
    }

    private fun sendToPhone(bpm: Int) {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                phoneConnected = nodes.isNotEmpty()
                for (node in nodes) {
                    Wearable.getMessageClient(this)
                        .sendMessage(node.id, "/hr", bpm.toString().toByteArray())
                        .addOnSuccessListener {
                            sentCount++
                            lastSentAt = SystemClock.elapsedRealtime()
                            ui.post { renderStatus() }
                        }
                }
                ui.post { renderStatus() }
            }
            .addOnFailureListener {
                phoneConnected = false
                ui.post { renderStatus() }
            }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startMeasure()
        else {
            availability = DataTypeAvailability.UNAVAILABLE
            renderStatus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, hrCallback) }
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
