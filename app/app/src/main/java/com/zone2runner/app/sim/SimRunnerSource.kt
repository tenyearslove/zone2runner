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
 * 폐루프 가상러너 시뮬 소스 — 엔진(물리)과 러너(특성)를 분리 (spec-019 강화).
 *
 * VirtualRunner의 신체/스타일/환경대로 매 초 심박·페이스·경사를 생성하고, 앱의 판정(onFeedback)에
 * (지연을 두고) 반응해 페이스를 조절하며, 내부적으로 토크테스트에 응답한다(onTalkTest).
 * → 코칭→반응→심박→토크테스트→개인화 학습의 폐루프가 시뮬 안에서 돈다.
 *
 * 사람 같은 randomness(모두 seed 기반, 재현 가능):
 *   워밍업 · 지형(언덕→경사→effort/HR) · 카디악 드리프트(+기온) · 피로 누적 · 미세 변동 ·
 *   돌발 서지/감속 · 코칭 반응 지연 · 자기조절 · 센서 아티팩트(드롭아웃/스파이크) · 토크테스트 편향.
 *
 * 토크테스트는 "그 러너의 진짜 임계(trueUpperBpm) 대비 현재 심박"으로 응답한다(개인마다 다름).
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

    /** 지형 프로파일: 언덕 정도(hilliness)에 비례한 진폭으로 완만히 변하는 경사(%) 시퀀스. */
    private fun terrainProfile(n: Int): DoubleArray {
        val amp = runner.terrainHilliness * 8.0 // 최대 ±~8% 등급
        if (amp < 0.1) return DoubleArray(n)
        // 서로 다른 주기의 사인 합 + 세그먼트 노이즈로 자연스러운 오르막/내리막
        val ph1 = rng.nextDouble() * 6.28; val ph2 = rng.nextDouble() * 6.28
        val raw = DoubleArray(n) { i ->
            (0.6 * sin(i / 220.0 + ph1) + 0.4 * sin(i / 70.0 + ph2)) * amp
        }
        // 이동평균 스무딩(급격한 계단 방지)
        val out = DoubleArray(n)
        val w = 15
        for (i in 0 until n) {
            var s = 0.0; var c = 0
            for (j in (i - w)..(i + w)) if (j in 0 until n) { s += raw[j]; c++ }
            out[i] = (s / c).coerceIn(-12.0, 12.0)
        }
        return out
    }

    override fun start(scope: CoroutineScope, onSample: suspend (Sample) -> Unit, onComplete: suspend () -> Unit) {
        job = scope.launch {
            val uAbs = runner.trueUpperBpm
            val restingHr = runner.restingHr + runner.dayCondition * -2.0 // 컨디션 좋으면 RHR 약간 낮음
            val targetEffortBase = ((uAbs - restingHr) / runner.hrr).coerceIn(0.35, 0.85)
            var effort = targetEffortBase + (1 - runner.pacingDiscipline) * 0.12
            var hr = restingHr + effort * runner.hrr
            var drift = 0.0
            var lat = 37.5665; var lon = 126.9780; var heading = 0.0
            var lastTalk = -999
            val slope = terrainProfile(maxDurationSec)
            val heat = ((runner.tempC - 18.0) / 14.0).coerceIn(-0.5, 1.0) // 18℃ 기준 더위 계수
            // 코칭 반응을 지연시키기 위한 짧은 버퍼(coachingLagSec 전의 판정에 반응)
            val judgeDelay = ArrayDeque<ZoneJudgment?>()
            var surgeLeft = 0; var surgeMag = 0.0

            for (t in 0 until maxDurationSec) {
                if (!isActive) return@launch

                // ---- 목표 강도 결정 ----
                var target = targetEffortBase + gauss(0.02)
                // 워밍업/초반 과속(규율 낮을수록, 시간 감쇠)
                if (t < 600) target += (1 - runner.pacingDiscipline) * (0.12 * (1 - t / 600.0))
                // 코칭 반응(coachingLagSec 지연): 지연 버퍼에서 과거 판정을 꺼내 반응
                judgeDelay.addLast(lastJudgment)
                val delayed = if (judgeDelay.size > runner.coachingLagSec.toInt().coerceAtLeast(1)) judgeDelay.removeFirst() else null
                when (delayed) {
                    ZoneJudgment.ABOVE -> target -= runner.coachingResponsiveness * 0.09
                    ZoneJudgment.BELOW -> target += runner.coachingResponsiveness * 0.06
                    else -> {}
                }
                // 자기조절: 진짜 임계 훨씬 위로 지속되면 스스로 낮춤
                val over = (hr - uAbs) / runner.hrr
                if (over > 0.05) target -= over * 0.5
                // 돌발 서지/감속(신호등·추월·오르막 회피): surgeProneness 확률로 발동, 수초 지속
                if (surgeLeft <= 0 && rng.nextDouble() < runner.surgeProneness * 0.01) {
                    surgeLeft = 3 + rng.nextInt(8); surgeMag = gauss(0.14)
                }
                if (surgeLeft > 0) { target += surgeMag; surgeLeft-- }

                effort += (target.coerceIn(0.30, 1.10) - effort) * 0.05
                effort = effort.coerceIn(0.30, 1.10)

                // ---- 심박 생성 ----
                // 경사: 오르막은 같은 페이스에 심박 비용↑(effort에 경사 부하 가산)
                val slopeLoad = slope[t] * 0.012 // 등급 1%당 effort +0.012 상당
                // 피로: 후반일수록 같은 effort의 HR 비용↑
                val fatigue = runner.fatigueRate * (t / 1800.0)
                val effHr = restingHr + (effort + slopeLoad + fatigue) * runner.hrr
                hr += (effHr - hr) * (1.0 / runner.hrLagSec)
                // 드리프트: 임계 초과분 + 더위 가중
                val excess = maxOf(0.0, effHr - uAbs)
                val driftTarget = runner.driftRate * excess * (1.0 + runner.driftHeatK * heat)
                drift += (driftTarget - drift) * (1.0 / 140.0)
                var hrObs = (hr + drift + gauss(runner.hrNoiseSd)).coerceIn(35.0, runner.maxHr.toDouble())

                // ---- 센서 아티팩트(강건성 검증) ----
                var emitHr = hrObs.toInt()
                if (rng.nextDouble() < runner.sensorDropoutRate) emitHr = 0          // 접촉 불량(무효 → 가드/브리지)
                else if (rng.nextDouble() < runner.sensorSpikeRate) emitHr = 230 + rng.nextInt(30) // PPG 글리치: 생리범위 밖 스파이크(이상치 기각 검증)

                // ---- 페이스/케이던스/경로 ----
                // 오르막이면 같은 effort라도 페이스가 느려짐(경사 저항)
                val pace = (runner.basePaceMinKm - 3.2 * (effort - 0.5) + slope[t] * 0.06 + gauss(0.05)).coerceIn(3.0, 13.0)
                val spm = (runner.cadenceBase - 4.0 * (pace - 6.0) + gauss(2.5)).coerceIn(150.0, 200.0)
                val mps = MPS_PER_MIN_KM / pace
                heading += gauss(0.05) + 0.02
                lat += (mps * cos(heading)) / 111_320.0
                lon += (mps * sin(heading)) / (111_320.0 * cos(Math.toRadians(lat)))

                onSample(Sample(t, emitHr, pace, spm.toInt(), slope[t], lat, lon))

                // ---- 내부 토크테스트(90초마다, 워밍업 후): 진짜 임계 대비 현재 심박 + 개인 편향/흔들림 ----
                if (t - lastTalk >= 90 && t > 120) {
                    lastTalk = t
                    val d = hrObs - uAbs + (runner.talkBias + gauss(runner.talkNoise)) * runner.hrr
                    val st = when {
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
