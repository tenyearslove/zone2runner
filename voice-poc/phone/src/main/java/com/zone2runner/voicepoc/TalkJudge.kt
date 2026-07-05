package com.zone2runner.voicepoc

/** 객관 토크테스트 5단계(강도 오름차순). 본 앱 spec-016 TalkState와 동일 축. */
enum class TalkLevel(val label: String) {
    VERY_COMFORTABLE("아주 편함"),
    COMFORTABLE("편함"),
    BORDERLINE("애매"),
    HARD("벅참"),
    VERY_HARD("매우 벅참"),
}

data class TalkVerdict(val level: TalkLevel, val difficulty: Double, val detail: String)

/**
 * 발화 지표 → 곤란도(0~1) → 5단계. 기준선(저강도 낭독)이 있으면 그 대비 상대 판정(재현성 높음),
 * 없으면 절대 휴리스틱. 세 신호를 가중합: 완독 지연 / 호흡 끊김 증가 / 발화비율 하락.
 */
object TalkJudge {
    fun judge(test: VoiceMetrics, baseline: VoiceMetrics?): TalkVerdict {
        if (test.speechSpanMs == 0) return TalkVerdict(TalkLevel.BORDERLINE, 0.5, "음성 미검출 (재시도)")

        val diff: Double
        val detail: String
        if (baseline != null && baseline.speechSpanMs > 0) {
            val spanRatio = test.speechSpanMs.toDouble() / baseline.speechSpanMs // >1 느려짐
            val pauseDelta = (test.pauseCount - baseline.pauseCount).toDouble()   // 증가 = 호흡 삽입
            val ratioDrop = baseline.voicedRatio - test.voicedRatio               // 발화비율 하락

            val cSpan = ((spanRatio - 1.0) / 0.6).coerceIn(0.0, 1.0)  // 60% 느려지면 최대
            val cPause = (pauseDelta / 4.0).coerceIn(0.0, 1.0)        // +4 끊김이면 최대
            val cRatio = (ratioDrop / 0.35).coerceIn(0.0, 1.0)        // 35%p 하락이면 최대
            diff = (0.40 * cSpan + 0.35 * cPause + 0.25 * cRatio).coerceIn(0.0, 1.0)
            detail = "완독 x%.2f, 끊김 %+d회, 발화율 %.0f%%→%.0f%%".format(
                spanRatio, pauseDelta.toInt(), baseline.voicedRatio * 100, test.voicedRatio * 100
            )
        } else {
            val cPause = (test.pauseCount / 5.0).coerceIn(0.0, 1.0)
            val cRatio = ((0.85 - test.voicedRatio) / 0.45).coerceIn(0.0, 1.0)
            diff = (0.5 * cPause + 0.5 * cRatio).coerceIn(0.0, 1.0)
            detail = "기준선 없음, 끊김 ${test.pauseCount}회, 발화율 %.0f%%".format(test.voicedRatio * 100)
        }

        val level = when {
            diff < 0.15 -> TalkLevel.VERY_COMFORTABLE
            diff < 0.35 -> TalkLevel.COMFORTABLE
            diff < 0.60 -> TalkLevel.BORDERLINE
            diff < 0.80 -> TalkLevel.HARD
            else -> TalkLevel.VERY_HARD
        }
        return TalkVerdict(level, diff, detail)
    }
}
