package com.zone2runner.voicepoc

import kotlin.math.sqrt

/**
 * 순수 호흡음에서 호흡률(분당 호흡수)과 강도를 추정(신호처리, 러닝 RR 논문 방식의 경량판).
 * 30ms RMS 포락선 → 평활 → peak(날숨 이벤트) 카운트. 말소리가 없다는 전제.
 */
data class BreathSignal(val breathsPerMin: Double, val energyDb: Double, val peaks: Int, val seconds: Double)

object BreathEnvelope {
    private const val FRAME_MS = 30
    private const val MIN_BREATH_GAP_MS = 900 // 최대 ~66회/분

    fun analyze(samples: ShortArray, sampleRate: Int): BreathSignal {
        val frame = sampleRate * FRAME_MS / 1000
        if (frame <= 0 || samples.size < frame * 4) return BreathSignal(0.0, -120.0, 0, 0.0)
        val n = samples.size / frame
        val env = DoubleArray(n)
        for (f in 0 until n) {
            var s = 0.0; val b = f * frame
            for (i in 0 until frame) { val v = samples[b + i].toDouble(); s += v * v }
            env[f] = sqrt(s / frame)
        }
        // 평활(이동평균 3)
        val sm = DoubleArray(n)
        for (i in 0 until n) {
            var acc = 0.0; var c = 0
            for (j in -1..1) { val k = i + j; if (k in 0 until n) { acc += env[k]; c++ } }
            sm[i] = acc / c
        }
        val mean = sm.average()
        val peakVal = sm.maxOrNull() ?: 0.0
        val thr = mean + (peakVal - mean) * 0.35 // 평균과 피크 사이
        val minGap = MIN_BREATH_GAP_MS / FRAME_MS

        var peaks = 0; var last = -minGap
        for (i in 1 until n - 1) {
            if (sm[i] > thr && sm[i] >= sm[i - 1] && sm[i] > sm[i + 1] && i - last >= minGap) {
                peaks++; last = i
            }
        }
        val seconds = samples.size.toDouble() / sampleRate
        val bpm = if (seconds > 0) peaks / seconds * 60.0 else 0.0
        // 전체 RMS를 dB로(강도, 라우드니스 프록시)
        var sum = 0.0
        for (v in samples) sum += v.toDouble() * v
        val rms = sqrt(sum / samples.size)
        val db = if (rms > 1) 20 * Math.log10(rms / 32768.0) else -120.0
        return BreathSignal(bpm, db, peaks, seconds)
    }
}
