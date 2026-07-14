package com.zone2runner.app.data

import android.content.Context
import com.zone2runner.app.domain.Profile
import com.zone2runner.app.domain.Zone2Prior

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
    // spec-013 확장 factor (기존 저장값엔 없음 → 기본값 로드 = 하위 호환)
    private const val K_HEIGHT = "height_cm"
    private const val K_WEIGHT = "weight_kg"
    private const val K_BODY = "body_type"
    private const val K_FITNESS = "fitness_level"
    private const val K_FREQ = "weekly_freq"
    private const val K_JOINT = "joint_caution" // spec-026 무릎/관절 주의(하위호환: 없으면 false)

    // 활성 프로필별 네임스페이스(spec-020). 기본 프로필은 접미사 없어 기존 데이터 그대로.
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Profiles.prefName(ctx, PREF), Context.MODE_PRIVATE)

    /** 프로필 삭제 시 그 신체정보 제거. */
    fun clear(ctx: Context, profileId: String) {
        ctx.getSharedPreferences(Profiles.prefNameFor(PREF, profileId), Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** 저장된 프로필(없으면 기본값). RHR=0(모름)이면 factor 기반 추정으로 해소(spec-013) — 하류는 항상 유효 RHR을 받는다. */
    fun load(ctx: Context): Profile {
        val p = prefs(ctx)
        val age = p.getInt(K_AGE, 35)
        val storedRhr = p.getInt(K_RHR, 58)
        val override = p.getInt(K_MAXHR, 0)
        val maxHr = if (override > 0) override else Profile.tanakaMaxHr(age).toInt()
        val fitness = p.getInt(K_FITNESS, 3)
        val freq = p.getInt(K_FREQ, 3)
        val rhrUnknown = storedRhr <= 0
        return Profile(
            age,
            if (rhrUnknown) Zone2Prior.estimateRhr(fitness, freq) else storedRhr,
            maxHr,
            heightCm = p.getInt(K_HEIGHT, 170),
            weightKg = p.getInt(K_WEIGHT, 70),
            bodyType = p.getInt(K_BODY, 3),
            fitnessLevel = fitness,
            weeklyFreq = freq,
            rhrEstimated = rhrUnknown,
            jointCaution = p.getBoolean(K_JOINT, false),
        )
    }

    /** maxHrOverride=0 이면 나이 기반(Tanaka) 자동. */
    fun save(
        ctx: Context, age: Int, restingHr: Int, maxHrOverride: Int,
        heightCm: Int = 170, weightKg: Int = 70,
        bodyType: Int = 3, fitnessLevel: Int = 3, weeklyFreq: Int = 3,
        jointCaution: Boolean = false,
    ) {
        prefs(ctx).edit()
            .putInt(K_AGE, age)
            .putInt(K_RHR, restingHr)
            .putInt(K_MAXHR, maxHrOverride)
            .putInt(K_HEIGHT, heightCm)
            .putInt(K_WEIGHT, weightKg)
            .putInt(K_BODY, bodyType.coerceIn(1, 5))
            .putInt(K_FITNESS, fitnessLevel.coerceIn(1, 5))
            .putInt(K_FREQ, weeklyFreq.coerceIn(1, 5))
            .putBoolean(K_JOINT, jointCaution)
            .putBoolean(K_SET, true)
            .apply()
    }

    /** 사용자가 한 번이라도 프로필을 설정했는지(온보딩 판단). */
    fun isConfigured(ctx: Context): Boolean = prefs(ctx).getBoolean(K_SET, false)

    /** 저장된 maxHr 오버라이드 값(0=자동). 프로필 화면 표시용. */
    fun maxHrOverride(ctx: Context): Int = prefs(ctx).getInt(K_MAXHR, 0)
}
