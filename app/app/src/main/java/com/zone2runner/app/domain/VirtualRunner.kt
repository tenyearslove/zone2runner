package com.zone2runner.app.domain

import java.util.Random

/**
 * 가상 러너 — 시뮬레이터 엔진과 분리된 "달릴 사람"의 특성 (결합도 낮춤, spec-019).
 *
 * 시뮬 엔진(SimRunnerSource)이 이 특성대로 심박/페이스/경사를 생성하고, 앱의 코칭(판정)에 반응하며,
 * 내부적으로 토크테스트에 응답한다. → 폐루프 검증이 가능해진다(실주행 없이 QA1/QA3/예측 검증).
 *
 * trueZone2UpperHrmaxFrac(이 러너의 진짜 유산소 임계, %HRmax)가 "정답"이고, 앱의 개인화가 여기로
 * 수렴하는지가 곧 개인화 기능의 검증이 된다. 앱은 이 값을 모른다(자기참조 방지).
 *
 * 강화(2026-07-06, spec-019): 신체/행동/환경 특성을 세분화하고, 세션마다 범위 안에서 사람처럼
 * 다양하게 발현되도록 randomness 필드를 추가. 모든 기본값은 기존 동작과 호환.
 */
data class VirtualRunner(
    val name: String = "균형형(기본)",
    // ---- 신체 조건 ----
    val age: Int = 35,
    val restingHr: Int = 58,
    val maxHr: Int = 190,
    val trueZone2UpperHrmaxFrac: Double = 0.68, // 진짜 유산소 임계(%HRmax) = 개인화가 수렴해야 할 정답
    val hrLagSec: Double = 28.0,                // 심박 반응 지연(초). 작을수록 빠름=체력 좋음
    val driftRate: Double = 0.45,               // 임계 초과 시 카디악 드리프트 크기
    val driftHeatK: Double = 0.15,              // 기온 민감도(더울수록 드리프트↑)
    val fatigueRate: Double = 0.02,             // 세션 후반 같은 강도의 HR 비용 상승(/30분)
    val hrNoiseSd: Double = 0.9,                // 박동 관측 노이즈(bpm)
    // ---- 달리기 스타일/행동 ----
    val basePaceMinKm: Double = 6.3,            // 편한 기준 페이스
    val pacingDiscipline: Double = 0.6,         // 0=초반 과속/진동, 1=일정 페이스 유지
    val cadenceBase: Int = 168,
    val coachingResponsiveness: Double = 0.6,   // 0=코칭 무시, 1=즉시 순응
    val coachingLagSec: Double = 8.0,           // 코칭 반응까지 지연(초)
    val surgeProneness: Double = 0.2,           // 돌발 서지/감속(신호등/추월) 경향(0~1)
    val talkBias: Double = 0.0,                 // 토크테스트 개인 편향(HRR frac, +면 과대보고=더 벅차다고)
    val talkNoise: Double = 0.15,               // 토크테스트 주관 흔들림(0~0.5)
    // ---- 세션 환경/컨디션 ----
    val terrainHilliness: Double = 0.25,        // 코스 언덕 정도(0=평지, 1=산악)
    val tempC: Double = 18.0,                   // 기온(더위=드리프트↑)
    val dayCondition: Double = 0.0,             // 그날 컨디션(-1 나쁨~+1 좋음): 임계/RHR 미세 이동
    // ---- 센서 아티팩트(강건성 검증용) ----
    val sensorDropoutRate: Double = 0.0,        // 초당 HR 접촉 불량(무효값) 확률
    val sensorSpikeRate: Double = 0.0,          // 초당 순간 스파이크(이상치) 확률
) {
    val hrr: Double get() = (maxHr - restingHr).toDouble()

    /** 진짜 임계 심박(bpm) — 정답. 그날 컨디션으로 미세 이동. 앱 개인화가 이 값으로 수렴하면 성공. */
    val trueUpperBpm: Double get() = (trueZone2UpperHrmaxFrac + dayCondition * 0.015) * maxHr

    /** 사람 요약(피커 표기용). */
    val summary: String get() = "${maxHr}max·안정${restingHr}·임계${(trueZone2UpperHrmaxFrac * 100).toInt()}%"

    companion object {
        val DEFAULT = VirtualRunner()

        /** 사전 설정 러너들 — 입력하기 싫을 때 골라 쓰는 프리셋. */
        val PRESETS: List<VirtualRunner> = listOf(
            DEFAULT,
            VirtualRunner(
                name = "초보 과속형",
                age = 28, restingHr = 68, maxHr = 196, trueZone2UpperHrmaxFrac = 0.63,
                hrLagSec = 34.0, driftRate = 0.55, fatigueRate = 0.04, basePaceMinKm = 6.0,
                pacingDiscipline = 0.25, cadenceBase = 160, coachingResponsiveness = 0.55,
                coachingLagSec = 12.0, surgeProneness = 0.4, talkNoise = 0.2, terrainHilliness = 0.2,
            ),
            VirtualRunner(
                name = "베테랑 절제형",
                age = 45, restingHr = 50, maxHr = 182, trueZone2UpperHrmaxFrac = 0.73,
                hrLagSec = 22.0, driftRate = 0.30, driftHeatK = 0.10, fatigueRate = 0.01,
                basePaceMinKm = 5.3, pacingDiscipline = 0.9, cadenceBase = 176,
                coachingResponsiveness = 0.35, coachingLagSec = 6.0, surgeProneness = 0.1,
                talkNoise = 0.1, terrainHilliness = 0.3,
            ),
            VirtualRunner(
                name = "코칭 잘 따름",
                age = 35, restingHr = 60, maxHr = 190, trueZone2UpperHrmaxFrac = 0.67,
                hrLagSec = 26.0, driftRate = 0.40, basePaceMinKm = 6.2, pacingDiscipline = 0.6,
                cadenceBase = 170, coachingResponsiveness = 0.95, coachingLagSec = 4.0, talkNoise = 0.1,
            ),
            VirtualRunner(
                name = "더위 취약형",
                age = 38, restingHr = 62, maxHr = 188, trueZone2UpperHrmaxFrac = 0.66,
                hrLagSec = 30.0, driftRate = 0.5, driftHeatK = 0.38, fatigueRate = 0.045,
                basePaceMinKm = 6.4, pacingDiscipline = 0.55, cadenceBase = 166,
                coachingResponsiveness = 0.6, tempC = 30.0, terrainHilliness = 0.15, talkBias = 0.03,
            ),
            VirtualRunner(
                name = "언덕 코스",
                age = 33, restingHr = 56, maxHr = 192, trueZone2UpperHrmaxFrac = 0.69,
                hrLagSec = 25.0, driftRate = 0.42, basePaceMinKm = 6.0, pacingDiscipline = 0.7,
                cadenceBase = 172, coachingResponsiveness = 0.6, surgeProneness = 0.25,
                terrainHilliness = 0.85, tempC = 16.0,
            ),
            VirtualRunner(
                name = "코칭 무시형(테스트)",
                coachingResponsiveness = 0.05, pacingDiscipline = 0.35, surgeProneness = 0.5, talkNoise = 0.25,
            ),
            VirtualRunner(
                name = "불안정 센서(테스트)",
                sensorDropoutRate = 0.02, sensorSpikeRate = 0.01, hrNoiseSd = 1.8, terrainHilliness = 0.3,
            ),
        )

        /**
         * 랜덤 사람 생성 — 모집단 범위에서 상관을 고려해 그럴듯한 개인을 뽑는다(spec-019).
         * 체력(fitness 0~1)을 잠재변수로 두고 상관된 특성을 생성: 체력 좋으면 hrLag↓/discipline↑/
         * 임계↑/drift↓ 경향. seed로 재현 가능.
         */
        fun randomHuman(seed: Long): VirtualRunner {
            val r = Random(seed)
            fun u(a: Double, b: Double) = a + r.nextDouble() * (b - a)
            fun nz(m: Double, sd: Double) = m + r.nextGaussian() * sd

            val fitness = r.nextDouble() // 0=초보 ~ 1=엘리트(잠재변수)
            val age = 18 + r.nextInt(45)
            val restingHr = (72 - fitness * 22 + nz(0.0, 4.0)).toInt().coerceIn(38, 82)
            val maxHr = (208 - 0.7 * age + nz(0.0, 6.0)).toInt().coerceIn(160, 205)
            val upper = (0.60 + fitness * 0.13 + nz(0.0, 0.02)).coerceIn(0.58, 0.76)
            val label = if (fitness > 0.66) "상급" else if (fitness > 0.33) "중급" else "초급"
            return VirtualRunner(
                name = "랜덤·$label",
                age = age, restingHr = restingHr, maxHr = maxHr,
                trueZone2UpperHrmaxFrac = upper,
                hrLagSec = (40 - fitness * 20 + nz(0.0, 2.0)).coerceIn(18.0, 42.0),
                driftRate = (0.6 - fitness * 0.3 + nz(0.0, 0.05)).coerceIn(0.22, 0.62),
                driftHeatK = u(0.05, 0.4),
                fatigueRate = (0.06 - fitness * 0.05 + nz(0.0, 0.008)).coerceIn(0.0, 0.06),
                hrNoiseSd = u(0.6, 1.8),
                basePaceMinKm = (7.6 - fitness * 3.2 + nz(0.0, 0.2)).coerceIn(4.2, 7.8),
                pacingDiscipline = (0.2 + fitness * 0.7 + nz(0.0, 0.08)).coerceIn(0.15, 0.97),
                cadenceBase = (156 + fitness * 26 + nz(0.0, 4.0)).toInt().coerceIn(152, 186),
                coachingResponsiveness = u(0.1, 0.95),
                coachingLagSec = u(4.0, 18.0),
                surgeProneness = u(0.0, 0.6),
                talkBias = nz(0.0, 0.04).coerceIn(-0.1, 0.1),
                talkNoise = u(0.08, 0.3),
                terrainHilliness = u(0.0, 0.9),
                tempC = u(6.0, 31.0),
                dayCondition = nz(0.0, 0.5).coerceIn(-1.0, 1.0),
            )
        }
    }
}
