package com.zone2runner.sensorpoc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import com.google.android.gms.wearable.Wearable

/**
 * 포그라운드 서비스 + Health Services ExerciseClient.
 *
 * 왜 ExerciseClient인가(adr-009): MeasureClient는 포그라운드 전용이라 앱이 백그라운드로 가면
 * 측정이 스로틀/해제된다. ExerciseClient는 운동 세션 API로, 포그라운드 서비스(지속 알림)와
 * 함께 쓰면 화면을 꺼도 HR이 계속 스트리밍된다. 여기서 받은 HR을 Data Layer로 폰에 전송.
 */
class HrService : Service() {

    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("측정 준비 중…"))
        HrBus.running = true
        HrBus.reset()
        HrBus.notifyUi()
        exerciseClient.setUpdateCallback(callback)
        startExercise()
        checkPhoneNode()
        return START_STICKY
    }

    private fun startExercise() {
        val config = ExerciseConfig.builder(ExerciseType.RUNNING)
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false) // 위치는 폰에서 수집(sensor-poc). 워치 서비스는 HR만.
            .build()
        val future = exerciseClient.startExerciseAsync(config)
        future.addListener({
            runCatching { future.get() }.onFailure {
                HrBus.error = "운동 시작 실패: ${it.message}"
                HrBus.notifyUi()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private val callback = object : ExerciseUpdateCallback {
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {
            HrBus.error = "등록 실패: ${throwable.message}"
            HrBus.notifyUi()
        }

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            if (availability is DataTypeAvailability) {
                HrBus.availability = availability.toString()
                HrBus.notifyUi()
            }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val hr = update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value ?: return
            val bpm = hr.toInt()
            HrBus.hr = bpm
            HrBus.notifyUi()
            updateNotification("HR $bpm bpm (백그라운드 지속)")
            sendToPhone(bpm)
        }
    }

    private fun checkPhoneNode() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { HrBus.phoneConnected = it.isNotEmpty(); HrBus.notifyUi() }
            .addOnFailureListener { HrBus.phoneConnected = false; HrBus.notifyUi() }
    }

    private fun sendToPhone(bpm: Int) {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                HrBus.phoneConnected = nodes.isNotEmpty()
                for (node in nodes) {
                    Wearable.getMessageClient(this)
                        .sendMessage(node.id, "/hr", bpm.toString().toByteArray())
                        .addOnSuccessListener {
                            HrBus.sentCount++
                            HrBus.lastSentAt = SystemClock.elapsedRealtime()
                            HrBus.notifyUi()
                        }
                }
                HrBus.notifyUi()
            }
            .addOnFailureListener { HrBus.phoneConnected = false; HrBus.notifyUi() }
    }

    override fun onDestroy() {
        super.onDestroy()
        HrBus.running = false
        runCatching { exerciseClient.endExerciseAsync() }
        runCatching { exerciseClient.clearUpdateCallbackAsync(callback) }
        HrBus.notifyUi()
    }

    // ---- 알림 ----

    private fun buildNotification(text: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "심박 측정", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Zone2 Sensor PoC")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private companion object {
        const val CHANNEL = "hr_service"
        const val NOTIF_ID = 1001
    }
}
