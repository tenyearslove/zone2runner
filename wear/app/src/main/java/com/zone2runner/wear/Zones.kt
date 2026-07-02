package com.zone2runner.wear

import android.graphics.Color

/**
 * 워치 측 존 판정(단순판) — %HRmax 5구간.
 *
 * 주의: 실제 zone2runner의 "개인화 Zone 2 판정"은 폰에서 다변량 MLP + Bayesian 경계로 수행한다
 * (arch/adr-005, spec-006). 워치는 러닝 중 즉시 피드백용 경량 존 표시만 담당한다.
 */
enum class HrZone(val short: String, val desc: String, val color: Int) {
    Z1("Z1", "회복", Color.parseColor("#5AC8FA")),
    Z2("Z2", "지방연소", Color.parseColor("#30D158")), // 목표 존
    Z3("Z3", "유산소", Color.parseColor("#FFD60A")),
    Z4("Z4", "무산소", Color.parseColor("#FF9F0A")),
    Z5("Z5", "최대", Color.parseColor("#FF3B30"));
}

object Zones {
    // TODO(spec-009): 프로필/연령 연동 시 개인 HRmax/RHR로 대체
    const val HR_MAX = 190
    const val HR_REST = 60

    /** %HRmax 5구간: <60 Z1, 60-70 Z2, 70-80 Z3, 80-90 Z4, >=90 Z5 */
    fun zoneOf(bpm: Int): HrZone {
        val pct = bpm.toDouble() / HR_MAX * 100
        return when {
            pct < 60 -> HrZone.Z1
            pct < 70 -> HrZone.Z2
            pct < 80 -> HrZone.Z3
            pct < 90 -> HrZone.Z4
            else -> HrZone.Z5
        }
    }

    /** 게이지 마커 위치 0..1 (50%~100% HRmax를 5존에 균등 매핑). */
    fun gaugeFraction(bpm: Int): Float {
        val pct = bpm.toDouble() / HR_MAX * 100
        return ((pct - 50) / 50).coerceIn(0.0, 1.0).toFloat()
    }

    /** Zone 2 목표 심박 범위(bpm). */
    val zone2Bpm: IntRange
        get() = (HR_MAX * 0.60).toInt()..(HR_MAX * 0.70).toInt()
}
