package com.zone2runner.app.domain

/**
 * 표시 존(Z1~Z5, adr-023) — 폰이 유일한 판정 주체. 워치는 존 인덱스를 받아 그대로 그린다.
 * 색은 폰-워치 공통(워치 HrZone과 hex 동일). Android 의존을 피하려 hex 문자열로 보관하고
 * UI가 사용 지점에서 파싱한다(JVM 단위 테스트 가능).
 *
 * 표시 존은 "순간심박" 기준(DisplayZoneJudge) — 화면의 큰 숫자와 존 색이 일치.
 * 코칭/통계/개인화는 60초 지속심박(ZoneJudge, adr-013 역할 분리)으로 별도 유지.
 */
enum class DisplayZone(val idx: Int, val short: String, val desc: String, val colorHex: String) {
    Z1(1, "Z1", "회복", "#5AC8FA"),
    Z2(2, "Z2", "지방연소", "#30D158"),
    Z3(3, "Z3", "유산소", "#FFD60A"),
    Z4(4, "Z4", "무산소", "#FF9F0A"),
    Z5(5, "Z5", "최대", "#FF3B30");

    companion object {
        fun fromIdx(i: Int): DisplayZone? = values().firstOrNull { it.idx == i }
    }
}

/** 존 구간 분할 — Z2 = [lo, hi], Z1 = 그 아래, Z3~Z5 = 상한~최대심박 3등분(구 워치 Zones.zoneOf와 동일). */
object DisplayZones {

    fun rawZone(bpm: Int, lo: Int, hi: Int, maxHr: Int): DisplayZone {
        if (bpm < lo) return DisplayZone.Z1
        if (bpm <= hi) return DisplayZone.Z2
        val seg = ((maxHr - hi) / 3.0).coerceAtLeast(1.0)
        return when {
            bpm <= hi + seg -> DisplayZone.Z3
            bpm <= hi + 2 * seg -> DisplayZone.Z4
            else -> DisplayZone.Z5
        }
    }

    /**
     * 존 내 위치 0..1 — 워치 등폭 게이지의 마커 각도 = (idx-1 + frac)/5.
     * Z1은 하한 아래 1밴드폭 구간으로 간주(그보다 낮으면 0에 고정).
     */
    fun withinFrac(bpm: Int, zone: DisplayZone, lo: Int, hi: Int, maxHr: Int): Double {
        val band = (hi - lo).coerceAtLeast(1).toDouble()
        val seg = ((maxHr - hi) / 3.0).coerceAtLeast(1.0)
        val (start, width) = when (zone) {
            DisplayZone.Z1 -> (lo - band) to band
            DisplayZone.Z2 -> lo.toDouble() to band
            DisplayZone.Z3 -> hi.toDouble() to seg
            DisplayZone.Z4 -> (hi + seg) to seg
            DisplayZone.Z5 -> (hi + 2 * seg) to (maxHr - hi - 2 * seg).coerceAtLeast(1.0)
        }
        return ((bpm - start) / width).coerceIn(0.0, 1.0)
    }
}

/**
 * 표시 존 판정기(adr-023): 순간심박 + 히스테리시스.
 * 경계 근처 센서 노이즈(±수 bpm)로 색이 매초 깜빡이는 것을 막는다 — 존 변경은
 *   (a) 새 존 쪽으로 경계를 marginBpm 초과해 넘었을 때 즉시, 또는
 *   (b) 경계 ±margin 안이라도 같은 새 존이 holdTicks 연속 관측될 때(1Hz 기준 = 초).
 */
class DisplayZoneJudge(private val marginBpm: Int = 2, private val holdTicks: Int = 3) {

    private var current: DisplayZone? = null
    private var pending: DisplayZone? = null
    private var pendingTicks = 0

    fun judge(bpm: Int, lo: Int, hi: Int, maxHr: Int): DisplayZone {
        val raw = DisplayZones.rawZone(bpm, lo, hi, maxHr)
        val cur = current ?: raw.also { current = it }
        if (raw == cur) { pending = null; pendingTicks = 0; return cur }
        // 현재 존 쪽으로 margin만큼 되돌려 봐도 여전히 다른 존이면 경계를 확실히 벗어남 → 즉시 전환
        val pulledBack = if (raw.idx > cur.idx) bpm - marginBpm else bpm + marginBpm
        if (DisplayZones.rawZone(pulledBack, lo, hi, maxHr) != cur) {
            current = raw; pending = null; pendingTicks = 0; return raw
        }
        // 경계 ±margin 안: 같은 후보가 holdTicks 연속이면 전환(느린 진짜 이동), 아니면 유지(노이즈)
        if (pending == raw) pendingTicks++ else { pending = raw; pendingTicks = 1 }
        if (pendingTicks >= holdTicks) { current = raw; pending = null; pendingTicks = 0; return raw }
        return cur
    }

    fun reset() { current = null; pending = null; pendingTicks = 0 }
}
