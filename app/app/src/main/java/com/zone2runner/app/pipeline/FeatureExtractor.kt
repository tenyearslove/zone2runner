package com.zone2runner.app.pipeline


/**
 * 1Hz 시계열 특징 추출. 현재 소비처:
 *   - smoothedHrAt: 지속 심박(60초 평균) → 규칙 판정(ZoneJudge)의 입력.
 *   - dHrPerSecAt: 심박 추세(bpm/s) → 대시보드 표시 + 회복 감지.
 *   - displayDriftAt: 사용자 표시용 드리프트(생리 지표).
 * 이상치 제거된 HR을 넣는다(OutlierGuard).
 * (구 extractAt의 7-특징 벡터[디커플링 관측 등]는 개인화 디커플링 채널 제거로 함께 삭제 — 2026-07-13.)
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
     * 최근 60초 평균 심박(bpm) — 규칙 판정(ZoneJudge)이 보는 것과 동일한 지속 심박 기준.
     * 밴드 마커를 이 값으로 그려 판정 칩과 시각적으로 정렬한다(순간 스파이크로 인한 색/판정 괴리 제거).
     * 버퍼가 비면 null. 초반엔 가용 표본만 평균(점진적으로 60초 창으로 수렴).
     */
    fun smoothedHrAt(t: Int): Int? {
        if (hr.isEmpty() || t < 0) return null
        val end = minOf(t, hr.size - 1)
        val m = mean(hr, end - HRW + 1, end + 1)
        return if (m > 0) Math.round(m).toInt() else null
    }

    /** 심박 추세(bpm/s) — 최근 W초 차분. 버퍼 부족(t<W) 시 null. 대시보드 표시 + 회복 감지용(도출값). */
    fun dHrPerSecAt(t: Int): Double? {
        if (t < W || t >= hr.size) return null
        return (hr[t] - hr[t - W]) / W
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
        if (c == 0 || displayBase <= 0) return null
        // 표시 클램프: 실제 심혈관 드리프트는 통상 0~15%. 시뮬 심박이 최대치에 붙거나 GPS 속도가
        // 튀면 비율이 비현실적으로 부풀어(관찰: 38%) 사용자를 오도한다 → -10~+25%로 제한(참고 지표).
        return ((s / c) / displayBase - 1.0).coerceIn(-0.10, 0.25)
    }

    /** HR / 속도(km/h) = hr * pace / 60 — 같은 속도 대비 심박 비용(EF 역수). */
    private fun hrPerSpeed(i: Int): Double = hr[i] * pace[i] / 60.0
}
