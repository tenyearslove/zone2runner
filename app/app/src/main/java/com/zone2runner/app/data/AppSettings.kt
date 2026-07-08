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
    val voicePitch: Int = 1,           // 0 낮게 / 1 보통 / 2 높게 (spec-024 FR2)
    val voiceName: String? = null,     // TTS 보이스 식별자. null=기기 기본 (spec-024 FR1)
    val coachPersona: Int = 0,         // 코칭 말투 인덱스(PERSONA_KEYS) (spec-024 FR3)
    val preemptiveEnabled: Boolean = true,   // 예측 기반 선제 코칭(spec-014 FR4)
    val heatCoachingEnabled: Boolean = true, // 더위(기온) 코칭 맥락
    val keepScreenOn: Boolean = true,        // 러닝 중 화면 항상 켜기
) {
    /** 코칭 빈도 → 엔진 케이던스(초). */
    val cadence: CoachCadence get() = CoachCadence.forLevel(coachFrequency)

    /** 음성 속도 → TTS speechRate 배율. */
    val ttsRate: Float get() = when (voiceRate) { 0 -> 0.85f; 2 -> 1.25f; else -> 1.0f }

    /** 음 높낮이 → TTS pitch 배율. */
    val ttsPitch: Float get() = when (voicePitch) { 0 -> 0.85f; 2 -> 1.15f; else -> 1.0f }

    /** 말투 프롬프트 키(coach_prompt.json persona 맵). */
    val personaKey: String get() = PERSONA_KEYS.getOrElse(coachPersona) { PERSONA_KEYS[0] }

    companion object {
        val FREQ_LABELS = listOf("최소", "적게", "보통", "자주", "매우 자주")
        val RATE_LABELS = listOf("느리게", "보통", "빠르게")
        val PITCH_LABELS = listOf("낮게", "보통", "높게")
        // 말투(페르소나): 방향/사실은 규칙이 확정(adr-002 불변), LLM 표현 문체만 바꾼다(spec-024 FR3)
        val PERSONA_KEYS = listOf("default", "spartan", "friend", "calm")
        val PERSONA_LABELS = listOf("친절한 코치", "스파르타 교관", "다정한 친구", "차분한 안내")
        val PERSONA_HINTS = listOf(
            "기본 말투예요.",
            "짧고 단호한 명령형으로 몰아붙여요.",
            "친구처럼 편한 반말로 응원해요.",
            "아나운서처럼 차분한 존댓말로 안내해요.",
        )
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
            voicePitch = p.getInt("voicePitch", d.voicePitch),
            voiceName = p.getString("voiceName", null),
            coachPersona = p.getInt("persona", d.coachPersona)
                .coerceIn(0, AppSettings.PERSONA_KEYS.size - 1),
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
            .putInt("voicePitch", s.voicePitch)
            .putString("voiceName", s.voiceName)
            .putInt("persona", s.coachPersona)
            .putBoolean("preemptive", s.preemptiveEnabled)
            .putBoolean("heat", s.heatCoachingEnabled)
            .putBoolean("keepScreen", s.keepScreenOn)
            .apply()
    }
}
