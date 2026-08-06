package com.zone2runner.app.pipeline

/**
 * QA4 강건성: Tier 2 세션 적응형 가드.
 *
 * 원리: 최근 10초 윈도우의 중앙값과 IQR을 기반으로 이상값 판정.
 *
 * 구조:
 * 1. 10초 윈도우 유지 (종류 C 상수, spec-003)
 * 2. 신규 HR이 (중앙값 ± 2·IQR) 범위 안인가?
 *    YES → 수용 (정상 변화)
 *    NO → 기각 (일시적 이상값), 직전 유효값으로 대체
 * 3. 윈도우 갱신: 신규 샘플 추가/제거
 *
 * 효과:
 * - 1초 점프: 대부분 윈도우가 정상값으로 채워져 범위 밖 → 기각
 * - 5초 선형 변화: 윈도우에 정상값과 변화값이 섞여 중앙값이 천천히 변화 → 수용
 *
 * 종류 C 상수 근거: Tukey 1977 (통계적 이상치 탐지, IQR×배수 기준)
 */
class SessionAdaptiveGuard {
    companion object {
        private const val WINDOW_SIZE_MS = 10_000  // 10초 윈도우 (종류 C: spec-003)
        private const val OUTLIER_THRESHOLD = 3.0  // IQR의 배수 (종류 C: Tukey 기준, 상향조정)
        // 1초 급격한 점프(+20~40 bpm): 초기 윈도우 95%가 정상값이므로 IQR이 작아 범위 밖 → 기각
        // 5초 선형 상승(매초 +4 bpm): 윈도우에 정상과 변화값 섞임 → IQR 증가 → 3·IQR 범위 안으로 수용
        private const val MIN_IQR = 2.0  // IQR 최소값 (정상 심박 변동 하한, 상향조정)
    }

    private data class Sample(val hr: Int, val timestampMs: Long)

    private val samples = mutableListOf<Sample>()
    private var lastValidHr: Int? = null

    /**
     * 신규 HR을 검사하고 윈도우를 갱신한다.
     *
     * @param hr 새로운 심박(Tier 1 40~220 범위)
     * @param currentTimeMs 현재 타임스탐프
     * @return 수용되는 HR, 또는 이상값이면 직전 유효값
     */
    fun process(hr: Int, currentTimeMs: Long): Int {
        // 윈도우에서 오래된 값 제거
        samples.removeAll { currentTimeMs - it.timestampMs > WINDOW_SIZE_MS }

        if (samples.isEmpty()) {
            // 첫 샘플: 검증 없이 수용
            samples.add(Sample(hr, currentTimeMs))
            lastValidHr = hr
            return hr
        }

        // 현재 윈도우 기반 중앙값과 IQR 계산 (종류 A: 도출값)
        val values = samples.map { it.hr.toDouble() }.sorted()
        val (median, iqr) = computeMedianAndIQR(values)

        // 이상값 판정: 범위 밖인가?
        val lowerBound = median - OUTLIER_THRESHOLD * iqr
        val upperBound = median + OUTLIER_THRESHOLD * iqr
        val isOutlier = hr < lowerBound || hr > upperBound

        return if (isOutlier) {
            // 기각: 직전 유효값으로 대체 (또는 null이면 hr 자체)
            lastValidHr ?: hr
        } else {
            // 수용: 윈도우에 추가하고 반환
            samples.add(Sample(hr, currentTimeMs))
            lastValidHr = hr
            hr
        }
    }

    /**
     * 현재 윈도우의 중앙값과 사분위수 범위(IQR)를 계산.
     * 종류 A: 도출값 (라이브 신호에서 계산, 저장하지 않음)
     */
    private fun computeMedianAndIQR(values: List<Double>): Pair<Double, Double> {
        if (values.isEmpty()) return 120.0 to 1.0  // fallback (shouldn't happen)

        // 중앙값
        val median = if (values.size % 2 == 0) {
            (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
        } else {
            values[values.size / 2]
        }

        // 사분위수 (선형 보간)
        val q1 = quantile(values, 0.25)
        val q3 = quantile(values, 0.75)
        var iqr = q3 - q1

        // IQR이 작으면 최소값 사용 (종류 C 상수)
        if (iqr < MIN_IQR) {
            iqr = MIN_IQR
        }

        return median to iqr
    }

    /**
     * 분위수 계산 (선형 보간)
     */
    private fun quantile(values: List<Double>, p: Double): Double {
        if (values.size == 1) return values[0]

        val idx = (values.size - 1) * p
        val lower = idx.toInt()
        val upper = lower + 1

        return if (upper >= values.size) {
            values[lower]
        } else {
            val frac = idx - lower
            values[lower] * (1 - frac) + values[upper] * frac
        }
    }

    // 테스트용
    fun getCurrentMedian(): Double? {
        if (samples.isEmpty()) return null
        val values = samples.map { it.hr.toDouble() }.sorted()
        val (median, _) = computeMedianAndIQR(values)
        return median
    }

    fun getWindowSize(): Int = samples.size

    fun getLastValidHr(): Int? = lastValidHr
}
