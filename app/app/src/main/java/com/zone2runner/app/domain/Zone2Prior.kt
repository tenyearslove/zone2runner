package com.zone2runner.app.domain

import kotlin.math.abs

/**
 * 콜드스타트 Zone 2 사전값(prior) 산출 — 순수 함수 (adr-012 대안 B, spec-013).
 * 프로필의 단계형 factor(체형/러닝 수준/주간 빈도)를 HRR 비율 오프셋으로 변환해
 * "데이터가 쌓이기 전에도 개인 근사 경계"에서 시작한다. 적응(Bayesian, adr-004)은 그대로.
 *
 * 오프셋 근거(spec-013): 훈련자 LT1 상향(비훈련 60~65 vs 훈련 75~80 %HRmax), MAF 범주 보정(±5),
 * 비만군 보수적 경계(동일 부하 심박 상승/드리프트 증가). RHR 이중 계상 방지로 총합 clamp.
 */
object Zone2Prior {

    data class Prior(
        val uFrac0: Double,   // Zone2 상한 초기값 (HRR 비율)
        val sigma0Bpm: Double, // 사전 불확실성 σ0 (bpm) — 극단 선택/불일치일수록 확대
        val offset: Double,    // 적용된 총 오프셋 (진단/미리보기용)
    )

    val BODY_LABELS = listOf("매우 마름", "마름", "보통", "과체중", "비만")
    val FITNESS_LABELS = listOf("입문", "초급", "중급", "상급", "엘리트")
    val FREQ_LABELS = listOf("거의 안함", "주1회", "주2~3", "주4~5", "매일")

    /** 자기평가 보조 안내문(러닝 수준). */
    val FITNESS_HINTS = listOf(
        "달려본 적 거의 없음", "가끔, 5km 미만", "주기적, 5~10km", "10km+ 꾸준히", "대회/훈련 수년",
    )

    private val BODY_OFFSET = doubleArrayOf(-0.01, 0.0, 0.0, -0.02, -0.04)
    private val FITNESS_OFFSET = doubleArrayOf(-0.05, -0.025, 0.0, 0.025, 0.05)
    private val FREQ_OFFSET = doubleArrayOf(-0.02, -0.01, 0.0, 0.01, 0.02)

    const val BAND = 0.12 // Zone2 폭(HRR 비율) ≈ %HRmax 10% 부근

    // uFrac(HRR 비율) 생리적 clamp — prior/개인화/영속화가 공유하는 단일 기준.
    const val U_FRAC_MIN = 0.30
    const val U_FRAC_MAX = 0.75

    // ★ Zone2 기준 재보정(2026-07-04): 참 Zone2 상단을 %HRmax 0.70(유산소 임계 LT1 근사)으로 잡는다.
    // 기존 고정 %HRR 0.70은 실측 maxHr 사용자에게 %HRmax 79%(Zone3~4)로 과대평가됐음(실기기 피드백).
    // 사람마다 maxHr/RHR 비율이 달라 %HRmax 목표를 개인별로 HRR 비율(uFrac)로 환산한다.
    const val HRMAX_TARGET = 0.70 // Zone2 상단 = 최대심박의 70% 부근

    fun of(p: Profile): Prior {
        val b = p.bodyType.coerceIn(1, 5) - 1
        val f = p.fitnessLevel.coerceIn(1, 5) - 1
        val q = p.weeklyFreq.coerceIn(1, 5) - 1
        // factor 오프셋은 %HRmax 목표를 미세조정(훈련자 LT1 상향 등). %HRmax 단위.
        val offset = (BODY_OFFSET[b] + FITNESS_OFFSET[f] + FREQ_OFFSET[q]).coerceIn(-0.08, 0.06)
        val hrmaxFrac = (HRMAX_TARGET + offset).coerceIn(0.60, 0.78)
        val uAbs = hrmaxFrac * p.maxHr                    // 상단(bpm)
        val uFrac0 = ((uAbs - p.restingHr) / p.hrr).coerceIn(U_FRAC_MIN, U_FRAC_MAX) // HRR 비율로 환산

        val extremity = abs(p.bodyType.coerceIn(1, 5) - 3) +
            abs(p.fitnessLevel.coerceIn(1, 5) - 3) + abs(p.weeklyFreq.coerceIn(1, 5) - 3)
        var sigma = 8.0 + 0.8 * extremity // 중앙 선택(extremity 0) = 기존 고정 σ 8bpm과 동일(하위 호환)
        val suggested = suggestBodyType(p.heightCm, p.weightKg)
        if (suggested != null && abs(suggested - p.bodyType) >= 2) sigma += 2.0 // BMI-체형 불일치 → 불확실성 확대
        if (p.rhrEstimated) sigma += 3.0 // RHR 모름(추정치 사용) → 불확실성 추가 확대(spec-009 폴백)
        return Prior(uFrac0, sigma, offset)
    }

    /**
     * RHR 모름(spec-009 4순위 폴백의 개선판): %HRmax로 갈아타는 대신, 러닝 수준/빈도로
     * 모집단 RHR을 추정해 HRR 파이프라인을 그대로 유지한다(파이프라인 전체가 HRR 기반).
     * 근거: RHR은 유산소 체력과 강한 역상관(훈련자 40~60, 일반 60~80).
     */
    fun estimateRhr(fitnessLevel: Int, weeklyFreq: Int): Int {
        val base = intArrayOf(74, 70, 66, 60, 54)[fitnessLevel.coerceIn(1, 5) - 1]
        return (base - (weeklyFreq.coerceIn(1, 5) - 3) * 2).coerceIn(45, 85)
    }

    /** BMI → 체형 제안(1~5). 대한비만학회 기준(<18.5 저체중, 18.5~23 정상, 23~25 과체중, ≥25 비만). 미입력 시 null. */
    fun suggestBodyType(heightCm: Int, weightKg: Int): Int? {
        if (heightCm < 100 || weightKg < 20) return null
        val h = heightCm / 100.0
        val bmi = weightKg / (h * h)
        return when {
            bmi < 17.0 -> 1
            bmi < 18.5 -> 2
            bmi < 23.0 -> 3
            bmi < 25.0 -> 4
            else -> 5
        }
    }

    fun bmi(heightCm: Int, weightKg: Int): Double? {
        if (heightCm < 100 || weightKg < 20) return null
        val h = heightCm / 100.0
        return weightKg / (h * h)
    }
}
