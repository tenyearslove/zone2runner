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
        // 표시용 드리프트 기준선 구간(HR 안정 후) — displayDriftAt 참조
        const val BASE_FROM = 180
        const val BASE_TO = 240
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

    /**
     * 최근 60초 평균 심박(bpm) — MLP 판정이 보는 것과 동일한 지속 심박 기준.
     * 밴드 마커를 이 값으로 그려 판정 칩과 시각적으로 정렬한다(순간 스파이크로 인한 색/판정 괴리 제거).
     * 버퍼가 비면 null. 초반엔 가용 표본만 평균(점진적으로 60초 창으로 수렴).
     */
    fun smoothedHrAt(t: Int): Int? {
        if (hr.isEmpty() || t < 0) return null
        val end = minOf(t, hr.size - 1)
        val m = mean(hr, end - HRW + 1, end + 1)
        return if (m > 0) Math.round(m).toInt() else null
    }

    /**
     * 심박 동역학 모델 입력 8종 (spec-014, ml/train_hr_dynamics.py extract_dynamics와 동일 규약):
     *   [hr_now_frac(10초), hr_sus_frac(60초), dHR(30초), pace_plan, slope, spm, elapsed_min, decoupling]
     * pace_plan = 앞으로 유지할 페이스(호출자가 후보/현재 페이스를 공급). 워밍업 전 null.
     */
    fun dynFeaturesAt(t: Int, profile: Profile, pacePlan: Double, slopeNow: Double, spmNow: Int): DoubleArray? {
        if (baseRatio.isNaN() || t < WARMUP_S || t >= hr.size) return null
        val hrNow = mean(hr, t - 10, t)
        val hrSus = mean(hr, t - HRW, t)
        val dHR = (hr[t] - hr[t - W]) / W
        var rr = 0.0; var c = 0
        for (i in (t - W) until t) { rr += hr[i] / pace[i]; c++ }
        val dec = (rr / c) / baseRatio - 1.0
        return doubleArrayOf(
            (hrNow - profile.restingHr) / profile.hrr,
            (hrSus - profile.restingHr) / profile.hrr,
            dHR,
            pacePlan,
            slopeNow,
            spmNow.toDouble(),
            t / 60.0,
            dec,
        )
    }

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

    // ---- 표시용 드리프트 (사용자 노출 지표 — 특징 feat[5]와 별개) ----
    // feat[5]의 hr/pace 비율은 강도 변화에 지배되고 워밍업 중 기준선이라 값이 부풀어
    // 사용자 지표로 부적합(실기기 관찰: +30~50%). 표시는 생리학 관례(Pw:HR 디커플링)에 맞춰
    // HR/속도(EF 역수) 기반 + HR 안정 후(3~4분) 기준선으로 계산한다. 통상 0~10%, >5% 피로 신호.
    private var displayBase = Double.NaN

    /** 표시용 드리프트(비율). 기준선(3~4분) 확보 전엔 null. */
    fun displayDriftAt(t: Int): Double? {
        if (t < BASE_TO + 60) return null
        if (displayBase.isNaN()) {
            var s = 0.0; var c = 0
            for (i in BASE_FROM until minOf(BASE_TO, hr.size)) { s += hrPerSpeed(i); c++ }
            if (c < 30) return null
            displayBase = s / c
        }
        var s = 0.0; var c = 0
        for (i in maxOf(0, t - 60) until minOf(t, hr.size)) { s += hrPerSpeed(i); c++ }
        return if (c > 0 && displayBase > 0) (s / c) / displayBase - 1.0 else null
    }

    /** HR / 속도(km/h) = hr * pace / 60 — 같은 속도 대비 심박 비용(EF 역수). */
    private fun hrPerSpeed(i: Int): Double = hr[i] * pace[i] / 60.0
}
