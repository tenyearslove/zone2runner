package com.zone2runner.app.domain

import android.graphics.Color

/** 사용자 프로필. Zone2 경계 산정의 사전값(공식). spec-009 연동 전 기본값 사용. */
data class Profile(val age: Int, val restingHr: Int, val maxHr: Int) {
    val hrr: Double get() = (maxHr - restingHr).toDouble()
    companion object {
        fun default(age: Int = 35, restingHr: Int = 58) =
            Profile(age, restingHr, (208 - 0.7 * age).toInt()) // Tanaka
    }
}

/** 1Hz 센서 샘플(시뮬레이터/실기기 공통). hr은 원시값(이상치 포함 가능). */
data class Sample(
    val tSec: Int,
    val hr: Int,
    val paceMinKm: Double,
    val spm: Int,
    val slopePct: Double,
    val lat: Double,
    val lon: Double,
)

/** MLP 판정 결과: Zone2 대비 상태. */
enum class ZoneJudgment(val label: String, val color: Int) {
    BELOW("미달", Color.parseColor("#5AC8FA")),
    IN("Zone 2 유지", Color.parseColor("#30D158")),
    ABOVE("초과", Color.parseColor("#FF9F0A"));

    companion object {
        fun fromIndex(i: Int) = when (i) { 0 -> BELOW; 2 -> ABOVE; else -> IN }
    }
}

/** 개인 Zone2 경계(HRR 대비 비율). 개인화(Bayesian)가 갱신. */
data class Zone2Boundary(val uFrac: Double, val lFrac: Double) {
    companion object { val FORMULA = Zone2Boundary(0.70, 0.60) }
}

/** 라이브 대시보드 상태(재생/실기기 공통). */
data class LiveState(
    val elapsedSec: Int = 0,
    val hr: Int = -1,
    val judgment: ZoneJudgment? = null,
    val paceMinKm: Double = 0.0,
    val speedKmh: Double = 0.0,
    val distanceM: Double = 0.0,
    val coaching: String = "",
    val uEstFrac: Double = 0.70,
)

/** 경로 점(존 색으로 폴리라인 채색). */
data class TrackPoint(val lat: Double, val lon: Double, val judgment: ZoneJudgment?)

/** 세션 종료 리포트. */
data class RunReport(
    val durationSec: Int,
    val distanceM: Double,
    val avgHr: Int,
    val maxHr: Int,
    val belowSec: Int,
    val inSec: Int,
    val aboveSec: Int,
    val avgPaceMinKm: Double,
    val coachingLines: List<String>,
    val track: List<TrackPoint>,
    val uEstStartFrac: Double,
    val uEstEndFrac: Double,
    val restingHr: Int,
    val maxHrProfile: Int,
) {
    val zone2Pct: Int
        get() = if (durationSec > 0) (inSec * 100 / durationSec) else 0
}
