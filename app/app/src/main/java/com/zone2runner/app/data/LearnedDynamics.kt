package com.zone2runner.app.data

import android.content.Context

/**
 * 심박 예측 개인 보정 가중치의 세션 간 누적 저장 (spec-018).
 * HrPredictionLearner의 잔차 모델 가중치(w30+w60, 8개)를 저장 → 다음 세션이 이어서 학습.
 * LearnedZone과 같은 패턴(개인화가 세션을 넘어 누적).
 */
object LearnedDynamics {
    private const val PREF = "learned_dynamics"
    private const val KEY_W = "weights"
    private const val KEY_N = "pred_updates" // 누적 예측 보정 학습 횟수(시각화)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Profiles.prefName(ctx, PREF), Context.MODE_PRIVATE)

    /** 저장된 잔차 가중치(8개). 없으면 null. */
    fun weights(ctx: Context): DoubleArray? {
        val s = prefs(ctx).getString(KEY_W, null) ?: return null
        val parts = s.split(",")
        if (parts.size != 8) return null
        return runCatching { DoubleArray(8) { parts[it].toDouble() } }.getOrNull()
    }

    fun updates(ctx: Context): Int = prefs(ctx).getInt(KEY_N, 0)

    fun set(ctx: Context, weights: DoubleArray, updatesThisSession: Int = 0) {
        if (weights.size != 8 || weights.any { !it.isFinite() }) return
        val p = prefs(ctx)
        p.edit().putString(KEY_W, weights.joinToString(","))
            .putInt(KEY_N, p.getInt(KEY_N, 0) + updatesThisSession.coerceAtLeast(0)).apply()
    }

    fun reset(ctx: Context) = prefs(ctx).edit().clear().apply()
    fun clear(ctx: Context, profileId: String) {
        ctx.getSharedPreferences(Profiles.prefNameFor(PREF, profileId), Context.MODE_PRIVATE).edit().clear().apply()
    }
}
