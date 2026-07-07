package com.zone2runner.wear

import android.graphics.Color

/**
 * 존 표시 메타데이터(색/라벨) — 폰 app의 DisplayZone과 인덱스/hex 동일(폰-워치 공통 5색).
 * adr-023: 워치는 존을 계산하지 않는다. 폰이 확정한 존 인덱스(/run/live)를 그대로 그린다.
 * (구 Zones의 자체 판정/경계 저장/`/zones` 동기화는 adr-023으로 삭제)
 */
enum class HrZone(val idx: Int, val short: String, val desc: String, val color: Int) {
    Z1(1, "Z1", "회복", Color.parseColor("#5AC8FA")),
    Z2(2, "Z2", "지방연소", Color.parseColor("#30D158")), // 목표 존
    Z3(3, "Z3", "유산소", Color.parseColor("#FFD60A")),
    Z4(4, "Z4", "무산소", Color.parseColor("#FF9F0A")),
    Z5(5, "Z5", "최대", Color.parseColor("#FF3B30"));

    companion object {
        fun fromIdx(i: Int): HrZone? = values().firstOrNull { it.idx == i }
    }
}
