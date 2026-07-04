package com.zone2runner.app.domain

/**
 * 가상 러너 — 시뮬레이터 엔진과 분리된 "달릴 사람"의 특성 (결합도 낮춤).
 *
 * 시뮬 엔진(SimRunnerSource)이 이 특성대로 심박/페이스를 생성하고, 앱의 코칭(판정)에 반응하며,
 * 내부적으로 토크테스트에 응답한다. → 폐루프 검증이 가능해진다:
 *   앱 코칭 → 러너가 페이스 조절 → 심박 변화 → 러너가 토크테스트 응답 → 개인화 학습 → 진짜 Zone2로 수렴.
 *
 * trueZone2UpperHrmaxFrac(이 러너의 진짜 유산소 임계, %HRmax)가 "정답"이고, 앱의 개인화가 여기로
 * 수렴하는지가 곧 개인화 기능의 검증이 된다.
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
    // ---- 달리기 스타일 ----
    val basePaceMinKm: Double = 6.3,            // 편한 기준 페이스
    val pacingDiscipline: Double = 0.6,         // 0=초반 과속(초보), 1=일정 페이스 유지(훈련)
    val cadenceBase: Int = 168,
    // ---- 행동(코칭 반응) ----
    val coachingResponsiveness: Double = 0.6,   // 0=코칭 무시, 1=즉시 순응
    val talkNoise: Double = 0.15,               // 토크테스트 주관 흔들림(0~0.5)
) {
    val hrr: Double get() = (maxHr - restingHr).toDouble()
    /** 진짜 임계 심박(bpm) — 정답. 앱 개인화가 이 값으로 수렴하면 성공. */
    val trueUpperBpm: Double get() = trueZone2UpperHrmaxFrac * maxHr

    companion object {
        val DEFAULT = VirtualRunner()

        /** 사전 설정 러너들 — 입력하기 싫을 때 골라 쓰는 프리셋. */
        val PRESETS: List<VirtualRunner> = listOf(
            DEFAULT,
            VirtualRunner(
                name = "초보 과속형",
                age = 28, restingHr = 68, maxHr = 196, trueZone2UpperHrmaxFrac = 0.63,
                hrLagSec = 34.0, driftRate = 0.55, basePaceMinKm = 6.0, pacingDiscipline = 0.25,
                cadenceBase = 160, coachingResponsiveness = 0.55, talkNoise = 0.2,
            ),
            VirtualRunner(
                name = "베테랑 절제형",
                age = 45, restingHr = 50, maxHr = 182, trueZone2UpperHrmaxFrac = 0.73,
                hrLagSec = 22.0, driftRate = 0.30, basePaceMinKm = 5.3, pacingDiscipline = 0.9,
                cadenceBase = 176, coachingResponsiveness = 0.35, talkNoise = 0.1,
            ),
            VirtualRunner(
                name = "코칭 잘 따름",
                age = 35, restingHr = 60, maxHr = 190, trueZone2UpperHrmaxFrac = 0.67,
                hrLagSec = 26.0, driftRate = 0.40, basePaceMinKm = 6.2, pacingDiscipline = 0.6,
                cadenceBase = 170, coachingResponsiveness = 0.95, talkNoise = 0.1,
            ),
            VirtualRunner(
                name = "코칭 무시형(테스트)",
                coachingResponsiveness = 0.05, pacingDiscipline = 0.35, talkNoise = 0.25,
            ),
        )
    }
}
