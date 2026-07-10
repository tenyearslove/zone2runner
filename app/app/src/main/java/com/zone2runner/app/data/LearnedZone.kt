package com.zone2runner.app.data

import android.content.Context
import com.zone2runner.app.domain.Zone2Prior

/**
 * 세션 누적 개인 Zone2 상단(uFrac) — 세션 종료 시 개인화(Personalization) 최종 경계를 직접 저장한다.
 * 값은 이미 Personalization 안에서 prior + 토크테스트 + 디커플링이 융합/스무딩된 결과이므로
 * 여기서 추가 평활은 하지 않는다(adr-004/adr-016). 다음 세션의 prior로 사용.
 * 미학습이면 null → 공식 prior(Zone2Prior) 사용.
 *
 * spec-020: 활성 프로필별 네임스페이스 + 세션별 uFrac 이력(시각화) + 누적 말하기 관측 수.
 */
object LearnedZone {
    private const val PREF = "learned_zone"
    private const val KEY_U = "u_frac"
    private const val KEY_N = "n"
    private const val KEY_HIST = "u_history"  // 세션별 uFrac, 콤마 구분(최근 HIST_CAP)
    private const val KEY_TALK = "talk_obs"   // 누적 말하기 테스트 관측 수
    private const val KEY_SIGMA = "sigma_bpm" // 최근 세션 종료 시 개인화 불확실성 σ(bpm)
    private const val KEY_EXPLAIN = "explain" // 세션 종료 시 1회 생성한 개인화 설명(이후 재사용, LLM 재호출 없음) spec-023
    // 분석 엔진 드리프트 개인 노이즈플로어(種類B, spec-025 §4) — 세션 간 누적 EWMA 상태
    private const val KEY_DF_MEAN = "drift_floor_mean"
    private const val KEY_DF_VAR = "drift_floor_var"
    private const val KEY_DF_N = "drift_floor_n"
    private const val HIST_CAP = 50

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Profiles.prefName(ctx, PREF), Context.MODE_PRIVATE)

    /** 세션 종료 시 저장된 개인화 설명(LLM 풀이 또는 규칙 폴백). 없으면 null → 프로필은 규칙 팩트 표시. */
    fun explanation(ctx: Context): String? = prefs(ctx).getString(KEY_EXPLAIN, null)?.takeIf { it.isNotBlank() }
    fun setExplanation(ctx: Context, text: String) = prefs(ctx).edit().putString(KEY_EXPLAIN, text).apply()

    /** 누적된 개인 uFrac. 학습 이력 없으면 null. */
    fun uFrac(ctx: Context): Double? {
        val p = prefs(ctx)
        if (!p.contains(KEY_U)) return null
        return p.getFloat(KEY_U, 0.70f).toDouble()
    }

    fun sessionCount(ctx: Context): Int = prefs(ctx).getInt(KEY_N, 0)

    fun talkObs(ctx: Context): Int = prefs(ctx).getInt(KEY_TALK, 0)

    /** 최근 개인화 불확실성 σ(bpm). 학습 이력 없으면 null. */
    fun sigmaBpm(ctx: Context): Double? {
        val p = prefs(ctx); if (!p.contains(KEY_SIGMA)) return null
        return p.getFloat(KEY_SIGMA, 8f).toDouble()
    }

    /** 세션별 uFrac 이력(오래된→최근). 시각화 스파크라인용. */
    fun history(ctx: Context): List<Double> {
        val s = prefs(ctx).getString(KEY_HIST, "") ?: ""
        return s.split(",").mapNotNull { it.toDoubleOrNull() }
    }

    /**
     * 세션 종료 시 최종 개인화 경계(uFrac)를 저장 + 이력/관측수 누적.
     * talkObsThisSession: 이번 세션에 사용자가 응답한 말하기 테스트 횟수(누적에 더함).
     */
    fun set(ctx: Context, finalUFrac: Double, talkObsThisSession: Int = 0, sigmaBpm: Double = 8.0) {
        val u = finalUFrac.coerceIn(Zone2Prior.U_FRAC_MIN, Zone2Prior.U_FRAC_MAX)
        val p = prefs(ctx)
        val hist = (history(ctx) + u).takeLast(HIST_CAP)
        p.edit()
            .putFloat(KEY_U, u.toFloat())
            .putInt(KEY_N, p.getInt(KEY_N, 0) + 1)
            .putInt(KEY_TALK, p.getInt(KEY_TALK, 0) + talkObsThisSession.coerceAtLeast(0))
            .putFloat(KEY_SIGMA, sigmaBpm.toFloat())
            .putString(KEY_HIST, hist.joinToString(",") { "%.4f".format(it) })
            .apply()
    }

    /**
     * 드리프트 개인 노이즈플로어 상태(mean, var, count). 학습 이력 없으면 null → 중립 prior로 시작.
     * spec-025 §4: 반응형 코칭 판단선 m+k·σ̂의 개인화 근거.
     */
    fun driftFloor(ctx: Context): Triple<Double, Double, Int>? {
        val p = prefs(ctx); if (!p.contains(KEY_DF_N)) return null
        return Triple(
            p.getFloat(KEY_DF_MEAN, Float.NaN).toDouble(),
            p.getFloat(KEY_DF_VAR, Float.NaN).toDouble(),
            p.getInt(KEY_DF_N, 0),
        )
    }

    /** 세션 종료 시 드리프트 노이즈플로어 상태 저장(다음 세션이 이어서 개인화). */
    fun setDriftFloor(ctx: Context, mean: Double, varr: Double, count: Int) {
        prefs(ctx).edit()
            .putFloat(KEY_DF_MEAN, mean.toFloat())
            .putFloat(KEY_DF_VAR, varr.toFloat())
            .putInt(KEY_DF_N, count)
            .apply()
    }

    /** 개인화 학습 데이터만 초기화(신체 정보는 ProfileStore라 유지). spec-020 FR4. */
    fun reset(ctx: Context) = prefs(ctx).edit().clear().apply()

    /** 프로필 삭제 시 그 학습 데이터 제거. */
    fun clear(ctx: Context, profileId: String) {
        ctx.getSharedPreferences(Profiles.prefNameFor(PREF, profileId), Context.MODE_PRIVATE).edit().clear().apply()
    }
}
