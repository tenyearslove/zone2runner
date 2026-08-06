package com.zone2runner.app.pipeline

/**
 * QA4 강건성: HR 입력 가드 (Tier 1 + Tier 2 통합)
 *
 * 파이프라인:
 * 1. Tier 1 (고정 범위): 40~220 bpm 범위 밖은 즉시 기각
 * 2. Tier 2 (세션 적응형): 10초 윈도우 기반 이상값 판정
 *    - 정상 변화: 수용, 윈도우에 추가
 *    - 일시적 점프: 기각, 직전 유효값으로 대체
 *
 * spec-003 QA4 수락 기준:
 * - AC-T1-1: 40~220 밖은 100% 기각
 * - AC-T1-2: 범위 내 값은 Tier 2로 진행
 * - AC-T2-2~4: 일시적 이상값 vs 지속적 변화 시나리오에서 기대 동작 일치율 100%
 */
class HrInputGuard {
    private val sessionGuard = SessionAdaptiveGuard()

    /**
     * HR 입력을 검사하고 정제한다.
     *
     * @param hr 새로운 심박값
     * @param currentTimeMs 현재 타임스탐프
     * @return 검증된 HR 값 (또는 이상값이면 직전 유효값)
     */
    fun process(hr: Int, currentTimeMs: Long): Int {
        // Tier 1: 고정 범위 (40~220 bpm)
        if (!OutlierGuard.isValid(hr)) {
            // 범위 밖 → 즉시 기각, 직전 유효값으로 대체
            return sessionGuard.getLastValidHr() ?: 120  // fallback: 기본값 120
        }

        // Tier 2: 세션 적응형 가드
        return sessionGuard.process(hr, currentTimeMs)
    }

    // 테스트/모니터링용
    fun getWindowMedian(): Double? = sessionGuard.getCurrentMedian()
    fun getWindowSize(): Int = sessionGuard.getWindowSize()
    fun getLastValidHr(): Int? = sessionGuard.getLastValidHr()
}
