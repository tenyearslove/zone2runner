package com.zone2runner.app.domain

import android.graphics.Color

/**
 * 사용자 프로필. Zone2 경계 산정의 사전값(공식+factor, adr-012/spec-013).
 * bodyType/fitnessLevel/weeklyFreq: 1~5 단계(3=중앙). 기존 저장값은 기본값으로 로드(하위 호환).
 */
data class Profile(
    val age: Int,
    val restingHr: Int,
    val maxHr: Int,
    val heightCm: Int = 170,
    val weightKg: Int = 70,
    val bodyType: Int = 3,      // 1 매우마른형 ~ 5 비만형
    val fitnessLevel: Int = 3,  // 1 입문 ~ 5 엘리트
    val weeklyFreq: Int = 3,    // 1 거의 안 함 ~ 5 거의 매일
    val rhrEstimated: Boolean = false, // RHR 모름 → factor 기반 추정치 사용 중(spec-013)
) {
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

    val index: Int get() = when (this) { BELOW -> 0; IN -> 1; ABOVE -> 2 }

    companion object {
        fun fromIndex(i: Int) = when (i) { 0 -> BELOW; 2 -> ABOVE; else -> IN }
        /** -1 이면 판정 없음(null). */
        fun fromIndexOrNull(i: Int): ZoneJudgment? = if (i < 0) null else fromIndex(i)
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
    val smoothedHr: Int = -1,          // 최근 60초 평균 심박 — 판정/밴드 마커 기준(순간 hr과 구분)
    val judgment: ZoneJudgment? = null,
    val paceMinKm: Double = 0.0,
    val speedKmh: Double = 0.0,
    val distanceM: Double = 0.0,
    val coaching: String = "",
    val uEstFrac: Double = 0.70,
    // 실시간 판정 요소(MLP 입력 특징의 표시용 부분집합, spec-011 대시보드)
    val slopePct: Double = 0.0,       // 경사 % (오르막 +)
    val spm: Int = 0,                 // 케이던스
    val decoupling: Double? = null,   // 드리프트(디커플링 비율), 워밍업 전 null
    val dHrPerSec: Double? = null,    // 심박 추세(bpm/s), 워밍업 전 null
    // 심박 동역학 모델 출력(spec-014). 미로드/워밍업 전 = -1/0.0
    val predictedHr60: Int = -1,          // 현재 페이스 유지 시 60초 뒤 예측 심박(bpm)
    val recommendedPaceMinKm: Double = 0.0, // Zone2 목표 페이스 제안(min/km), 0=없음
)

/** 경로 점(존 색으로 폴리라인 채색). */
data class TrackPoint(val lat: Double, val lon: Double, val judgment: ZoneJudgment?)

/** 리포트 시계열 한 점(HR/페이스 차트, 심혈관 드리프트 분석용, 다운샘플). */
data class SeriesPoint(
    val tSec: Int,
    val hr: Int,
    val paceMinKm: Double,
    val judgmentIndex: Int, // -1=판정없음, 0=미달, 1=유지, 2=초과
)

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
    val series: List<SeriesPoint> = emptyList(),
    val id: String = "",                   // 저장 식별자(에폭ms 기반), SessionStore가 채움
    val startedAtEpochMs: Long = 0L,       // 세션 시작 시각
    val usedModel: Boolean = true,         // MLP 사용(true) vs 규칙 폴백(false)
    val coachSource: String = "rule",      // 코칭 표현 소스(rule/llm)
    val sourceMode: String = "sim",        // 입력 소스(sim/live)
    val avgSpm: Int = 0,                   // 평균 케이던스(spm). 0=미상(구버전 세션)
) {
    /** 평균 보폭(m) = 총거리 / 총걸음수(케이던스 적분 근사). 케이던스 미상이면 null. */
    val avgStrideM: Double?
        get() {
            if (avgSpm <= 0 || durationSec <= 0 || distanceM < 1) return null
            val steps = avgSpm.toDouble() * durationSec / 60.0
            return if (steps > 0) distanceM / steps else null
        }
    val zone2Pct: Int
        get() = if (durationSec > 0) (inSec * 100 / durationSec) else 0

    /**
     * 심혈관 드리프트(Cardiac Drift) 추정: 세션 전반부 대비 후반부의 HR/페이스 비율 상승률(%).
     * 유산소(Zone2) 지속의 대표 지표. 같은 페이스에서 HR이 오르면 드리프트 증가.
     */
    val cardiacDriftPct: Double
        get() {
            val s = series.filter { it.hr > 0 && it.paceMinKm in 0.1..30.0 }
            if (s.size < 8) return 0.0
            val half = s.size / 2
            // HR/속도(= hr*pace/60, EF 역수) 기반 — hr/pace는 강도 변화에 지배돼 드리프트 지표로 부적합
            fun ratio(sub: List<SeriesPoint>): Double {
                var acc = 0.0; var c = 0
                for (p in sub) { acc += p.hr * p.paceMinKm / 60.0; c++ }
                return if (c > 0) acc / c else 0.0
            }
            val r1 = ratio(s.subList(0, half))
            val r2 = ratio(s.subList(half, s.size))
            return if (r1 > 0) (r2 / r1 - 1.0) * 100.0 else 0.0
        }
}
