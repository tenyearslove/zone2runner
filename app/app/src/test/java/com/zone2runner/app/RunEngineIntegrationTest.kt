package com.zone2runner.app

import com.zone2runner.app.coaching.RuleCoach
import com.zone2runner.app.domain.Profile
import com.zone2runner.app.pipeline.HrDynamics
import com.zone2runner.app.pipeline.RunEngine
import com.zone2runner.app.sim.RunSimulator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 전체 파이프라인 통합 검증 — 시뮬레이션 세션을 RunEngine에 통과시켜 리포트 불변식을 확인.
 * 실기기 UI를 못 돌리는 대신, 엔진 레벨에서 [소스→가드→규칙판정→개인화→동역학예측→코칭→누적]이
 * 일관된 세션 결과를 만드는지 실제로 실행해 본다(규칙 코치, 동역학 모델 있으면 사용 — adr-013).
 */
class RunEngineIntegrationTest {

    @Test fun fullSession_producesConsistentReport() = runBlocking {
        val profile = Profile.default(35, 58)
        val dyn = loadModelOrNull()
        val engine = RunEngine(profile, dyn, RuleCoach())
        val session = RunSimulator(seed = 11L).generate(durationMin = 20)

        for (s in session.samples) engine.onSample(s)
        val r = engine.report()

        // 시간/거리
        assertTrue("경과시간은 샘플 수 근처", r.durationSec >= session.samples.size - 1)
        assertTrue("거리 누적", r.distanceM > 500)
        // 심박
        assertTrue("평균 심박 생리 범위", r.avgHr in 60..210)
        assertTrue("최대 >= 평균", r.maxHr >= r.avgHr)
        // 존 체류 합 <= 전체 시간, 비율 0..100
        assertTrue(r.belowSec + r.inSec + r.aboveSec <= r.durationSec)
        assertTrue(r.zone2Pct in 0..100)
        // 시계열/경로 기록됨
        assertTrue("시계열 기록", r.series.size > 50)
        assertTrue("경로 기록", r.track.size > 50)
        // 코칭 최소 1회(20분 세션이면 판정 전환이 있기 마련)
        assertTrue("코칭 문구 생성", r.coachingLines.isNotEmpty())
        // 개인화 경계는 가드 범위 내
        assertTrue(r.uEstEndFrac in 0.5..0.85)
        println("통합: dur=${r.durationSec}s dist=${r.distanceM.toInt()}m avgHr=${r.avgHr} z2=${r.zone2Pct}% coach=${r.coachingLines.size} model=${r.usedModel}")
    }

    @Test fun ruleFallback_whenNoModel_stillProducesReport() = runBlocking {
        val engine = RunEngine(Profile.default(40, 60), null, RuleCoach())
        val session = RunSimulator(seed = 5L).generate(durationMin = 8)
        for (s in session.samples) engine.onSample(s)
        val r = engine.report()
        assertTrue(!r.usedModel) // 규칙 폴백
        assertTrue(r.durationSec > 400)
        assertTrue(r.zone2Pct in 0..100)
    }

    private fun loadModelOrNull(): HrDynamics? {
        val f = listOf(File("src/main/assets/hr_dynamics.json"), File("app/src/main/assets/hr_dynamics.json"))
            .firstOrNull { it.exists() } ?: return null
        return runCatching { HrDynamics.fromJsonString(f.readText()) }.getOrNull()
    }
}
