package com.zone2runner.app.data

import android.content.Context
import com.zone2runner.app.domain.Profile

/**
 * 사용자 프로필 영속화 (spec-009: 나이/안정HR/최대HR).
 * 개인 Zone2 경계 산정의 사전값(공식)을 개인 신체값으로 시작하기 위한 최소 저장소.
 * DB 불필요 — SharedPreferences로 충분.
 */
object ProfileStore {
    private const val PREF = "zone2_profile"
    private const val K_AGE = "age"
    private const val K_RHR = "resting_hr"
    private const val K_MAXHR = "max_hr_override" // 0 = 자동(Tanaka)
    private const val K_SET = "configured"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 저장된 프로필(없으면 기본값). */
    fun load(ctx: Context): Profile {
        val p = prefs(ctx)
        val age = p.getInt(K_AGE, 35)
        val rhr = p.getInt(K_RHR, 58)
        val override = p.getInt(K_MAXHR, 0)
        return if (override > 0) Profile(age, rhr, override) else Profile.default(age, rhr)
    }

    /** maxHrOverride=0 이면 나이 기반(Tanaka) 자동. */
    fun save(ctx: Context, age: Int, restingHr: Int, maxHrOverride: Int) {
        prefs(ctx).edit()
            .putInt(K_AGE, age)
            .putInt(K_RHR, restingHr)
            .putInt(K_MAXHR, maxHrOverride)
            .putBoolean(K_SET, true)
            .apply()
    }

    /** 사용자가 한 번이라도 프로필을 설정했는지(온보딩 판단). */
    fun isConfigured(ctx: Context): Boolean = prefs(ctx).getBoolean(K_SET, false)

    /** 저장된 maxHr 오버라이드 값(0=자동). 프로필 화면 표시용. */
    fun maxHrOverride(ctx: Context): Int = prefs(ctx).getInt(K_MAXHR, 0)
}
