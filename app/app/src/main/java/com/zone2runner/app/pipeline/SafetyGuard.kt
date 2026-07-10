package com.zone2runner.app.pipeline

/**
 * 안전 가드(spec-008) — 위험 고심박 지속 시 즉시 중단/감속 권고.
 * 결정론 규칙, **LLM 우회**(지연/오작동 배제). 이상치 필터(OutlierGuard)를 통과한 "생리적으로 유효하지만
 * 위험한" 값에만 발동. profile.maxHr/현재 HR만 의존(분석 엔진/코칭 LLM과 무결합). 의료기기 아님(C03).
 *
 * 상수(種類C): 최대심박 95% 이상이 15초 지속 시 경고, 30초마다 재경고(과다 알림 억제).
 */
class SafetyGuard(
    private val pctOfMax: Double = PCT_OF_MAX,
    private val holdSec: Int = HOLD_SEC,
    private val reAlertSec: Int = REALERT_SEC,
) {
    private var overSec = 0
    private var lastAlertSec = -9999

    /** 위험 지속이면 권고 문자열, 아니면 null. tSec/hr은 이상치 제거된 값. maxHr<=0이면 판정 불가(null). */
    fun check(tSec: Int, hr: Int, maxHr: Int): String? {
        if (maxHr <= 0) return null
        if (hr >= pctOfMax * maxHr) overSec++ else { overSec = 0; return null }
        if (overSec < holdSec || tSec - lastAlertSec < reAlertSec) return null
        lastAlertSec = tSec
        return "심박이 많이 높습니다. 속도를 낮추거나 잠시 멈추세요."
    }

    companion object {
        const val PCT_OF_MAX = 0.95
        const val HOLD_SEC = 15
        const val REALERT_SEC = 30
    }
}
