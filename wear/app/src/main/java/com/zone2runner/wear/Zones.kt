package com.zone2runner.wear

import android.content.Context
import android.graphics.Color

/**
 * 워치 측 존 판정(경량판).
 *
 * 기준 동기화: 폰이 프로필 기반 Zone 2 경계(adr-012 prior)를 Data Layer `/zones`로 푸시하면
 * (ZoneSyncService) 저장해 사용한다 — 같은 심박에 워치/폰 존 표시가 어긋나는 문제 해소.
 * 미수신(폰 미연동) 시 기존 %HRmax(190) 기본값으로 동작한다.
 *
 * 주의: 실제 "개인화 Zone 2 판정"은 폰에서 규칙(ZoneJudge) + 온라인 Bayesian 경계로 수행(adr-013/016).
 * 워치는 러닝 중 즉시 피드백용 존 표시만 담당한다.
 */
enum class HrZone(val short: String, val desc: String, val color: Int) {
    Z1("Z1", "회복", Color.parseColor("#5AC8FA")),
    Z2("Z2", "지방연소", Color.parseColor("#30D158")), // 목표 존
    Z3("Z3", "유산소", Color.parseColor("#FFD60A")),
    Z4("Z4", "무산소", Color.parseColor("#FF9F0A")),
    Z5("Z5", "최대", Color.parseColor("#FF3B30"));
}

object Zones {
    // 폰 미연동 기본값 — 기존 %HRmax 190 동작과 동일(Z2 114~133)
    private const val DEF_MAX = 190
    @Volatile private var maxHr = DEF_MAX
    @Volatile private var z2Lo = (DEF_MAX * 0.60).toInt()
    @Volatile private var z2Hi = (DEF_MAX * 0.70).toInt()
    @Volatile var synced = false // 폰에서 경계를 받은 적 있는지(UI 표시용)
        private set

    private const val PREF = "zones"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!p.getBoolean("synced", false)) return
        maxHr = p.getInt("max_hr", DEF_MAX)
        z2Lo = p.getInt("z2_lo", z2Lo)
        z2Hi = p.getInt("z2_hi", z2Hi)
        synced = true
    }

    /** 폰이 보낸 개인 경계 저장 + 즉시 적용 (ZoneSyncService). */
    fun save(ctx: Context, maxHrIn: Int, lo: Int, hi: Int) {
        if (maxHrIn !in 120..230 || lo !in 60..220 || hi !in lo + 1..229) return // 이상 payload 무시
        maxHr = maxHrIn; z2Lo = lo; z2Hi = hi; synced = true
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("synced", true)
            .putInt("max_hr", maxHrIn).putInt("z2_lo", lo).putInt("z2_hi", hi)
            .apply()
    }

    /**
     * 5구간: Z2 = 개인 경계(폰과 동일 기준). Z1 = 그 아래.
     * Z3~Z5 = Z2 상한~최대심박을 3등분(개인 상한 기준의 상대 구간).
     */
    fun zoneOf(bpm: Int): HrZone = zoneOf(bpm, z2Lo, z2Hi, maxHr)

    /** 경계를 명시로 받는 판정(adr-022: 폰이 매초 보낸 지속심박+개인 경계로 존 표시). */
    fun zoneOf(bpm: Int, lo: Int, hi: Int, max: Int): HrZone {
        if (bpm < lo) return HrZone.Z1
        if (bpm <= hi) return HrZone.Z2
        val seg = ((max - hi) / 3.0).coerceAtLeast(1.0)
        return when {
            bpm <= hi + seg -> HrZone.Z3
            bpm <= hi + 2 * seg -> HrZone.Z4
            else -> HrZone.Z5
        }
    }

    /** 게이지 마커 위치 0..1 — Z1 시작(Z2 하한 - 밴드폭)부터 최대심박까지 선형. */
    fun gaugeFraction(bpm: Int): Float = gaugeFraction(bpm, z2Lo, z2Hi, maxHr)

    fun gaugeFraction(bpm: Int, lo: Int, hi: Int, max: Int): Float {
        val start = lo - (hi - lo)
        val range = (max - start).coerceAtLeast(1)
        return ((bpm - start).toDouble() / range).coerceIn(0.0, 1.0).toFloat()
    }

    /** Zone 2 목표 심박 범위(bpm). */
    val zone2Bpm: IntRange get() = z2Lo..z2Hi
}
