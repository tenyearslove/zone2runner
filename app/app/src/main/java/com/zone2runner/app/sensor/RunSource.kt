package com.zone2runner.app.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.zone2runner.app.domain.Sample
import com.zone2runner.app.sim.RunSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 러닝 입력 소스 추상화 — RunEngine은 Sample(1Hz)만 소비하므로 소스를 교체 가능(spec-003 HrSource 사상).
 *   - SimulatedRunSource: 물리 시뮬레이터 가속 재생(실기기 없이 전체 파이프라인 구동).
 *   - LiveRunSource: 실 GPS(FusedLocation) + 심박(HrProvider, 워치 Data Layer/폰 센서).
 * onSample은 1Hz로 호출(suspend, engine.onSample과 직결). onComplete는 자연 종료(시뮬)에서만 호출.
 */
interface RunSource {
    val label: String
    val realtime: Boolean
    fun start(scope: CoroutineScope, onSample: suspend (Sample) -> Unit, onComplete: suspend () -> Unit)
    fun stop()
}

/** 심박 공급자. 최신 HR(bpm, 없으면 -1)을 제공. 구현: 워치 Data Layer / 폰 센서 / 없음. */
interface HrProvider {
    fun start()
    fun latestHr(): Int
    fun stop()
    val sourceLabel: String
}

/** HR 소스 없음(실 GPS만 테스트하거나 워치 미연결). 항상 -1. */
class NoHrProvider : HrProvider {
    override fun start() {}
    override fun latestHr(): Int = -1
    override fun stop() {}
    override val sourceLabel = "심박 없음"
}

/** 시뮬레이터 재생 소스. delayMs로 재생 속도 조절(14ms≈70배속, 1000ms=실시간). */
class SimulatedRunSource(
    private val durationMin: Int = 30,
    private val seed: Long,
    private val delayMs: Long = 14L,
) : RunSource {
    override val label = "시뮬레이션"
    override val realtime = false
    private var job: Job? = null

    override fun start(scope: CoroutineScope, onSample: suspend (Sample) -> Unit, onComplete: suspend () -> Unit) {
        val session = RunSimulator(seed).generate(durationMin)
        job = scope.launch {
            for (s in session.samples) {
                if (!isActive) return@launch
                onSample(s)
                delay(delayMs)
            }
            onComplete()
        }
    }

    override fun stop() { job?.cancel() }
}

/**
 * 실기기 소스 — FusedLocation으로 GPS(위치/속도/고도) 수집, HrProvider로 심박 결합해 1Hz Sample 생성.
 * 케이던스(spm)는 폰에서 직접 측정 불가 -> 페이스 기반 추정. 경사는 고도 변화/수평 이동으로 산출.
 * 자연 종료 없음(사용자가 stop). 실기기 GPS 필요(에뮬레이터/실내에선 값이 안정적이지 않음).
 */
class LiveRunSource(
    context: Context,
    private val hrProvider: HrProvider,
) : RunSource {
    override val label = "실센서(GPS+${hrProvider.sourceLabel})"
    override val realtime = true

    private val appCtx = context.applicationContext
    private val fused: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appCtx)
    private var job: Job? = null

    // 최신 센서 상태
    @Volatile private var lat = 37.5665
    @Volatile private var lon = 126.9780
    @Volatile private var speedMps = 0.0
    @Volatile private var slopePct = 0.0
    private var lastLoc: Location? = null
    private var smoothedSlope = 0.0

    private val locCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lat = loc.latitude; lon = loc.longitude
            if (loc.hasSpeed()) speedMps = loc.speed.toDouble()
            val prev = lastLoc
            if (prev != null && prev.hasAltitude() && loc.hasAltitude()) {
                val horiz = prev.distanceTo(loc).toDouble()
                if (horiz > 1.0) {
                    val raw = (loc.altitude - prev.altitude) / horiz * 100.0
                    smoothedSlope += (raw.coerceIn(-25.0, 25.0) - smoothedSlope) * 0.3
                    slopePct = smoothedSlope
                }
            }
            lastLoc = loc
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(scope: CoroutineScope, onSample: suspend (Sample) -> Unit, onComplete: suspend () -> Unit) {
        hrProvider.start()
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L).build()
        runCatching { fused.requestLocationUpdates(req, locCallback, Looper.getMainLooper()) }
        job = scope.launch {
            var t = 0
            while (isActive) {
                onSample(buildSample(t))
                t++
                delay(1000L)
            }
        }
    }

    private fun buildSample(t: Int): Sample {
        val paceMinKm = if (speedMps > 0.3) (1000.0 / speedMps) / 60.0 else 20.0 // 정지에 가까우면 매우 느린 페이스
        val spm = (168.0 - 4.0 * (paceMinKm - 6.0)).coerceIn(150.0, 200.0).toInt()
        return Sample(
            tSec = t,
            hr = hrProvider.latestHr(),
            paceMinKm = paceMinKm.coerceIn(2.5, 20.0),
            spm = spm,
            slopePct = slopePct,
            lat = lat,
            lon = lon,
        )
    }

    override fun stop() {
        job?.cancel()
        runCatching { fused.removeLocationUpdates(locCallback) }
        hrProvider.stop()
    }
}
