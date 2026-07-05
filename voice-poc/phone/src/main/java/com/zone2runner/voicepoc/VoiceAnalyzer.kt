package com.zone2runner.voicepoc

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 발화 신호 지표(객관 토크테스트의 원천). 강도가 오르면:
 *   speechSpanMs 증가(완독이 느려짐) / pauseCount 증가(호흡이 발화를 자름) / voicedRatio 하락.
 */
data class VoiceMetrics(
    val totalMs: Int,        // 녹음 전체 길이
    val speechSpanMs: Int,   // 첫 발화~마지막 발화 (완독 시간 프록시)
    val voicedMs: Int,       // 유성 구간 합
    val pauseCount: Int,     // span 내 유의미한 침묵(호흡) 구간 수
    val voicedRatio: Double, // voicedMs / speechSpanMs (0~1)
)

/**
 * 순수 Kotlin 에너지 기반 VAD(음성 활동 검출). NN/클라우드/ASR 불필요, 완전 오프라인.
 * 20ms 프레임 RMS로 유성/침묵을 나누고, 완독시간/호흡끊김/발화비율을 뽑는다.
 */
object VoiceAnalyzer {
    private const val FRAME_MS = 20
    private const val MIN_PAUSE_MS = 150 // 이보다 긴 침묵만 "호흡 끊김"으로 카운트(자연 음절 간격 제외)

    fun analyze(samples: ShortArray, sampleRate: Int): VoiceMetrics {
        val frameLen = sampleRate * FRAME_MS / 1000
        if (frameLen <= 0 || samples.size < frameLen) return VoiceMetrics(0, 0, 0, 0, 0.0)
        val nFrames = samples.size / frameLen

        val rms = DoubleArray(nFrames)
        for (f in 0 until nFrames) {
            var sum = 0.0
            val base = f * frameLen
            for (i in 0 until frameLen) { val s = samples[base + i].toDouble(); sum += s * s }
            rms[f] = sqrt(sum / frameLen)
        }

        // 적응 임계: 피크(95% 분위) 대비 상대값을 기본으로, 노이즈 바닥(20% 분위)이 높으면 반영하되
        // 피크의 절반을 넘지 않게 캡(발화가 연속이라 바닥이 높아도 검출 실패하지 않도록). 절대 하한으로 무음 방어.
        val sorted = rms.sortedArray()
        val noiseFloor = sorted[(nFrames * 0.2).toInt().coerceIn(0, nFrames - 1)]
        val peak = sorted[(nFrames * 0.95).toInt().coerceIn(0, nFrames - 1)]
        val thr = max(peak * 0.20, min(noiseFloor * 1.8, peak * 0.5)).coerceAtLeast(120.0)

        val voiced = BooleanArray(nFrames) { rms[it] > thr }
        val first = voiced.indexOfFirst { it }
        if (first < 0) return VoiceMetrics(nFrames * FRAME_MS, 0, 0, 0, 0.0)
        val last = voiced.indexOfLast { it }

        val spanFrames = last - first + 1
        val voicedFrames = (first..last).count { voiced[it] }

        // span 내 침묵 런 중 MIN_PAUSE_MS 이상을 호흡 끊김으로 카운트
        var pauses = 0; var run = 0
        val minPauseFrames = MIN_PAUSE_MS / FRAME_MS
        for (f in first..last) {
            if (!voiced[f]) run++ else { if (run >= minPauseFrames) pauses++; run = 0 }
        }

        val spanMs = spanFrames * FRAME_MS
        val voicedMs = voicedFrames * FRAME_MS
        return VoiceMetrics(nFrames * FRAME_MS, spanMs, voicedMs, pauses, voicedMs.toDouble() / spanMs)
    }
}
