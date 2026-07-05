package com.zone2runner.voicepoc

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 페이스 강제 낭독의 "발화 길이(utterance length)" 측정 (spec-017).
 * 환기 여유가 줄면 더 자주 들이쉬어 한 숨에 말하는 길이가 짧아진다 → VT1 근접 신호.
 * 에너지 VAD로 발화/비발화를 나누고, 일정 길이 이상 gap을 호흡 끊김으로 센다.
 */
data class UtteranceMetrics(
    val breaths: Int,             // 발화 중 들숨(호흡 끊김) 횟수
    val breathsPerMin: Double,    // 분당 호흡수
    val meanUtteranceSec: Double, // 평균 발화 길이(숨 사이 시간)
    val tokensPerBreath: Double,  // 숨당 토큰(≈음절) 수 = 발화길이 / 박자
    val speakingRatio: Double,    // 발화 구간 중 실제 소리낸 비율
    val spanSec: Double,          // 첫 발화~마지막 발화
)

object UtteranceAnalyzer {
    private const val FRAME_MS = 20
    private const val MIN_BREATH_MS = 250 // 이보다 긴 비발화 gap = 들숨(자연 어절 간격 제외)

    fun analyze(samples: ShortArray, sampleRate: Int, beatMs: Int): UtteranceMetrics {
        val frame = sampleRate * FRAME_MS / 1000
        if (frame <= 0 || samples.size < frame * 8) return UtteranceMetrics(0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val n = samples.size / frame
        val rms = DoubleArray(n)
        for (f in 0 until n) {
            var s = 0.0; val b = f * frame
            for (i in 0 until frame) { val v = samples[b + i].toDouble(); s += v * v }
            rms[f] = sqrt(s / frame)
        }
        val sorted = rms.sortedArray()
        val noiseFloor = sorted[(n * 0.2).toInt().coerceIn(0, n - 1)]
        val peak = sorted[(n * 0.95).toInt().coerceIn(0, n - 1)]
        val thr = max(peak * 0.20, min(noiseFloor * 1.8, peak * 0.5)).coerceAtLeast(120.0)
        val voiced = BooleanArray(n) { rms[it] > thr }

        val first = voiced.indexOfFirst { it }
        if (first < 0) return UtteranceMetrics(0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val last = voiced.indexOfLast { it }
        val spanFrames = last - first + 1

        val minBreathFrames = MIN_BREATH_MS / FRAME_MS
        var breaths = 0; var run = 0; var voicedCount = 0
        for (i in first..last) {
            if (voiced[i]) { if (run >= minBreathFrames) breaths++; run = 0; voicedCount++ }
            else run++
        }

        val spanSec = spanFrames * FRAME_MS / 1000.0
        val meanUtter = spanSec / (breaths + 1)
        val tokensPerBreath = if (beatMs > 0) meanUtter / (beatMs / 1000.0) else 0.0
        val bpm = if (spanSec > 0) breaths / spanSec * 60.0 else 0.0
        val speakingRatio = voicedCount.toDouble() / spanFrames
        return UtteranceMetrics(breaths, bpm, meanUtter, tokensPerBreath, speakingRatio, spanSec)
    }
}
