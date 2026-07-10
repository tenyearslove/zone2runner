package com.zone2runner.app.analysis

/**
 * 분석 엔진 설계 선택 상수(spec-025 §3, CLAUDE.md 種類C).
 * 마법상수가 아니라 문헌 근거 있는 출발값 — 필드 데이터로 튜닝. 하드코딩 금지, 여기 한곳에 모은다.
 */
object AnalysisConfig {
    const val CV_STEADY = 0.05          // 정속 판정 페이스 변동계수 상한(드리프트 유효 조건)
    const val W_DRIFT_RT = 180          // 드리프트 실시간 롤링창(초) — Coyle 드리프트 10~20분 규모, 3분 창 기울기
    const val DRIFT_MIN_N = 30          // 드리프트 회귀 최소 표본
    const val CADENCE_WIN = 60          // 케이던스 안정성 창(초)
    const val CADENCE_MIN_N = 10        // 케이던스 σ 최소 표본
    const val MDC_CADENCE = 2.53        // 케이던스 최소검출변화(spm), JOSPT 2016 — σ 임계
    const val PACE_BIN = 0.25           // 서브맥시멀 페이스 빈 폭(min/km)
    const val MIN_BIN_SEC = 120         // 빈 최소 체류(초)
    const val PACE_RUN_LO = 2.5         // 러닝 페이스 유효 하한(min/km, 빠름)
    const val PACE_RUN_HI = 12.0        // 러닝 페이스 유효 상한(min/km, 느림) — 그 위는 걷기/정지
    const val HRR_RECENT = 30           // 노력 강도 기준(직전 평균 속도) 창(초)
    const val HRR_WINDOW = 60           // HRR 회복 관측창(초)
    const val EFFORT_END_SPEED_RATIO = 0.7 // 속도가 직전 평균의 70% 아래로 지속되면 노력 종료(Daanen HRR 관례)
    const val EFFORT_END_HOLD = 10      // 노력 종료 판정 지속(초)
    const val K_SIGMA = 2.0             // 판단선 배수(slope 대 k·SE, m+k·σ̂)
    const val DRIFT_FLOOR_MIN_N = 20    // 드리프트 개인 노이즈플로어가 유의미해지는 최소 관측수

    // Minetti 2002 러닝 에너지비용 5차 다항식 Cr(i) [J/kg/m], i=경사(소수). R²=0.999, i∈[-0.45,0.45].
    const val MINETTI_LEVEL = 3.6       // Cr(0) 평지 기준
    fun minettiCr(i: Double): Double {
        val g = i.coerceIn(-0.45, 0.45)
        return 155.4 * g * g * g * g * g - 30.4 * g * g * g * g - 43.3 * g * g * g +
            46.3 * g * g + 19.5 * g + 3.6
    }
}
