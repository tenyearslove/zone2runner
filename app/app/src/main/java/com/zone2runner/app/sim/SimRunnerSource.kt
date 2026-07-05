package com.zone2runner.app.sim

import com.zone2runner.app.domain.LiveState
import com.zone2runner.app.domain.MPS_PER_MIN_KM
import com.zone2runner.app.domain.Sample
import com.zone2runner.app.domain.VirtualRunner
import com.zone2runner.app.domain.ZoneJudgment
import com.zone2runner.app.pipeline.TalkState
import com.zone2runner.app.sensor.RunSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * 폐루프 가상러너 시뮬 소스 — 엔진(물리)과 러너(특성)를 분리.
 *
 * VirtualRunner의 신체/스타일/코칭 반응성대로 매 초 심박·페이스를 생성하고,
 * 앱의 판정(onFeedback)에 반응해 페이스를 조절하며, 내부적으로 토크테스트에 응답한다(onTalkTest).
 * → 코칭→반응→심박→토크테스트→개인화 학습의 폐루프가 시뮬 안에서 돈다.
 *
 * 토크테스트는 "그 러너의 진짜 임계(trueUpperBpm) 대비 현재 심박"으로 응답한다 —
 * 심박이 숫자상 낮아도 그 사람 임계보다 위면 "벅참"이 나온다(개인마다 다름).
 */
class SimRunnerSource(
    private val runner: VirtualRunner,
    delayMs: Long = 14L,
    private val seed: Long = 42L,
    private val maxDurationSec: Int = 40 * 60,
    private val onTalkTest: ((TalkState) -> Unit)? = null,
) : RunSource {
    override val label = "가상러너 · ${runner.name}"
    override val realtime = false

    @Volatile var delayMs: Long = delayMs
    private var job: Job? = null
    private val rng = Random(seed)
    @Volatile private var lastJudgment: ZoneJudgment? = null

    override fun onFeedback(state: LiveState) { lastJudgment = state.judgment }

    private fun gauss(sd: Double) = rng.nextGaussian() * sd

    override fun start(scope: CoroutineScope, onSample: suspend (Sample) -> Unit, onComplete: suspend () -> Unit) {
        job = scope.launch {
            val uAbs = runner.trueUpperBpm
            // 러너가 지향하는 강도(임계 부근). 초반 과속형(discipline 낮음)은 더 높게 시작.
            val targetEffortBase = ((uAbs - runner.restingHr) / runner.hrr).coerceIn(0.35, 0.85)
            var effort = targetEffortBase + (1 - runner.pacingDiscipline) * 0.12
            var hr = runner.restingHr + effort * runner.hrr
            var drift = 0.0
            var lat = 37.5665; var lon = 126.9780; var heading = 0.0
            var lastTalk = -999
            for (t in 0 until maxDurationSec) {
                if (!isActive) return@launch

                // 목표 강도: 임계 부근 지향 + 코칭 반응 + 자기조절
                var target = targetEffortBase + gauss(0.02)
                // 규율 낮으면 초반에 과속(시간 지나며 완화)
                if (t < 600) target += (1 - runner.pacingDiscipline) * (0.12 * (1 - t / 600.0))
                // 앱 코칭(판정)에 반응 — 반응성만큼
                when (lastJudgment) {
                    ZoneJudgment.ABOVE -> target -= runner.coachingResponsiveness * 0.09
                    ZoneJudgment.BELOW -> target += runner.coachingResponsiveness * 0.06
                    else -> {}
                }
                // 자기조절: 진짜 임계 훨씬 위로 지속되면 체감상 스스로 낮춤
                val over = (hr - uAbs) / runner.hrr
                if (over > 0.05) target -= over * 0.5
                effort += (target.coerceIn(0.30, 1.05) - effort) * 0.05
                effort = effort.coerceIn(0.30, 1.05)

                // 심박: 1차 지연 + 임계 초과분 드리프트, 최대심박 포화
                val effHr = runner.restingHr + effort * runner.hrr
                hr += (effHr - hr) * (1.0 / runner.hrLagSec)
                val excess = maxOf(0.0, effHr - uAbs)
                drift += (runner.driftRate * excess - drift) * (1.0 / 140.0)
                val hrObs = (hr + drift + gauss(0.7)).coerceAtMost(runner.maxHr.toDouble())

                val pace = (runner.basePaceMinKm - 3.2 * (effort - 0.5) + gauss(0.05)).coerceIn(3.5, 12.0)
                val spm = (runner.cadenceBase - 4.0 * (pace - 6.0) + gauss(2.5)).coerceIn(150.0, 200.0)
                val mps = MPS_PER_MIN_KM / pace
                heading += gauss(0.05) + 0.02
                lat += (mps * cos(heading)) / 111_320.0
                lon += (mps * sin(heading)) / (111_320.0 * cos(Math.toRadians(lat)))

                onSample(Sample(t, hrObs.toInt(), pace, spm.toInt(), 0.0, lat, lon))

                // 내부 토크테스트(90초마다, 워밍업 후): 진짜 임계 대비 현재 심박으로 응답
                if (t - lastTalk >= 90 && t > 120) {
                    lastTalk = t
                    val d = hrObs - uAbs + gauss(runner.talkNoise * runner.hrr) // 주관 흔들림
                    val st = when { // 진짜 임계 대비 심박차 → 5단계 응답(spec-016)
                        d < -16 -> TalkState.VERY_COMFORTABLE
                        d < -6 -> TalkState.COMFORTABLE
                        d <= 6 -> TalkState.BORDERLINE
                        d <= 16 -> TalkState.HARD
                        else -> TalkState.VERY_HARD
                    }
                    onTalkTest?.invoke(st)
                }
                delay(delayMs)
            }
            onComplete()
        }
    }

    override fun stop() { job?.cancel() }
}
