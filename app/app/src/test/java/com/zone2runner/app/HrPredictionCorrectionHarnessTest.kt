package com.zone2runner.app

import com.zone2runner.app.coaching.RuleCoach
import com.zone2runner.app.domain.Profile
import com.zone2runner.app.domain.VirtualRunner
import com.zone2runner.app.pipeline.HrDynamics
import com.zone2runner.app.pipeline.RunEngine
import com.zone2runner.app.sim.SimRunnerSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 예측 온라인 개인 보정 효과 오프라인 검증 (spec-018/adr-019, HANDOFF 우선순위 #2).
 *
 * 질문: 기본 NN(HrDynamics, 시뮬 평균 러너 학습)은 개인 동역학과 어긋나는데, 온라인 보정
 * (HrPredictionLearner, LMS)이 그 오차를 실제로 줄이는가?
 *
 * 방법(prior_experiment.py의 예측판): 기본 모델과 심박 반응(hrLagSec)/드리프트가 다른 프리셋
 * 러너들을 폐루프 시뮬(SimRunnerSource)로 RunEngine에 통과시키고, 세션마다 보정 가중치를
 * 다음 세션 prior로 넘긴다(LearnedDynamics). 마지막에 base RMSE vs corr RMSE를 비교.
 * 정직성: 학습은 페이스 유지 샘플만(예측 조건부), RMSE는 그 부분집합에서 base/corr 동일 기준.
 */
class HrPredictionCorrectionHarnessTest {

    private fun loadModel(): HrDynamics? {
        val f = listOf(File("src/main/assets/hr_dynamics.json"), File("app/src/main/assets/hr_dynamics.json"))
            .firstOrNull { it.exists() } ?: return null
        return runCatching { HrDynamics.fromJsonString(f.readText()) }.getOrNull()
    }

    /** 러너 1명, K세션. 가중치를 세션 간 이월. 마지막 세션 RMSE(base,corr) 반환. */
    private fun runRunner(dyn: HrDynamics, r: VirtualRunner, sessions: Int): HrPredictionLearnerResult = runBlocking {
        val profile = Profile.default(r.age, r.restingHr).copy(maxHr = r.maxHr)
        var predW: DoubleArray? = null
        var last = HrPredictionLearnerResult(0.0, 0.0, 0.0, 0.0, 0)
        for (i in 0 until sessions) {
            val engine = RunEngine(profile, dyn, RuleCoach(), coachScope = null, priorPredWeights = predW)
            val source = SimRunnerSource(r, delayMs = 0L, seed = 100L + i, maxDurationSec = 30 * 60,
                onTalkTest = { engine.observeTalkTest(it) })
            val done = CompletableDeferred<Unit>()
            source.start(this,
                onSample = { s -> val st = engine.onSample(s); source.onFeedback(st) },
                onComplete = { done.complete(Unit) })
            done.await()
            predW = engine.predWeights()
            val rm = engine.predRmse()
            last = HrPredictionLearnerResult(rm.base30, rm.corr30, rm.base60, rm.corr60, engine.predUpdates())
        }
        last
    }

    data class HrPredictionLearnerResult(
        val base30: Double, val corr30: Double, val base60: Double, val corr60: Double, val updates: Int,
    )

    @Test fun onlineCorrection_reducesPredictionRmse_acrossRunners() {
        val dyn = loadModel()
        assertNotNull("hr_dynamics.json 모델 로드", dyn)
        dyn!!

        // 기본 모델과 동역학이 다른 러너들(hrLag/드리프트/임계 상이) — 여기서 보정 이득이 나야 함
        val runners = VirtualRunner.PRESETS.filter { it.name != "코칭 무시형(테스트)" }
        println("=== 예측 온라인 보정 효과 (30분×5세션, 가중치 이월) ===")
        println("%-14s %8s %8s %8s %8s %6s".format("러너", "base60", "corr60", "base30", "corr30", "n"))
        var improvedCount = 0
        val results = runners.map { r ->
            val res = runRunner(dyn, r, sessions = 5)
            println("%-14s %8.2f %8.2f %8.2f %8.2f %6d".format(
                r.name, res.base60, res.corr60, res.base30, res.corr30, res.updates))
            if (res.updates >= 30 && res.corr60 <= res.base60) improvedCount++
            r to res
        }
        // 학습이 실제로 일어난 러너들에서 보정이 60초 RMSE를 (평균적으로) 악화시키지 않아야 하고,
        // 최소 과반에서 개선되어야 한다(개인 편차가 큰 러너일수록 이득이 크다).
        val learned = results.filter { it.second.updates >= 30 }
        assertTrue("학습 표본 충분한 러너 존재", learned.isNotEmpty())
        val meanBase = learned.map { it.second.base60 }.average()
        val meanCorr = learned.map { it.second.corr60 }.average()
        println("평균 base60=%.2f  corr60=%.2f  (개선 러너 %d/%d)".format(meanBase, meanCorr, improvedCount, learned.size))
        assertTrue("보정이 평균 60초 RMSE를 악화시키지 않음", meanCorr <= meanBase + 0.5)
        assertTrue("과반 러너에서 60초 예측 개선", improvedCount >= (learned.size + 1) / 2)
    }
}
