package com.zone2runner.app.sim

import com.zone2runner.app.domain.Sample
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * 물리 기반 러닝 세션 시뮬레이터(ml/simulator.py의 경량 Kotlin 포팅).
 * HR(지연+Cardiac Drift), 페이스, 케이던스, 경사 + GPS 경로(폴리라인용)를 1Hz로 생성.
 * 실기기 없이 전체 파이프라인을 재생/점검하기 위한 입력원.
 */
class RunSimulator(seed: Long = 42L) {
    private val rng = Random(seed)

    data class SimRunner(
        val age: Int, val resting: Int, val maxHr: Int, val hrr: Double,
        val uFrac: Double, val lFrac: Double, val hrTau: Double,
        val driftScale: Double, val driftTau: Double, val noiseSd: Double, val basePace: Double,
    )

    data class Session(val samples: List<Sample>, val runner: SimRunner)

    private fun u(a: Double, b: Double) = a + rng.nextDouble() * (b - a)
    private fun gauss(m: Double, s: Double) = m + rng.nextGaussian() * s

    fun makeRunner(): SimRunner {
        val age = 20 + rng.nextInt(35)
        val resting = u(48.0, 68.0).toInt()
        val maxHr = (208 - 0.7 * age)
        val hrr = maxHr - resting
        val uFrac = gauss(0.70, 0.045).coerceIn(0.55, 0.85)
        val band = u(0.08, 0.13)
        return SimRunner(
            age, resting, maxHr.toInt(), hrr, uFrac, uFrac - band,
            u(20.0, 40.0), u(0.30, 0.60), u(90.0, 180.0), u(0.3, 1.0), u(6.5, 8.0),
        )
    }

    private fun smooth(a: DoubleArray, k: Int): DoubleArray {
        val out = DoubleArray(a.size)
        val half = k / 2
        for (i in a.indices) {
            var s = 0.0; var c = 0
            for (j in (i - half)..(i + half)) if (j in a.indices) { s += a[j]; c++ }
            out[i] = s / c
        }
        return out
    }

    private fun effortProfile(n: Int): DoubleArray {
        val p = DoubleArray(n)
        val w = minOf(120, n)
        for (i in 0 until w) p[i] = 0.45 + (0.62 - 0.45) * i / maxOf(1, w - 1)
        var t = w
        while (t < n) {
            val seg = minOf(60 + rng.nextInt(180), n - t)
            val target = gauss(0.66, 0.09).coerceIn(0.4, 0.92)
            for (i in t until t + seg) p[i] = target
            t += seg
        }
        val s = smooth(p, 15)
        for (i in s.indices) s[i] = s[i].coerceIn(0.35, 0.95)
        return s
    }

    private fun terrain(n: Int): DoubleArray {
        val slope = DoubleArray(n)
        val choices = doubleArrayOf(0.0, 0.0, 0.0, 3.0, 5.0, -3.0, -5.0)
        var t = 0
        while (t < n) {
            val seg = minOf(90 + rng.nextInt(210), n - t)
            val sVal = choices[rng.nextInt(choices.size)]
            for (i in t until t + seg) slope[i] = sVal
            t += seg
        }
        return smooth(slope, 20)
    }

    fun generate(durationMin: Int = 30, startLat: Double = 37.5665, startLon: Double = 126.9780): Session {
        val r = makeRunner()
        val n = durationMin * 60
        val effort = effortProfile(n)
        val slope = terrain(n)
        val uAbs = r.resting + r.uFrac * r.hrr

        val effortEff = DoubleArray(n) { (effort[it] + 0.012 * slope[it]).coerceIn(0.3, 1.05) }
        val effortHr = DoubleArray(n) { r.resting + effortEff[it] * r.hrr }

        val hr = DoubleArray(n)
        hr[0] = effortHr[0]
        var drift = 0.0
        for (i in 1 until n) {
            val hrLag = hr[i - 1] + (effortHr[i] - hr[i - 1]) * (1.0 / r.hrTau)
            val excess = maxOf(0.0, effortHr[i] - uAbs)
            val driftTarget = r.driftScale * excess
            drift += (driftTarget - drift) * (1.0 / r.driftTau)
            hr[i] = hrLag + drift
        }

        val samples = ArrayList<Sample>(n)
        var lat = startLat; var lon = startLon
        var heading = 0.0
        for (i in 0 until n) {
            val hrObs = hr[i] + gauss(0.0, r.noiseSd)
            var pace = r.basePace - 3.2 * (effort[i] - 0.5) + 0.10 * slope[i] + gauss(0.0, 0.08)
            pace = pace.coerceIn(3.5, 11.0)
            var spm = 168 - 4.0 * (pace - 6.0) + gauss(0.0, 2.5)
            spm = spm.coerceIn(150.0, 200.0)

            // GPS: 속도(m/s)=16.667/pace, heading 완만한 랜덤워크로 루프 경로
            val mps = 16.667 / pace
            heading += gauss(0.0, 0.05) + 0.02 // 완만히 휘어 루프
            lat += (mps * cos(heading)) / 111_320.0
            lon += (mps * sin(heading)) / (111_320.0 * cos(Math.toRadians(lat)))

            samples += Sample(i, hrObs.toInt(), pace, spm.toInt(), slope[i], lat, lon)
        }
        return Session(samples, r)
    }
}
