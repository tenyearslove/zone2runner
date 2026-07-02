package com.zone2runner.app.sensor

import com.zone2runner.app.domain.Sample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * 가짜 라이브 소스 — 워치/실기기 없이도 "실제로 러닝 중인 것처럼" 실시간(1Hz) 합성 데이터를 폰에 주입.
 * QA 테스트 가능성(외부 입력을 통제해 파이프라인 검증) + 개발/시연 편의를 위한 모드.
 *
 * 시뮬 재생(SimulatedRunSource, 70배속 사전생성)과 다른 점:
 *   - 실시간(1Hz)으로 진행하고, GPS 좌표가 실제로 이동한다(지도에서 움직임 확인 가능).
 *   - 심박/속도 "범위"를 사용자가 지정한다. 속도로부터 이동거리를 만들어 페이스가 계산된다.
 */
class MockRunSource(
    private val cfg: MockConfig,
    seed: Long,
    private val stepDelayMs: Long = 1000L,
) : RunSource {
    override val label = "가짜 라이브(테스트)"
    override val realtime = true

    private val rng = Random(seed)
    private var job: Job? = null

    // 상태(랜덤워크로 범위 내에서 자연스럽게 변동)
    private var lat = cfg.startLat
    private var lon = cfg.startLon
    private var heading = 0.0
    private var hr = (cfg.hrMin + cfg.hrMax) / 2.0
    private var speedKmh = (cfg.speedMinKmh + cfg.speedMaxKmh) / 2.0

    override fun start(scope: CoroutineScope, onSample: suspend (Sample) -> Unit, onComplete: suspend () -> Unit) {
        job = scope.launch {
            var t = 0
            while (isActive) {
                onSample(nextSample(t))
                t++
                delay(stepDelayMs)
            }
        }
    }

    /** 1초분 합성 샘플 생성(순수, 테스트 대상). HR/속도는 범위 내 랜덤워크, GPS는 속도만큼 이동. */
    internal fun nextSample(t: Int): Sample {
        // 심박: 범위 내 랜덤워크(가장자리에서 중앙으로 살짝 되돌림)
        hr += rng.nextGaussian() * 1.5 + pullToCenter(hr, cfg.hrMin.toDouble(), cfg.hrMax.toDouble())
        hr = hr.coerceIn(cfg.hrMin.toDouble(), cfg.hrMax.toDouble())

        // 속도(km/h): 범위 내 랜덤워크
        speedKmh += rng.nextGaussian() * 0.35 + pullToCenter(speedKmh, cfg.speedMinKmh, cfg.speedMaxKmh) * 0.1
        speedKmh = speedKmh.coerceIn(cfg.speedMinKmh, cfg.speedMaxKmh)

        // GPS 이동: 속도(m/s)만큼 heading 방향으로 전진(완만히 휘어 루프)
        val mps = speedKmh / 3.6
        heading += rng.nextGaussian() * 0.05 + 0.012
        lat += (mps * cos(heading)) / 111_320.0
        lon += (mps * sin(heading)) / (111_320.0 * cos(Math.toRadians(lat)))

        // 페이스는 속도에서 계산(min/km). 케이던스는 페이스에서 추정.
        val paceMinKm = (60.0 / speedKmh.coerceAtLeast(0.3)).coerceIn(2.5, 20.0)
        val spm = (168.0 - 4.0 * (paceMinKm - 6.0)).coerceIn(150.0, 200.0).toInt()

        return Sample(
            tSec = t,
            hr = hr.toInt(),
            paceMinKm = paceMinKm,
            spm = spm,
            slopePct = 0.0,
            lat = lat,
            lon = lon,
        )
    }

    /** 범위 중앙으로 살짝 끌어당기는 복원력(가장자리 고착 방지). */
    private fun pullToCenter(v: Double, lo: Double, hi: Double): Double {
        val mid = (lo + hi) / 2.0
        return (mid - v) * 0.05
    }

    override fun stop() { job?.cancel() }
}

/** 가짜 라이브 설정 — 심박/속도 범위 + 시작 좌표. */
data class MockConfig(
    val hrMin: Int = 120,
    val hrMax: Int = 155,
    val speedMinKmh: Double = 8.0,
    val speedMaxKmh: Double = 11.0,
    val startLat: Double = 37.5665,
    val startLon: Double = 126.9780,
) {
    companion object {
        val PRESETS: List<Pair<String, MockConfig>> = listOf(
            "가벼운 조깅" to MockConfig(hrMin = 110, hrMax = 135, speedMinKmh = 7.0, speedMaxKmh = 9.0),
            "Zone 2 템포" to MockConfig(hrMin = 130, hrMax = 150, speedMinKmh = 9.0, speedMaxKmh = 11.0),
            "고강도 인터벌" to MockConfig(hrMin = 155, hrMax = 182, speedMinKmh = 12.0, speedMaxKmh = 16.0),
        )
    }
}
