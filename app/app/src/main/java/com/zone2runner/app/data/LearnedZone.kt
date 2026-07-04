package com.zone2runner.app.data

import android.content.Context

/**
 * 세션 누적 개인 Zone2 상단(uFrac) — ThresholdEstimator(NN)의 세션별 추정을 지수이동평균으로
 * 누적 저장(경량 재귀 추정, adr-014 "NN=관측 산출기, 누적 적응"). 다음 세션의 prior로 사용.
 * 미학습이면 null → 공식 prior(Zone2Prior) 사용.
 */
object LearnedZone {
    private const val PREF = "learned_zone"
    private const val KEY_U = "u_frac"
    private const val KEY_N = "n"
    private const val ALPHA = 0.4 // 새 관측 반영률(EMA)

    /** 누적된 개인 uFrac. 학습 이력 없으면 null. */
    fun uFrac(ctx: Context): Double? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!p.contains(KEY_U)) return null
        return p.getFloat(KEY_U, 0.70f).toDouble()
    }

    fun sessionCount(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_N, 0)

    /** NN 세션 추정치로 누적 갱신. 첫 관측은 그대로, 이후 EMA. */
    fun update(ctx: Context, nnUFrac: Double) {
        val u = nnUFrac.coerceIn(0.30, 0.75) // %HRmax 재보정으로 하한 완화(2026-07-04)
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val prev = if (p.contains(KEY_U)) p.getFloat(KEY_U, 0.70f).toDouble() else null
        val next = if (prev == null) u else prev + ALPHA * (u - prev)
        p.edit().putFloat(KEY_U, next.toFloat()).putInt(KEY_N, p.getInt(KEY_N, 0) + 1).apply()
    }
}
