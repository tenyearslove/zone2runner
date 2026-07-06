package com.zone2runner.app.pipeline

/**
 * 코칭 케이던스(초 단위 간격) — 설정의 코칭 빈도(0..4, spec-021)에서 파생.
 *   minGapSec  : 어떤 코칭이든 최소 간격(연속 발화 방지).
 *   overdueSec : 존 밖(미달/초과)에 계속 머물 때 재코칭 주기.
 *   idleReSec  : 정지 상태 재안내 주기.
 * level 2(보통)가 기존 하드코딩 값(20/60/40)과 동일 — 하위 호환.
 */
data class CoachCadence(
    val minGapSec: Int,
    val overdueSec: Int,
    val idleReSec: Int,
) {
    companion object {
        private val TABLE = listOf(
            CoachCadence(40, 120, 80), // 0 최소
            CoachCadence(30, 90, 60),  // 1 적게
            CoachCadence(20, 60, 40),  // 2 보통(기존값)
            CoachCadence(12, 40, 30),  // 3 자주
            CoachCadence(8, 25, 20),   // 4 매우 자주
        )
        val DEFAULT = TABLE[2]
        fun forLevel(level: Int): CoachCadence = TABLE[level.coerceIn(0, TABLE.size - 1)]
    }
}
