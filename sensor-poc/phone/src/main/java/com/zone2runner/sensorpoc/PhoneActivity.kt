package com.zone2runner.sensorpoc

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Location
import android.os.Bundle
import android.os.Looper
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
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable

/**
 * Phone PoC: 워치에서 오는 HR(Data Layer /hr) 수신 표시 + 위치/고도/경사 표시.
 * adr-008 PoC. 날씨는 제외.
 */
class PhoneActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private lateinit var fused: FusedLocationProviderClient
    private var lastLoc: Location? = null

    private val onMessage = MessageClient.OnMessageReceivedListener { event ->
        if (event.path == "/hr") {
            val hr = String(event.data)
            append("HR ← 워치: $hr bpm")
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val alt = loc.altitude
            val prev = lastLoc
            val slope = if (prev != null) {
                val d = prev.distanceTo(loc)
                if (d > 1f) ((loc.altitude - prev.altitude) / d * 100).let { "%.1f%%".format(it) } else "-"
            } else "-"
            append("위치: ${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)} / 고도 ${"%.1f".format(alt)}m / 경사 $slope")
            lastLoc = loc
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(32 + b.left, 32 + b.top, 32 + b.right, 32 + b.bottom); insets
        }
        val title = TextView(this).apply {
            text = "Zone2 Sensor PoC (Phone)"; textSize = 20f; setTypeface(typeface, Typeface.BOLD)
        }
        log = TextView(this).apply { setTextIsSelectable(true) }
        root.addView(title)
        root.addView(ScrollView(this).apply { addView(log) })
        setContentView(root)

        fused = LocationServices.getFusedLocationProviderClient(this)
        append("워치 앱에서 측정 시작하면 HR이 여기로 옵니다.")
        ensureLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(onMessage)
        startLocation()
    }

    override fun onPause() {
        super.onPause()
        Wearable.getMessageClient(this).removeListener(onMessage)
        fused.removeLocationUpdates(locationCallback)
    }

    private fun ensureLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L).build()
        fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startLocation()
        else append("위치 권한 거부됨 — 위치/고도/경사 미표시")
    }

    private fun append(s: String) { runOnUiThread { log.append(s + "\n\n") } }
}
