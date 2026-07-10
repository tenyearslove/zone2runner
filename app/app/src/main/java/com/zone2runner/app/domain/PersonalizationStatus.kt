package com.zone2runner.app.domain

/**
 * 개인화 진행 현황 — 시각화 입력 값객체 (spec-020 FR3). 순수 데이터(안드로이드 무관, 테스트 용이).
 * 프로필 공식 초기값(prior)과 학습된 현재 경계를 bpm으로 대비해 "기준이 얼마나 이동했나"를 보여준다.
 */
data class PersonalizationStatus(
    val restingHr: Int,
    val hrr: Double,
    val priorUFrac: Double,          // 프로필 factor 기반 초기값(공식)
    val learnedUFrac: Double?,       // 세션 누적 학습값(없으면 null = 아직 공식)
    val band: Double,                // Zone2 밴드폭(HRR 비율)
    val sessions: Int,               // 러닝 세션 수
    val talkObs: Int,                // 말하기 테스트 누적 관측 수(주 라벨)
    val sigmaBpm: Double?,           // 개인화 불확실성 σ(bpm, 작을수록 확신)
    val uFracHistory: List<Double>,  // 세션별 uFrac(스파크라인)
) {
    val learned: Boolean get() = learnedUFrac != null && sessions > 0
    private fun bpm(u: Double) = (restingHr + u * hrr).toInt()

    val priorHi: Int get() = bpm(priorUFrac)
    val priorLo: Int get() = bpm(priorUFrac - band)
    val curUFrac: Double get() = learnedUFrac ?: priorUFrac
    val curHi: Int get() = bpm(curUFrac)
    val curLo: Int get() = bpm(curUFrac - band)

    /** 상단 경계 이동량(bpm, 학습−초기). 양수=위로 이동. */
    val shiftHiBpm: Int get() = curHi - priorHi

    /** 진행 단계 라벨(사용자 친화 — 설계 용어 없이). */
    val stageLabel: String get() = when {
        !learned -> "시작 전 (공식 초기값)"
        sessions < 2 -> "학습 시작"
        sigmaBpm != null && sigmaBpm > 7.0 -> "학습 중"
        sigmaBpm != null && sigmaBpm > 4.5 -> "안정화 중"
        else -> "안정적"
    }
}
