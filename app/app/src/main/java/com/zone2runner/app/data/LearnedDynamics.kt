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

    /** 저장된 잔차 가중치(8개). 없으면 null. */
    fun weights(ctx: Context): DoubleArray? {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_W, null) ?: return null
        val parts = s.split(",")
        if (parts.size != 8) return null
        return runCatching { DoubleArray(8) { parts[it].toDouble() } }.getOrNull()
    }

    fun set(ctx: Context, weights: DoubleArray) {
        if (weights.size != 8 || weights.any { !it.isFinite() }) return
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_W, weights.joinToString(",")).apply()
    }
}
