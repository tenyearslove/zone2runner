package com.zone2runner.app

import com.zone2runner.app.domain.Sample
import com.zone2runner.app.domain.VirtualRunner
import com.zone2runner.app.pipeline.OutlierGuard
import com.zone2runner.app.sim.SimRunnerSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** spec-019 수락 기준(AC1~6) — 강화된 VirtualRunner/SimRunnerSource 검증. */
class VirtualRunnerTest {

    /** SimRunnerSource를 headless로 durationSec 만큼 돌려 샘플 수집. */
    private fun collect(runner: VirtualRunner, seed: Long, durationSec: Int): List<Sample> = runBlocking {
        val out = ArrayList<Sample>(durationSec)
        val src = SimRunnerSource(runner, delayMs = 0L, seed = seed, maxDurationSec = durationSec)
        val done = CompletableDeferred<Unit>()
        src.start(this, onSample = { s -> out.add(s) }, onComplete = { done.complete(Unit) })
        done.await()
        out
    }

    @Test fun ac6_randomHuman_isPhysiologicallyValid_acrossPopulation() {
        for (seed in 1L..100L) {
            val r = VirtualRunner.randomHuman(seed)
            assertTrue("maxHr>restingHr (seed=$seed)", r.maxHr > r.restingHr + 60)
            assertTrue(r.restingHr in 38..82)
            assertTrue(r.maxHr in 160..205)
            assertTrue(r.trueZone2UpperHrmaxFrac in 0.58..0.76)
            assertTrue(r.hrLagSec in 18.0..42.0)
            assertTrue(r.pacingDiscipline in 0.15..0.97)
            assertTrue(r.terrainHilliness in 0.0..0.9)
            assertTrue(r.tempC in 6.0..31.0)
            assertTrue(r.basePaceMinKm in 4.2..7.8)
        }
    }

    @Test fun ac2_deterministicBySeed_andVariesAcrossSeeds() {
        val runner = VirtualRunner.DEFAULT
        for (seed in listOf(3L, 7L, 11L, 21L, 42L)) {
            val first = collect(runner, seed = seed, durationSec = 300)
            val replay = collect(runner, seed = seed, durationSec = 300)
            assertEquals("같은 seed → 같은 길이 (seed=$seed)", first.size, replay.size)
            assertTrue("같은 seed → 동일 시퀀스 (seed=$seed)", first.zip(replay).all { (x, y) ->
                x.hr == y.hr && x.paceMinKm == y.paceMinKm && x.slopePct == y.slopePct
            })
            val different = collect(runner, seed = seed + 1_000L, durationSec = 300)
            val hrDiff = first.zip(different).count { (x, y) -> x.hr != y.hr }
            assertTrue("다른 seed → 유의미하게 다른 세션 (seed=$seed)", hrDiff > 100)
        }
    }

    @Test fun ac3_terrain_emitsNonZeroSlope_whenHilly() {
        val flat = collect(VirtualRunner.DEFAULT.copy(terrainHilliness = 0.0), seed = 3L, durationSec = 600)
        assertTrue("평지면 slope 0", flat.all { it.slopePct == 0.0 })
        val hilly = collect(VirtualRunner.DEFAULT.copy(terrainHilliness = 0.85), seed = 3L, durationSec = 600)
        val uphill = hilly.count { it.slopePct > 1.0 }
        val downhill = hilly.count { it.slopePct < -1.0 }
        assertTrue("언덕 코스면 오르막 구간 발생", uphill > 30)
        assertTrue("언덕 코스면 내리막 구간 발생", downhill > 30)
        assertTrue("경사 범위 타당(±12% 내)", hilly.all { Math.abs(it.slopePct) <= 12.0 })
    }

    @Test fun ac4_sensorArtifacts_occur_andGuardFilters() {
        val r = VirtualRunner.DEFAULT.copy(sensorDropoutRate = 0.03, sensorSpikeRate = 0.02)
        val samples = collect(r, seed = 5L, durationSec = 1200)
        val dropouts = samples.count { it.hr <= 0 }
        val spikes = samples.count { it.hr > 220 } // 스파이크는 생리범위(220) 밖으로 튐
        assertTrue("드롭아웃 발생", dropouts > 5)
        assertTrue("스파이크 발생", spikes > 3)
        // OutlierGuard가 무효/스파이크를 걸러낸다(강건성 QA4)
        assertTrue("드롭아웃은 무효로 기각", samples.filter { it.hr <= 0 }.all { !OutlierGuard.isValid(it.hr) })
        assertTrue("생리범위 밖 스파이크 기각", samples.filter { it.hr > 220 }.all { !OutlierGuard.isValid(it.hr) })
    }

    @Test fun ac5_presetsIntact_backwardCompatible() {
        // §12 하네스가 쓰는 프리셋들이 유효하고 세션을 만든다(하위 호환)
        assertTrue(VirtualRunner.PRESETS.size >= 5)
        val s = collect(VirtualRunner.PRESETS.first { it.name == "베테랑 절제형" }, seed = 1L, durationSec = 300)
        assertEquals(300, s.size)
        assertTrue("심박 생리 범위", s.filter { it.hr > 0 }.all { it.hr in 35..205 })
    }

    @Test fun heat_increasesDrift_higherLateHr() {
        // 같은 러너, 기온만 다르게 → 더울수록 후반 심박이 높다(드리프트 가중)
        val base = VirtualRunner.DEFAULT.copy(driftHeatK = 0.35, trueZone2UpperHrmaxFrac = 0.60) // 임계 넘겨 드리프트 유도
        val cool = collect(base.copy(tempC = 10.0), seed = 4L, durationSec = 1800)
        val hot = collect(base.copy(tempC = 30.0), seed = 4L, durationSec = 1800)
        fun lateAvg(l: List<Sample>) = l.takeLast(300).filter { it.hr > 0 }.map { it.hr }.average()
        assertTrue("더울수록 후반 심박↑", lateAvg(hot) > lateAvg(cool))
    }
}
