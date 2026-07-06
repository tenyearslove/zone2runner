package com.zone2runner.app.data

import android.content.Context

/**
 * 심박 예측 ODE 개인 파라미터의 세션 간 누적 저장 (adr-020).
 * HrOdeModel의 개인 파라미터 [τ, drift, a, b, c] 5개를 저장 → 다음 세션이 이어서 개인화.
 * LearnedZone과 같은 패턴(개인화가 세션을 넘어 누적). (구 spec-018 잔차 가중치 8개에서 이행.)
 */
object LearnedDynamics {
    private const val PREF = "learned_dynamics"
    private const val KEY_P = "ode_params"
    private const val KEY_N = "pred_updates" // 누적 예측 학습 횟수(시각화)
    private const val N_PARAMS = 5

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Profiles.prefName(ctx, PREF), Context.MODE_PRIVATE)

    /** 저장된 ODE 파라미터(5개). 없으면(또는 구 형식) null → 모집단 prior로 시작. */
    fun params(ctx: Context): DoubleArray? {
        val s = prefs(ctx).getString(KEY_P, null) ?: return null
        val parts = s.split(",")
        if (parts.size != N_PARAMS) return null
        return runCatching { DoubleArray(N_PARAMS) { parts[it].toDouble() } }.getOrNull()
    }

    fun updates(ctx: Context): Int = prefs(ctx).getInt(KEY_N, 0)

    fun set(ctx: Context, params: DoubleArray, updatesThisSession: Int = 0) {
        if (params.size != N_PARAMS || params.any { !it.isFinite() }) return
        val p = prefs(ctx)
        p.edit().putString(KEY_P, params.joinToString(","))
            .putInt(KEY_N, p.getInt(KEY_N, 0) + updatesThisSession.coerceAtLeast(0)).apply()
    }

    fun reset(ctx: Context) = prefs(ctx).edit().clear().apply()
    fun clear(ctx: Context, profileId: String) {
        ctx.getSharedPreferences(Profiles.prefNameFor(PREF, profileId), Context.MODE_PRIVATE).edit().clear().apply()
    }
}
