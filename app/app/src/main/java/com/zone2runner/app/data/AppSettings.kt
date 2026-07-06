package com.zone2runner.app.data

import android.content.Context
import com.zone2runner.app.pipeline.CoachCadence

/**
 * 앱 전역 설정(spec-021) — 프로필(신체/개인화)과 분리된 UX 환경설정. SharedPreferences 영속.
 * 러닝 시작 시 로드해 엔진/TTS/화면에 반영(러닝 중 변경은 다음 세션부터).
 */
data class AppSettings(
    val coachFrequency: Int = 2,       // 0..4 (최소~매우 자주), 기본 보통
    val voiceEnabled: Boolean = true,  // 음성(TTS) 코칭
    val voiceRate: Int = 1,            // 0 느리게 / 1 보통 / 2 빠르게
    val preemptiveEnabled: Boolean = true,   // 예측 기반 선제 코칭(spec-014 FR4)
    val heatCoachingEnabled: Boolean = true, // 더위(기온) 코칭 맥락
    val keepScreenOn: Boolean = true,        // 러닝 중 화면 항상 켜기
) {
    /** 코칭 빈도 → 엔진 케이던스(초). */
    val cadence: CoachCadence get() = CoachCadence.forLevel(coachFrequency)

    /** 음성 속도 → TTS speechRate 배율. */
    val ttsRate: Float get() = when (voiceRate) { 0 -> 0.85f; 2 -> 1.25f; else -> 1.0f }

    companion object {
        val FREQ_LABELS = listOf("최소", "적게", "보통", "자주", "매우 자주")
        val RATE_LABELS = listOf("느리게", "보통", "빠르게")
    }
}

/** 설정 영속화. 단일 전역 pref(프로필 네임스페이스 없음). */
object SettingsStore {
    private const val PREF = "zone2_settings"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): AppSettings {
        val p = prefs(ctx); val d = AppSettings()
        return AppSettings(
            coachFrequency = p.getInt("coachFreq", d.coachFrequency),
            voiceEnabled = p.getBoolean("voice", d.voiceEnabled),
            voiceRate = p.getInt("voiceRate", d.voiceRate),
            preemptiveEnabled = p.getBoolean("preemptive", d.preemptiveEnabled),
            heatCoachingEnabled = p.getBoolean("heat", d.heatCoachingEnabled),
            keepScreenOn = p.getBoolean("keepScreen", d.keepScreenOn),
        )
    }

    fun save(ctx: Context, s: AppSettings) {
        prefs(ctx).edit()
            .putInt("coachFreq", s.coachFrequency)
            .putBoolean("voice", s.voiceEnabled)
            .putInt("voiceRate", s.voiceRate)
            .putBoolean("preemptive", s.preemptiveEnabled)
            .putBoolean("heat", s.heatCoachingEnabled)
            .putBoolean("keepScreen", s.keepScreenOn)
            .apply()
    }
}
