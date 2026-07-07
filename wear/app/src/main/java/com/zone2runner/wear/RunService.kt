package com.zone2runner.wear

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
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
    @Volatile private var exerciseStarted = false
    @Volatile private var stopRequested = false // 시작(async) 완료 전에 종료됨 → 고아 운동 방지
    @Volatile private var lastMoveMs = 0L      // 마지막으로 움직인 시각(자동 일시정지 판단)
    @Volatile private var autoPaused = false   // 자동 일시정지 상태(수동 일시정지와 구분 — 자동만 자동재개)
    private val autoPauseAfterMs = 20_000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromRemote = intent?.getBooleanExtra(EXTRA_FROM_REMOTE, false) ?: false
        when (intent?.action) {
            ACTION_START -> startSession(fromRemote)
            ACTION_PAUSE -> pauseSession()
            ACTION_RESUME -> resumeSession()
            ACTION_STOP -> { stopSession(fromRemote); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    private fun startSession(fromRemote: Boolean = false) {
        startForeground(NOTIF_ID, buildNotification("센서 예열중…"))
        RunBus.reset()
        RunBus.state = RunState.RUNNING
        RunBus.runStart = SystemClock.elapsedRealtime()
        RunBus.notifyUi()
        forwarder.start()
        lastMoveMs = SystemClock.elapsedRealtime()
        autoPaused = false
        startExercise()
        startLocation()
        // 워치에서 직접 시작한 경우에만 폰에 시작 신호(원격 시작이면 폰이 이미 러닝 중 → 루프 방지)
        if (!fromRemote) RunLink.send(this, RunLink.PATH_START)
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

    private fun stopSession(fromRemote: Boolean = false) {
        stopRequested = true
        RunBus.state = RunState.IDLE
        RunBus.notifyUi()
        if (!fromRemote) RunLink.send(this, RunLink.PATH_STOP) // 워치에서 종료 → 폰도 종료
        stopSelf()
    }

    private fun startExercise() {
        // 케이던스(STEPS_PER_MINUTE)는 ACTIVITY_RECOGNITION 권한 필요 — 없으면 심박만으로 시작한다
        // (권한 없다고 서비스를 죽이지 않는다). 케이던스는 폰이 페이스 기반으로 폴백 추정.
        val hasActivity = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
        val types = if (hasActivity)
            setOf(DataType.HEART_RATE_BPM, DataType.STEPS_PER_MINUTE)
        else setOf(DataType.HEART_RATE_BPM)
        if (!hasActivity) { RunBus.error = "케이던스 권한 없음 → 심박만"; RunBus.notifyUi() }

        val config = ExerciseConfig.builder(ExerciseType.RUNNING)
            .setDataTypes(types)
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false) // 거리/페이스는 FusedLocation(아래)으로 계산
            .build()
        // startExerciseAsync가 권한 부족 시 동기적으로 SecurityException을 던질 수 있어 try로 감싼다
        try {
            val future = exerciseClient.startExerciseAsync(config)
            future.addListener({
                runCatching { future.get() }
                    .onSuccess {
                        exerciseStarted = true
                        // 시작이 끝나기 전에 이미 종료를 눌렀다면(레이스) 곧바로 종료 — Health Services에
                        // 고아 운동이 남아 죽은 프로세스로 HR을 흘리며 BODY_SENSORS SecurityException을 찍는 것을 막는다.
                        if (stopRequested) runCatching { exerciseClient.endExerciseAsync() }
                    }
                    .onFailure {
                        RunBus.error = "운동 시작 실패: ${it.message}"
                        RunBus.notifyUi()
                    }
            }, ContextCompat.getMainExecutor(this))
        } catch (t: Throwable) {
            RunBus.error = "운동 시작 예외: ${t.message}"
            RunBus.notifyUi()
        }
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
                // 자동 일시정지: 일정 시간 움직임 없으면 정지(수동 일시정지와 구분)
                if (SystemClock.elapsedRealtime() - lastMoveMs > autoPauseAfterMs) {
                    autoPaused = true; pauseSession(); updateNotification("자동 일시정지")
                }
            }
            RunBus.notifyUi()
            updateNotification("HR $bpm bpm · ${fmtDist(RunBus.distanceM)}")
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            // GPS 튐 방지(강건성): 저정확도(>25m) 위치는 버린다 — 실내/음영에서 수백 m 튀는 점이
            // lastLoc을 오염시켜 이후 거리/속도까지 망가뜨리는 걸 원천 차단.
            if (loc.hasAccuracy() && loc.accuracy > 25f) return
            if (loc.hasSpeed() && loc.speed in 0f..12f) {
                RunBus.speedKmh = loc.speed * 3.6 // 러닝 상한 ~43km/h
                if (RunBus.speedKmh > 1.5) { // 다시 움직임 감지
                    lastMoveMs = SystemClock.elapsedRealtime()
                    if (autoPaused && RunBus.state == RunState.PAUSED) { autoPaused = false; resumeSession() }
                }
            }
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
        stopRequested = true
        // 무조건 종료 시도(guarded). 시작이 진행 중이면 위 future.onSuccess가 stopRequested를 보고 종료하고,
        // 이미 시작됐으면 여기서 종료된다. 활성 운동이 없을 때의 end 호출은 runCatching으로 무해.
        runCatching { exerciseClient.endExerciseAsync() }
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
        const val EXTRA_FROM_REMOTE = "from_remote" // 폰이 원격 시작/종료한 경우 되쏘지 않음(루프 방지)
        private const val CHANNEL = "run_session"
        private const val NOTIF_ID = 2001
    }
}
