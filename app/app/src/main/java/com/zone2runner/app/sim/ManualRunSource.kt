package com.zone2runner.app.sim

import com.zone2runner.app.domain.MPS_PER_MIN_KM
import com.zone2runner.app.domain.Profile
import com.zone2runner.app.domain.Sample
import com.zone2runner.app.sensor.RunSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 수동 페이스 시뮬 소스 — 사용자가 슬라이더로 페이스를 정하면 심박이 생리 모델로 따라온다.
 * "심박을 직접 정하는 건 말이 안 되고, 페이스를 조절하면 심박이 따라와야 한다"(사용자 요청).
 *
 * 물리: RunSimulator와 동일 방정식의 온라인 스텝 — 페이스→강도(effort) 역산,
 * HR은 목표 심박을 1차 지연(hrTau)으로 추종 + 참 상한 초과 시 Cardiac Drift 누적, 최대심박 포화.
 * 경사는 0(평지 고정 — 페이스 효과만 관찰하도록 교란 제거). 배속(delayMs)은 재생 중 변경 가능.
 */
class ManualRunSource(
    profile: Profile,
    initialPaceMinKm: Double = 7.0,
    delayMs: Long = 1000L,
    private val seed: Long = 42L,
    private val maxDurationSec: Int = 60 * 60,
) : RunSource {
    override val label = "시뮬(수동 페이스)"
    override val realtime = false

    /** 샘플 간 지연(ms) — 배속 칩으로 재생 중 변경. */
    @Volatile var delayMs: Long = delayMs

    /** 목표 페이스(min/km) — 슬라이더로 재생 중 변경. 심박은 이 값을 지연으로 따라온다. */
    @Volatile var targetPace: Double = initialPaceMinKm

    private var job: Job? = null
    private val runner = RunSimulator(seed).makeRunner(profile)
    private val rng = java.util.Random(seed)
    @Volatile private var paused = false
    override fun setPaused(paused: Boolean) { this.paused = paused }

    override fun start(scope: CoroutineScope, onSample: suspend (Sample) -> Unit, onComplete: suspend () -> Unit) {
        job = scope.launch {
            var hr = runner.resting.toDouble() // 안정심박에서 시작 → hrTau 지연으로 서서히 램프업(현실적 워밍업)
            var drift = 0.0
            val route = RouteWalk(37.5665, 126.9780, rng) // 직선+코너 경로(원형 랜덤워크 대체)
            val uAbs = runner.resting + runner.uFrac * runner.hrr
            var t = 0
            while (isActive && t < maxDurationSec) {
                while (paused && isActive) delay(100L) // 토크테스트 팝업 동안 시간 정지(spec-022)
                val pace = targetPace.coerceIn(3.5, 12.0)
                // 페이스→강도 역산(RunSimulator 페이스 식의 역): pace = basePace - 3.2*(effort-0.5)
                val effort = (0.5 + (runner.basePace - pace) / 3.2).coerceIn(0.30, 1.0)
                val effortHr = runner.resting + effort * runner.hrr
                hr += (effortHr - hr) * (1.0 / runner.hrTau)
                val excess = maxOf(0.0, effortHr - uAbs)
                drift += (runner.driftScale * excess - drift) * (1.0 / runner.driftTau)
                val hrObs = (hr + drift + rng.nextGaussian() * runner.noiseSd)
                    .coerceAtMost(runner.maxHr.toDouble())
                val spm = (168 - 4.0 * (pace - 6.0) + rng.nextGaussian() * 2.5).coerceIn(150.0, 200.0)

                route.step(MPS_PER_MIN_KM / pace)

                onSample(Sample(t, hrObs.toInt(), pace + rng.nextGaussian() * 0.05, spm.toInt(), 0.0, route.lat, route.lon))
                t++
                delay(delayMs)
            }
            onComplete()
        }
    }

    override fun stop() { job?.cancel() }
}
