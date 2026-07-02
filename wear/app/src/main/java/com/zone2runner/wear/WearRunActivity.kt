package com.zone2runner.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
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
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import androidx.wear.widget.BoxInsetLayout
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Zone2 Runner — 워치 러닝 대시보드.
 * 한 화면(스크롤 없음)에 HR, 현재 존, 페이스/거리/속도, 경과시간, 조작 버튼을 배치한다.
 * 베젤 게이지(ZoneGaugeView)로 현재 존을 시각화한다.
 */
class WearRunActivity : ComponentActivity() {

    private enum class State { IDLE, RUNNING, PAUSED }

    private val measureClient by lazy { HealthServices.getClient(this).measureClient }
    private lateinit var fused: FusedLocationProviderClient
    private val hrForwarder by lazy { HrForwarder(this) }
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

    // 상태
    private var state = State.IDLE
    private var hr = -1
    private var accumulatedMs = 0L
    private var runStart = 0L
    private var distanceM = 0.0
    private var speedKmh = 0.0
    private var lastLoc: Location? = null
    private var hrRegistered = false

    private val hrCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {}
        override fun onDataReceived(data: DataPointContainer) {
            val v = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value ?: return
            hr = v.toInt()
            if (state == State.RUNNING) hrForwarder.send(hr) // 폰으로 실시간 HR 전달(Data Layer)
            ui.post { render() }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else speedKmh
            val prev = lastLoc
            if (state == State.RUNNING && prev != null) {
                val d = prev.distanceTo(loc)
                if (d in 0.5f..40f) distanceM += d // 노이즈/튐 제거
            }
            lastLoc = loc
            ui.post { render() }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fused = LocationServices.getFusedLocationProviderClient(this)
        setContentView(buildUi())
        render()
    }

    override fun onResume() { super.onResume(); ui.post(ticker) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

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
            text = "00:00"; textSize = 15f; setTextColor(C_MUTED)
            typeface = Typeface.MONOSPACE
        }
        content.addView(timeView)

        // HR + BPM
        hrView = TextView(this).apply {
            text = "--"; textSize = 46f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(C_MUTED)
        }
        bpmLabel = TextView(this).apply {
            text = "BPM"; textSize = 9f; setTextColor(C_MUTED); letterSpacing = 0.15f
            gravity = Gravity.CENTER
        }
        content.addView(hrView, centered())
        content.addView(bpmLabel, centered())

        zoneLabel = TextView(this).apply {
            text = "시작 대기"; textSize = 14f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(C_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(1), 0, dp(6))
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

        box.addView(content, BoxInsetLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            boxedEdges = BoxInsetLayout.LayoutParams.BOX_ALL
            gravity = Gravity.CENTER
        })
        return box
    }

    private fun metricValue() = TextView(this).apply {
        text = "--"; textSize = 15f; setTypeface(Typeface.DEFAULT_BOLD); setTextColor(C_TEXT)
        gravity = Gravity.CENTER
    }

    private fun metricCol(value: TextView, label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            layoutParams = lp
            setPadding(dp(3), 0, dp(3), 0)
            addView(value)
            addView(TextView(this@WearRunActivity).apply {
                text = label; textSize = 9f; setTextColor(C_MUTED); gravity = Gravity.CENTER
            })
        }
    }

    private fun pill(text: String, color: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 13f; setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply {
                setColor(color); cornerRadius = dp(22).toFloat()
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun rebuildButtons() {
        btnRow.removeAllViews()
        when (state) {
            State.IDLE -> btnRow.addView(pill("시작", C_GREEN) { start() })
            State.RUNNING -> {
                btnRow.addView(pill("일시정지", C_AMBER) { pause() })
                btnRow.addView(space())
                btnRow.addView(pill("종료", C_RED) { stop() })
            }
            State.PAUSED -> {
                btnRow.addView(pill("재개", C_GREEN) { resume() })
                btnRow.addView(space())
                btnRow.addView(pill("종료", C_RED) { stop() })
            }
        }
    }

    private fun space() = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) }

    // ---- 렌더링 ----

    private fun render() {
        // 경과시간
        val ms = accumulatedMs + if (state == State.RUNNING) SystemClock.elapsedRealtime() - runStart else 0L
        val totalSec = (ms / 1000).toInt()
        timeView.text = "%02d:%02d".format(totalSec / 60, totalSec % 60)

        // HR + 존
        val running = state != State.IDLE
        if (hr < 0) {
            hrView.text = "--"; hrView.setTextColor(C_MUTED); bpmLabel.setTextColor(C_MUTED)
            zoneLabel.text = if (state == State.IDLE) "시작 대기" else "센서 예열중…"
            zoneLabel.setTextColor(if (state == State.IDLE) C_MUTED else C_AMBER)
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
        paceVal.text = formatPace(speedKmh)
        distVal.text = if (distanceM < 1000) "${distanceM.toInt()}m" else "%.2fkm".format(distanceM / 1000)
        spdVal.text = if (speedKmh < 0.3) "--" else "%.1f".format(speedKmh)

        if (btnRow.childCount == 0 || btnTagMismatch()) rebuildButtons()
    }

    private var lastBtnState: State? = null
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

    // ---- 제어 ----

    private fun start() {
        if (!hasPerms()) { requestPerms(); return }
        state = State.RUNNING
        accumulatedMs = 0L; runStart = SystemClock.elapsedRealtime()
        distanceM = 0.0; lastLoc = null; hr = -1
        hrForwarder.start()
        startSensors()
        rebuildButtons(); render()
    }

    private fun pause() {
        if (state != State.RUNNING) return
        accumulatedMs += SystemClock.elapsedRealtime() - runStart
        state = State.PAUSED
        rebuildButtons(); render()
    }

    private fun resume() {
        if (state != State.PAUSED) return
        runStart = SystemClock.elapsedRealtime()
        state = State.RUNNING
        rebuildButtons(); render()
    }

    private fun stop() {
        state = State.IDLE
        stopSensors()
        hr = -1; speedKmh = 0.0
        rebuildButtons(); render()
    }

    @SuppressLint("MissingPermission")
    private fun startSensors() {
        if (!hrRegistered) {
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, hrCallback)
            hrRegistered = true
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private fun stopSensors() {
        if (hrRegistered) {
            runCatching { measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, hrCallback) }
            hrRegistered = false
        }
        fused.removeLocationUpdates(locationCallback)
    }

    // ---- 권한 ----

    private fun hasPerms(): Boolean {
        val body = ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
        val loc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return body && loc
    }

    private fun requestPerms() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACCESS_FINE_LOCATION),
            1
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPerms()) start()
        else { zoneLabel.text = "권한 필요(심박/위치)"; zoneLabel.setTextColor(C_RED) }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSensors()
    }

    // ---- helpers ----
    private fun centered() = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val C_TEXT = Color.parseColor("#E8EAED")
        val C_MUTED = Color.parseColor("#9AA0A6")
        val C_GREEN = Color.parseColor("#30D158")
        val C_AMBER = Color.parseColor("#FF9F0A")
        val C_RED = Color.parseColor("#FF3B30")
    }
}
