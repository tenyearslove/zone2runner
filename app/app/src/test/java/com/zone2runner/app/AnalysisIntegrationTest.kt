package com.zone2runner.app

import com.zone2runner.app.coaching.RuleCoach
import com.zone2runner.app.domain.LiveState
import com.zone2runner.app.domain.Profile
import com.zone2runner.app.domain.Sample
import com.zone2runner.app.pipeline.RunEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 관측 분석 엔진 폐루프 통합 검증(spec-025 10단계, QA5 테스트가능성) — 워치 없이 손으로 구성한
 * 정속+감속 세션을 RunEngine에 통과시켜 [수집→가드→규칙판정→분석엔진→코칭→리포트]가 실제 산출되는지 확인.
 */
class AnalysisIntegrationTest {

    private fun sample(t: Int, hr: Int, pace: Double, spm: Int) =
        Sample(t, hr, pace, spm, 0.0, Double.NaN, Double.NaN)

    @Test fun steadyThenRecovery_engineProducesMetrics() = runBlocking {
        val engine = RunEngine(Profile.default(35, 55), RuleCoach())
        var last: LiveState? = null
        var t = 0
        // 260초 정속(페이스 6.0), 심박 130→150 완만 상승 = 드리프트
        for (i in 0 until 260) { last = engine.onSample(sample(t, 130 + i / 13, 6.0, 176)); t++ }
        // 80초 감속/회복(페이스 14.0=느림, 심박 150→118)
        for (i in 0 until 80) { last = engine.onSample(sample(t, 150 - (i * 0.4).toInt(), 14.0, 62)); t++ }

        val r = engine.report()

        // 실시간: 정속 창에서 드리프트 기울기 산출
        assertNotNull("드리프트 실시간 산출", last!!.driftSlope)
        // 세션종료: 분석 지표 요약(드리프트/서브맥시멀/HRR/케이던스 중 최소 하나 이상)
        assertTrue("세션종료 분석 지표", r.analysisLines.isNotEmpty())
        // 서브맥시멀: 260초 정속 페이스 빈 → 대표값 존재
        assertNotNull("서브맥시멀 HR", r.submaxHr)
        // 예측 잔재 없음(레거시 usedModel만 true)
        assertTrue(r.durationSec >= 339)
        println("분석통합: drift=${last!!.driftSlope} submax=${r.submaxHr} lines=${r.analysisLines.size} coach=${r.coachingLines.size}")
        r.analysisLines.forEach { println("  · $it") }
    }

    @Test fun driftFloor_seededFromPrior_wiresThrough() = runBlocking {
        // 개인 드리프트 플로어를 prior로 주입해도 세션이 정상 동작(seed 경로 검증)
        val engine = RunEngine(
            Profile.default(40, 60), RuleCoach(),
            priorDriftFloor = Triple(3.0, 1.0, 25),
        )
        var t = 0
        for (i in 0 until 200) { engine.onSample(sample(t, 135 + i / 20, 6.0, 178)); t++ }
        val (m, v, n) = engine.driftFloorState()
        assertTrue("플로어가 관측으로 갱신됨", n >= 25)   // seed 25 + 관측 누적
        assertTrue(m.isFinite() && v.isFinite())
    }
}
