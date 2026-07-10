package com.zone2runner.app.analysis

import com.zone2runner.app.domain.Profile
import com.zone2runner.app.domain.Zone2Boundary

/**
 * 관측 데이터 분석 엔진(FR3, spec-025) — 파생지표 하나의 계약.
 *
 * 강의 AI System의 "분석 서비스" 컴포넌트. 각 지표는 단일 책임(SRP)의 작은 모듈이고,
 * 엔진 레지스트리에 등록만으로 추가된다(OCP — 엔진 코드 무수정). 판단선은 개인 k·σ(마법상수 금지, CLAUDE.md).
 *
 * mode에 따라 실시간(onTick)과 세션종료(onSessionEnd) 중 필요한 쪽만 override 한다(기본 no-op).
 */
interface AnalysisMetric {
    val id: String
    val mode: MetricMode

    /** 매 틱(1Hz) 실시간 산출. 미준비/게이트아웃이면 null. REALTIME/BOTH만 호출됨. */
    fun onTick(input: AnalysisInput): MetricSample? = null

    /** 세션 종료 시 전체 구간(input.window = 세션 전체) 산출. SESSION_END/BOTH만 호출됨. */
    fun onSessionEnd(input: AnalysisInput): MetricSample? = null
}

enum class MetricMode { REALTIME, SESSION_END, BOTH }

enum class Trend { UP, FLAT, DOWN, UNKNOWN }

/**
 * 지표 산출 결과(種類A 도출값). value 외에 통계(se/r2)와 추세/게이트 상태를 함께 전달해
 * 소비처(FR5 트리거/FR6 표시)가 "얼마나 믿을지"를 판단할 수 있게 한다.
 */
data class MetricSample(
    val id: String,
    val value: Double,
    val se: Double? = null,     // 도출 통계량 표준오차(있으면). 판단선 k·se 용
    val r2: Double? = null,
    val trend: Trend = Trend.UNKNOWN,
    val gated: Boolean = false,  // 노이즈/정속 게이트로 이번 표본 제외됨
    val note: String = "",       // 설명용이성용 짧은 근거 문구
)

/** 엔진이 각 지표에 넘기는 불변 입력 스냅샷. window = 신호 롤링 뷰(실시간) 또는 세션 전체(종료 시). */
data class AnalysisInput(
    val tSec: Int,
    val profile: Profile,
    val boundary: Zone2Boundary,
    val window: SignalWindow,
)
