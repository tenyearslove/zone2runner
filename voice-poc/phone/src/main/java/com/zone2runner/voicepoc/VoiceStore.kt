package com.zone2runner.voicepoc

import android.content.Context

/**
 * 폰 측 공유 상태: 기준선(저강도 낭독 지표) + 마지막 워치 판정. 기준선은 prefs에 영속.
 * PhoneActivity와 VoiceChannelService(워치 오디오 수신)가 공유한다.
 */
object VoiceStore {
    @Volatile var baseline: VoiceMetrics? = null
        private set

    /** 워치에서 온 마지막 판정(있으면 폰 화면에도 표시). */
    @Volatile var lastWatchVerdict: TalkVerdict? = null
    @Volatile var onChange: (() -> Unit)? = null

    fun setBaseline(ctx: Context, m: VoiceMetrics) {
        baseline = m
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt("span", m.speechSpanMs).putInt("voiced", m.voicedMs)
            .putInt("pause", m.pauseCount).putInt("total", m.totalMs).apply()
    }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!p.contains("span")) return
        val span = p.getInt("span", 0); val voiced = p.getInt("voiced", 0)
        if (span <= 0) return
        baseline = VoiceMetrics(p.getInt("total", span), span, voiced, p.getInt("pause", 0), voiced.toDouble() / span)
    }

    private const val PREF = "voice_poc"
}
