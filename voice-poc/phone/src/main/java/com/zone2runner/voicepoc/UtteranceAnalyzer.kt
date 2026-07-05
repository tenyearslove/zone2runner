package com.zone2runner.voicepoc

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 시간제한 카운팅 Talk Test의 발화 처리량(throughput) 측정 (spec-017).
 * 핵심: 숨을 검출하지 않는다. 고정 시간 동안 "얼마나 말했나"를 재면, 숨쉬는 시간이 말하는 시간을
 * 자동으로 깎으므로 벅찰수록 처리량이 준다. VT1 근처에서 처리량이 급감.
 *   - speakingRatio: 창 대비 실제 소리낸 비율(편함↑)
 *   - voicedSegments: 발화 덩어리 수 ≈ 센 개수(편함↑)
 * (참고 지표) breaths/meanUtteranceSec: 발화 사이 긴 gap 기반. 부차적.
 */
data class UtteranceMetrics(
    val voicedSegments: Int,      // 발화 덩어리 수 ≈ 말한 토큰(숫자) 수
    val speakingRatio: Double,    // 소리낸 시간 / 창 전체 (주 지표)
    val breaths: Int,             // 긴 gap(≥350ms) 수 (부차)
    val breathsPerMin: Double,
    val meanUtteranceSec: Double, // 덩어리 사이 평균 발화 길이
    val windowSec: Double,
) {
    companion object { val EMPTY = UtteranceMetrics(0, 0.0, 0, 0.0, 0.0, 0.0) }
}

object UtteranceAnalyzer {
    private const val FRAME_MS = 20
    private const val MIN_GAP_MS = 120   // 발화 덩어리 분리(어절 간격)
    private const val MIN_BREATH_MS = 350 // 긴 gap = 들숨(부차 지표)

    fun analyze(samples: ShortArray, sampleRate: Int, windowMs: Int): UtteranceMetrics {
        val frame = sampleRate * FRAME_MS / 1000
        if (frame <= 0 || samples.size < frame * 8) return UtteranceMetrics.EMPTY
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

        val windowSec = (windowMs.takeIf { it > 0 } ?: (n * FRAME_MS)) / 1000.0
        val voicedFrames = voiced.count { it }
        val speakingRatio = voicedFrames.toDouble() / n

        // 발화 덩어리 카운트: 짧은 gap(<MIN_GAP)은 같은 덩어리로 병합
        val minGapFrames = MIN_GAP_MS / FRAME_MS
        var segments = 0; var gap = 0; var inSeg = false
        for (i in 0 until n) {
            if (voiced[i]) { if (!inSeg) { segments++; inSeg = true }; gap = 0 }
            else { gap++; if (gap >= minGapFrames) inSeg = false }
        }

        // 부차: 긴 gap(들숨)과 평균 발화 길이(발화 구간 내)
        val first = voiced.indexOfFirst { it }; val last = voiced.indexOfLast { it }
        var breaths = 0; var run = 0
        val minBreathFrames = MIN_BREATH_MS / FRAME_MS
        if (first >= 0) for (i in first..last) { if (voiced[i]) { if (run >= minBreathFrames) breaths++; run = 0 } else run++ }
        val spanSec = if (first >= 0) (last - first + 1) * FRAME_MS / 1000.0 else 0.0
        val meanUtter = if (first >= 0) spanSec / (breaths + 1) else 0.0
        val bpm = if (spanSec > 0) breaths / spanSec * 60.0 else 0.0

        return UtteranceMetrics(segments, speakingRatio, breaths, bpm, meanUtter, windowSec)
    }
}
