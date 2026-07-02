package com.zone2runner.sensorpoc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
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
import com.google.android.gms.wearable.Wearable

/**
 * Wear PoC: Health Services로 실시간 HR 측정 → Data Layer(MessageClient /hr)로 폰 전송.
 * adr-008 PoC. "심박이 실제로 오는지" 검증.
 */
class WearActivity : ComponentActivity() {

    private lateinit var log: TextView
    private val measureClient by lazy { HealthServices.getClient(this).measureClient }

    private val hrCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            append("availability: $availability")
        }

        override fun onDataReceived(data: DataPointContainer) {
            val points = data.getData(DataType.HEART_RATE_BPM)
            val hr = points.lastOrNull()?.value ?: return
            val bpm = hr.toInt()
            append("HR: $bpm bpm")
            sendToPhone(bpm)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        val btn = Button(this).apply { text = "측정 시작" }
        log = TextView(this).apply { textSize = 12f }
        root.addView(btn)
        root.addView(ScrollView(this).apply { addView(log) })
        setContentView(root)

        append("측정 시작을 누르면 HR 측정 + 폰 전송")
        btn.setOnClickListener { ensurePermissionAndStart() }
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
        append("HR 측정 등록...")
        measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, hrCallback)
    }

    private fun sendToPhone(bpm: Int) {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) append("연결된 폰 없음")
                for (node in nodes) {
                    Wearable.getMessageClient(this).sendMessage(node.id, "/hr", bpm.toString().toByteArray())
                }
            }
            .addOnFailureListener { append("노드 조회 실패: ${it.message}") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startMeasure()
        else append("BODY_SENSORS 권한 거부됨")
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, hrCallback) }
    }

    private fun append(s: String) { runOnUiThread { log.append(s + "\n") } }
}
