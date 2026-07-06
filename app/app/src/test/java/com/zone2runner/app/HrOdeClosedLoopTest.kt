package com.zone2runner.app

import com.zone2runner.app.coaching.RuleCoach
import com.zone2runner.app.domain.Profile
import com.zone2runner.app.domain.VirtualRunner
import com.zone2runner.app.pipeline.RunEngine
import com.zone2runner.app.sim.SimRunnerSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 심박 예측 ODE 폐루프 검증 (adr-020, QA5).
 *
 * 확인 두 가지:
 *  (1) 생리 ODE 기본 예측이 폐루프 실주행 시뮬에서 정확한가(60초 RMSE 노이즈 바닥 수준).
 *  (2) 온라인 파라미터 개인화가 그 정확도를 악화시키지 않는가(비해악성).
 *
 * 정직한 한계: 정상상태 위주 Zone2 세션에는 τ를 식별할 전이(pace/경사 변화)가 적어 τ 개인화는
 * 거의 개입하지 않는다(그래서 base≈model). τ 추정이 실제 참값으로 수렴함은 HrOdeModelTest에서
 * 전이가 풍부한 계열로 따로 검증한다(30→~20). 실사람 이득 정량화는 필드 로그 과제.
 */
class HrOdeClosedLoopTest {

    private data class Res(val base: Double, val model: Double, val tau: Double, val updates: Int)

    private fun runRunner(r: VirtualRunner, sessions: Int): Res = runBlocking {
        val profile = Profile.default(r.age, r.restingHr).copy(maxHr = r.maxHr)
        var params: DoubleArray? = null
        var last = Res(0.0, 0.0, 0.0, 0)
        for (i in 0 until sessions) {
            val engine = RunEngine(profile, RuleCoach(), coachScope = null, priorOdeParams = params)
            val source = SimRunnerSource(r, delayMs = 0L, seed = 100L + i, maxDurationSec = 30 * 60,
                onTalkTest = { engine.observeTalkTest(it) })
            val done = CompletableDeferred<Unit>()
            source.start(this,
                onSample = { s -> val st = engine.onSample(s); source.onFeedback(st) },
                onComplete = { done.complete(Unit) })
            done.await()
            params = engine.odeParams()
            val rm = engine.predRmse()
            last = Res(rm.base, rm.model, engine.odeTau(), engine.predUpdates())
        }
        last
    }

    @Test fun odePrediction_accurate_and_personalization_nonHarmful() {
        val runners = VirtualRunner.PRESETS.filter { it.name != "코칭 무시형(테스트)" }
        println("=== 심박 예측 ODE 개인화 효과 (30분×5세션, 파라미터 이월) ===")
        println("%-14s %8s %8s %6s %6s".format("러너", "base60", "model60", "τ", "n"))
        var improved = 0
        val results = runners.map { r ->
            val res = runRunner(r, sessions = 5)
            println("%-14s %8.2f %8.2f %6.1f %6d".format(r.name, res.base, res.model, res.tau, res.updates))
            if (res.updates >= 30 && res.model <= res.base + 0.01) improved++
            res
        }
        val learned = results.filter { it.updates >= 30 }
        assertTrue("학습 표본 충분한 러너 존재", learned.isNotEmpty())
        val meanBase = learned.map { it.base }.average()
        val meanModel = learned.map { it.model }.average()
        println("평균 base60=%.2f  model60=%.2f  (비해악 %d/%d)".format(meanBase, meanModel, improved, learned.size))
        // (1) 기본 ODE 예측이 정확(60초 RMSE가 생리적으로 작은 범위) — 폐루프에서 실증
        assertTrue("기본 ODE 60초 예측이 정확(RMSE < 10bpm)", meanBase < 10.0)
        // (2) 개인화가 정확도를 악화시키지 않음
        assertTrue("개인화 비해악(평균 RMSE 악화 없음)", meanModel <= meanBase + 0.5)
        assertTrue("과반 러너에서 개인화 비해악", improved >= (learned.size + 1) / 2)
    }
}
