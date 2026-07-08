package com.zone2runner.app.sim

import com.zone2runner.app.domain.MPS_PER_MIN_KM
import com.zone2runner.app.domain.Sample
import com.zone2runner.app.sensor.RunSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 수동 가상러너 시뮬 소스(spec-022) — 사용자가 케이던스/보폭을 슬라이더로 직접 조종하면
 * 가상러너가 그 속도로 달리고, 심박은 신체 스펙 기반 생리 모델이 자동 계산한다:
 * 속도 = 케이던스 x 보폭 → 페이스 → 강도 역산 → 목표 심박 1차 지연(hrTau) 추종 +
 * 임계 초과 시 카디악 드리프트 + 최대심박 포화(ManualRunSource와 동일 방정식 계열, adr-020).
 * 심박 보정(hrOffsetBpm)은 관측 심박에 마지막에 가산 — 개인차/컨디션 재현용.
 * 평지 고정(조종 변수 효과만 관찰). 배속(delayMs)/슬라이더는 재생 중 변경 가능.
 */
class ManualVirtualRunnerSource(
    private val body: Body,
    delayMs: Long = 1000L,
    private val seed: Long = 42L,
    private val maxDurationSec: Int = 60 * 60,
) : RunSource {

    /** 신체 스펙(spec-022 FR1) — 기본값은 활성 프로필에서. basePace = 편하게 달리는 기준 페이스. */
    data class Body(val age: Int, val restingHr: Int, val maxHr: Int, val basePaceMinKm: Double) {
        val hrr: Double get() = (maxHr - restingHr).toDouble()
    }

    override val label = "시뮬(수동 러너)"
    override val realtime = false

    /** 샘플 간 지연(ms) — 배속 칩으로 재생 중 변경. */
    @Volatile var delayMs: Long = delayMs

    // 조종 슬라이더(spec-022 FR2) — 재생 중 실시간 반영
    @Volatile var targetSpm: Int = 0            // 케이던스 0~200 (0 = 정지에서 시작, 사용자 피드백)
    @Volatile var targetStrideM: Double = 1.00  // 보폭 0.60~1.50m
    @Volatile var hrOffsetBpm: Int = 0          // 심박 보정 -20~+20
    @Volatile var slopePct: Double = 0.0        // 경사 -10~+10% — 파이프라인 경사 입력(특징/예측/코칭) 테스트용

    private var job: Job? = null
    private val rng = java.util.Random(seed)
    @Volatile private var paused = false
    override fun setPaused(paused: Boolean) { this.paused = paused }

    override fun start(scope: CoroutineScope, onSample: suspend (Sample) -> Unit, onComplete: suspend () -> Unit) {
        job = scope.launch {
            var hr = body.restingHr + 0.45 * body.hrr // 가벼운 시작 강도
            var drift = 0.0
            val route = RouteWalk(37.5665, 126.9780, rng) // 직선+코너 경로(원형 랜덤워크 대체)
            val uAbs = 0.70 * body.maxHr // 진짜 임계 근사(%HRmax 70%) — 드리프트 발동 기준으로만 사용
            var t = 0
            while (isActive && t < maxDurationSec) {
                while (paused && isActive) delay(100L) // 토크테스트 팝업 동안 시간 정지(spec-022)
                val spm = targetSpm.coerceIn(0, 200)
                val stride = targetStrideM.coerceIn(0.60, 1.50)
                val speed = spm * stride                 // m/min (0 = 정지)
                val moving = speed >= 30.0               // 아주 느린 제자리걸음 미만은 정지 취급
                val pace = if (moving) (1000.0 / speed).coerceIn(3.0, 20.0) else STOP_PACE
                val slope = slopePct.coerceIn(-10.0, 10.0)
                // 페이스→강도 역산(RunSimulator 계열 공통식): pace = basePace - 3.2*(effort-0.5).
                // 정지면 강도 최소 → 심박이 안정 심박 쪽으로 회복(τ 지연).
                // 오르막은 같은 속도 유지 시 심박 비용↑(SimRunnerSource와 동일 계수: 등급 1% = effort +0.012).
                val effort = if (!moving) 0.05
                             else (0.5 + (body.basePaceMinKm - pace) / 3.2 + slope * 0.012).coerceIn(0.15, 1.05)
                val effortHr = body.restingHr + effort * body.hrr
                hr += (effortHr - hr) * (1.0 / HR_TAU)
                val excess = maxOf(0.0, effortHr - uAbs)
                drift += (DRIFT_SCALE * excess - drift) * (1.0 / DRIFT_TAU)
                val hrObs = (hr + drift + rng.nextGaussian() * NOISE_SD + hrOffsetBpm)
                    .coerceIn(35.0, body.maxHr.toDouble())

                if (moving) route.step(MPS_PER_MIN_KM / pace)

                val spmOut = if (spm <= 0) 0 else (spm + rng.nextGaussian() * 1.5).toInt().coerceAtLeast(0)
                onSample(Sample(t, hrObs.toInt(), pace, spmOut, if (moving) slope else 0.0, route.lat, route.lon))
                t++
                delay(delayMs)
            }
            onComplete()
        }
    }

    override fun stop() { job?.cancel() }

    private companion object {
        const val HR_TAU = 30.0       // 심박 반응 시상수(초)
        const val DRIFT_SCALE = 0.45  // 임계 초과분 대비 드리프트 크기
        const val DRIFT_TAU = 140.0   // 드리프트 누적 시상수(초)
        const val NOISE_SD = 0.8      // 관측 노이즈(bpm)
        const val STOP_PACE = 20.0    // 정지 sentinel(min/km) — LiveRunSource와 동일 규약(화면은 "--")
    }
}
