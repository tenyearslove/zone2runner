package com.zone2runner.voicepoc

import android.content.Context
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions

/**
 * 온디바이스 호흡 감지(YAMNet, AudioSet 521클래스). 낭독/호흡 오디오를 분류해
 * 숨소리(Breathing/Gasp/Pant 등)와 말소리(Speech) 확률을 뽑는다. 진짜 학습된 NN(MobileNet 기반).
 * 내용(무슨 단어)이 아니라 "숨참"을 직접 본다.
 */
class BreathClassifier(context: Context) {

    data class Scores(
        val breathing: Float, val gasp: Float, val pant: Float, val snort: Float,
        val speech: Float,
        val breathSum: Float,          // 숨 관련 합(강도 지표)
        val top: List<Pair<String, Float>>, // 상위 라벨(디버그/보정용)
    )

    private val classifier: AudioClassifier = AudioClassifier.createFromOptions(
        context,
        AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("yamnet.tflite").build())
            .setRunningMode(RunningMode.AUDIO_CLIPS)
            .setMaxResults(521)
            .build()
    )

    /** 16kHz mono PCM16 → 창별 분류를 평균해 관심 라벨 점수 산출. */
    fun classify(samples: ShortArray): Scores {
        val floats = FloatArray(samples.size) { samples[it] / 32768f }
        val format = AudioData.AudioDataFormat.builder()
            .setNumOfChannels(1).setSampleRate(16_000f).build()
        val audio = AudioData.create(format, floats.size)
        audio.load(floats)

        val result = classifier.classify(audio)
        // 창(≈0.975s)별 카테고리 점수를 라벨별 평균으로 집계
        val sums = HashMap<String, Float>(); var windows = 0
        for (cr in result.classificationResults()) {
            val cats = cr.classifications().firstOrNull()?.categories() ?: continue
            windows++
            for (c in cats) sums[c.categoryName()] = (sums[c.categoryName()] ?: 0f) + c.score()
        }
        val n = windows.coerceAtLeast(1)
        fun avg(label: String) = (sums[label] ?: 0f) / n

        val breathing = avg("Breathing"); val gasp = avg("Gasp")
        val pant = avg("Pant"); val snort = avg("Snort")
        val speech = avg("Speech") + avg("Narration, monologue")
        val top = sums.entries.sortedByDescending { it.value }.take(6).map { it.key to it.value / n }
        return Scores(breathing, gasp, pant, snort, speech, breathing + gasp + pant + snort, top)
    }

    fun close() = classifier.close()
}
