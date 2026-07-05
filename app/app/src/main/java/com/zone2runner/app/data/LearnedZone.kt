package com.zone2runner.app.data

import android.content.Context
import com.zone2runner.app.domain.Zone2Prior

/**
 * 세션 누적 개인 Zone2 상단(uFrac) — 세션 종료 시 개인화(Personalization) 최종 경계를 직접 저장한다.
 * 값은 이미 Personalization 안에서 prior + 토크테스트 + 디커플링이 융합/스무딩된 결과이므로
 * 여기서 추가 평활은 하지 않는다(adr-004/adr-016). 다음 세션의 prior로 사용.
 * 미학습이면 null → 공식 prior(Zone2Prior) 사용.
 */
object LearnedZone {
    private const val PREF = "learned_zone"
    private const val KEY_U = "u_frac"
    private const val KEY_N = "n"

    /** 누적된 개인 uFrac. 학습 이력 없으면 null. */
    fun uFrac(ctx: Context): Double? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!p.contains(KEY_U)) return null
        return p.getFloat(KEY_U, 0.70f).toDouble()
    }

    fun sessionCount(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_N, 0)

    /**
     * 세션 종료 시 최종 개인화 경계(uFrac)를 저장. 이 값은 이미 Personalization 안에서
     * prior(직전 저장값) + 토크테스트 + NN 역치 + 디커플링이 융합/스무딩된 결과이므로 직접 저장한다.
     * 토크테스트 보정이 세션을 넘어 누적되게 하는 핵심(사용자 요청).
     */
    fun set(ctx: Context, finalUFrac: Double) {
        val u = finalUFrac.coerceIn(Zone2Prior.U_FRAC_MIN, Zone2Prior.U_FRAC_MAX)
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        p.edit().putFloat(KEY_U, u.toFloat()).putInt(KEY_N, p.getInt(KEY_N, 0) + 1).apply()
    }
}
