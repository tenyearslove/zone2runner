package com.zone2runner.app.pipeline

import com.zone2runner.app.domain.Profile

/**
 * 1Hz 시계열 → MLP 입력 특징 7종. simulator.extract_features와 동일 규약(spec-006 §1).
 * 특징: [hr_norm_u, hr_norm_l, dHR, pace, spm, decoupling, slope]
 *   - hr_norm_u/l : 개인 경계(uEst/lEst) 대비 HR 위치
 *   - decoupling  : 세션 baseline(hr/pace) 대비 최근 상승률 (Cardiac Drift)
 * 이상치 제거된 HR을 넣는다(OutlierGuard).
 */
class FeatureExtractor {
    companion object {
        const val WARMUP_S = 120
        const val STRIDE = 5
        private const val W = 30   // dHR / decoupling 윈도우
        private const val HRW = 60 // 지속 상태 윈도우
    }

    private val hr = ArrayList<Double>()   // 이상치 제거된 bpm
    private val pace = ArrayList<Double>()
    private val spm = ArrayList<Int>()
    private val slope = ArrayList<Double>()
    private var baseRatio = Double.NaN

    /** tSec 순서대로 호출(0,1,2,...). */
    fun add(hrClean: Double, paceMinKm: Double, spmv: Int, slopePct: Double) {
        hr += hrClean; pace += paceMinKm.coerceAtLeast(0.1); spm += spmv; slope += slopePct
        val n = hr.size
        if (n == WARMUP_S) computeBaseRatio()
    }

    private fun computeBaseRatio() {
        var s = 0.0; var c = 0
        for (i in (WARMUP_S - 60) until WARMUP_S) { s += hr[i] / pace[i]; c++ }
        baseRatio = if (c > 0) s / c else Double.NaN
    }

    fun warmupDone(): Boolean = !baseRatio.isNaN()

    /** t(현재 인덱스, =size-1)가 STRIDE 지점이면 특징 반환, 아니면 null. */
    fun extractAt(t: Int, profile: Profile, uEst: Double, lEst: Double): DoubleArray? {
        if (baseRatio.isNaN() || t < WARMUP_S || t >= hr.size) return null
        if ((t - WARMUP_S) % STRIDE != 0) return null

        val hrRecent = mean(hr, t - HRW, t)
        val paceRecent = mean(pace, t - HRW, t)
        val hrFrac = (hrRecent - profile.restingHr) / profile.hrr
        val dHR = (hr[t] - hr[t - W]) / W
        var rr = 0.0; var c = 0
        for (i in (t - W) until t) { rr += hr[i] / pace[i]; c++ }
        val decoupling = (rr / c) / baseRatio - 1.0
        return doubleArrayOf(
            hrFrac - uEst,      // hr_norm_u
            hrFrac - lEst,      // hr_norm_l
            dHR,
            paceRecent,
            spm[t].toDouble(),
            decoupling,
            slope[t],
        )
    }

    private fun mean(a: List<Double>, from: Int, to: Int): Double {
        var s = 0.0; var c = 0
        for (i in from until to) { if (i >= 0 && i < a.size) { s += a[i]; c++ } }
        return if (c > 0) s / c else 0.0
    }
}
