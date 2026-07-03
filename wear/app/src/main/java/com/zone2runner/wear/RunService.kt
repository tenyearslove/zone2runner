package com.zone2runner.wear

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * 러닝 세션 포그라운드 서비스 — 측정(HR/GPS)과 누적(시간/거리)의 소유자.
 *
 * 왜 서비스인가(adr-009, sensor-poc HrService에서 검증): MeasureClient는 포그라운드 전용이라
 * 손목을 내려 화면이 꺼지면 HR이 끊긴다. ExerciseClient(RUNNING) + 포그라운드 서비스(지속 알림)
 * 조합이면 화면off에서도 HR 스트리밍이 유지되고, 받은 HR을 Data Layer(/hr)로 폰에 전달한다.
 * 페이스/거리는 기존 검증된 FusedLocation 계산을 서비스로 이동(ExerciseClient 네이티브 거리 전환은 후속).
 */
class RunService : Service() {

    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }
    private val fused: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private val forwarder by lazy { HrForwarder(this) }
    private var lastLoc: Location? = null
    private var exerciseStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_PAUSE -> pauseSession()
            ACTION_RESUME -> resumeSession()
            ACTION_STOP -> { stopSession(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    private fun startSession() {
        startForeground(NOTIF_ID, buildNotification("센서 예열중…"))
        RunBus.reset()
        RunBus.state = RunState.RUNNING
        RunBus.runStart = SystemClock.elapsedRealtime()
        RunBus.notifyUi()
        forwarder.start()
        startExercise()
        startLocation()
    }

    private fun pauseSession() {
        if (RunBus.state != RunState.RUNNING) return
        RunBus.accumulatedMs += SystemClock.elapsedRealtime() - RunBus.runStart
        RunBus.state = RunState.PAUSED
        RunBus.notifyUi()
        updateNotification("일시정지")
    }

    private fun resumeSession() {
        if (RunBus.state != RunState.PAUSED) return
        RunBus.runStart = SystemClock.elapsedRealtime()
        RunBus.state = RunState.RUNNING
        RunBus.notifyUi()
    }

    private fun stopSession() {
        RunBus.state = RunState.IDLE
        RunBus.notifyUi()
        stopSelf()
    }

    private fun startExercise() {
        val config = ExerciseConfig.builder(ExerciseType.RUNNING)
            // 케이던스(STEPS_PER_MINUTE)는 판정 특징(feat[4])의 실측 소스 — 폰은 측정 불가해
            // 페이스 기반 추정만 가능했음. 워치 실측을 /spm으로 폰에 전달(spec-006 특징 품질).
            .setDataTypes(setOf(DataType.HEART_RATE_BPM, DataType.STEPS_PER_MINUTE))
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false) // 거리/페이스는 FusedLocation(아래)으로 계산
            .build()
        val future = exerciseClient.startExerciseAsync(config)
        future.addListener({
            runCatching { future.get() }
                .onSuccess { exerciseStarted = true }
                .onFailure {
                    RunBus.error = "운동 시작 실패: ${it.message}"
                    RunBus.notifyUi()
                }
        }, ContextCompat.getMainExecutor(this))
    }

    private val exerciseCallback = object : ExerciseUpdateCallback {
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {
            RunBus.error = "등록 실패: ${throwable.message}"
            RunBus.notifyUi()
        }

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            if (availability is DataTypeAvailability) {
                RunBus.availability = availability.toString()
                RunBus.notifyUi()
            }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            // 케이던스(실측) → 폰으로 전달(판정 특징 feat[4] 실측화)
            update.latestMetrics.getData(DataType.STEPS_PER_MINUTE).lastOrNull()?.value?.let { spm ->
                if (RunBus.state == RunState.RUNNING) forwarder.sendSpm(spm.toInt())
            }
            val v = update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value ?: return
            val bpm = v.toInt()
            RunBus.hr = bpm
            if (RunBus.state == RunState.RUNNING) {
                forwarder.send(bpm)
                RunBus.sentCount++
            }
            RunBus.notifyUi()
            updateNotification("HR $bpm bpm · ${fmtDist(RunBus.distanceM)}")
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            if (loc.hasSpeed()) RunBus.speedKmh = loc.speed * 3.6
            val prev = lastLoc
            if (RunBus.state == RunState.RUNNING && prev != null) {
                val d = prev.distanceTo(loc)
                if (d in 0.5f..40f) RunBus.distanceM += d // 노이즈/튐 제거(기존 로직 유지)
            }
            lastLoc = loc
            RunBus.notifyUi()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        exerciseClient.setUpdateCallback(exerciseCallback)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        runCatching { fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper()) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (exerciseStarted) runCatching { exerciseClient.endExerciseAsync() }
        runCatching { exerciseClient.clearUpdateCallbackAsync(exerciseCallback) }
        runCatching { fused.removeLocationUpdates(locationCallback) }
        if (RunBus.state != RunState.IDLE) { RunBus.state = RunState.IDLE; RunBus.notifyUi() }
    }

    // ---- 알림 ----

    private fun buildNotification(text: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "러닝 세션", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Zone2 Runner")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun fmtDist(m: Double) = if (m < 1000) "${m.toInt()}m" else "%.2fkm".format(m / 1000)

    companion object {
        const val ACTION_START = "com.zone2runner.wear.START"
        const val ACTION_PAUSE = "com.zone2runner.wear.PAUSE"
        const val ACTION_RESUME = "com.zone2runner.wear.RESUME"
        const val ACTION_STOP = "com.zone2runner.wear.STOP"
        private const val CHANNEL = "run_session"
        private const val NOTIF_ID = 2001
    }
}
