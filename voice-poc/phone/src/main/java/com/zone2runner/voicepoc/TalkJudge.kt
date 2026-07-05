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
 * 호흡(YAMNet) 기반 판정. 숨소리(Breathing/Gasp/Pant)와 말소리(Speech)의 상대 비율로 숨참을 본다.
 * 편하게 말하면 Speech가 지배, 숨참이면 숨소리가 올라가고 말이 무너진다. 스케일-강건한 비율 사용.
 * GAIN은 실기기 점수 분포로 보정(초기값). breathSum/(breathSum+speech).
 */
object BreathJudge {
    private const val GAIN = 1.3

    fun judge(s: BreathClassifier.Scores): TalkVerdict {
        val denom = s.breathSum + s.speech + 1e-4f
        val ratio = (s.breathSum / denom).toDouble() // 0~1, 숨참일수록 높음
        val diff = (ratio * GAIN).coerceIn(0.0, 1.0)
        val level = when {
            diff < 0.15 -> TalkLevel.VERY_COMFORTABLE
            diff < 0.35 -> TalkLevel.COMFORTABLE
            diff < 0.60 -> TalkLevel.BORDERLINE
            diff < 0.80 -> TalkLevel.HARD
            else -> TalkLevel.VERY_HARD
        }
        val detail = "숨 %.2f (호흡%.2f/헐떡%.2f/가쁨%.2f), 말 %.2f".format(
            s.breathSum, s.breathing, s.pant, s.gasp, s.speech
        )
        return TalkVerdict(level, diff, detail)
    }
}

/**
 * ASR 기반 판정(폰). 완성도(어디까지 읽었나)가 주 신호 — 문장을 못 끝내면 곧 역치 위.
 * 끊김(호흡 삽입)이 보조. 완성도는 절대 지표라 기준선 불필요.
 */
object AsrTalkJudge {
    fun judge(completeness: Double, pauseCount: Int): TalkVerdict {
        val incomplete = (1.0 - completeness).coerceIn(0.0, 1.0) // 못 읽은 비율
        val cPause = (pauseCount / 6.0).coerceIn(0.0, 1.0)       // +6 끊김이면 최대
        val diff = (0.65 * incomplete + 0.35 * cPause).coerceIn(0.0, 1.0)
        val level = when {
            diff < 0.15 -> TalkLevel.VERY_COMFORTABLE
            diff < 0.35 -> TalkLevel.COMFORTABLE
            diff < 0.60 -> TalkLevel.BORDERLINE
            diff < 0.80 -> TalkLevel.HARD
            else -> TalkLevel.VERY_HARD
        }
        return TalkVerdict(level, diff, "완성도 %.0f%%, 끊김 %d회".format(completeness * 100, pauseCount))
    }
}

/**
 * 발화 지표 → 곤란도(0~1) → 5단계. 기준선(저강도 낭독)이 있으면 그 대비 상대 판정(재현성 높음),
 * 없으면 절대 휴리스틱. 세 신호를 가중합: 완독 지연 / 호흡 끊김 증가 / 발화비율 하락.
 */
object TalkJudge {
    fun judge(test: VoiceMetrics, baseline: VoiceMetrics?): TalkVerdict {
        if (test.speechSpanMs == 0) return TalkVerdict(TalkLevel.BORDERLINE, 0.5, "음성 미검출 (재시도)")

        val diff: Double
        val detail: String
        if (baseline != null && baseline.voicedMs > 0) {
            // 두 독립 신호(고정창에선 완독시간이 무의미해 제외):
            //  1) 발화량(voicedMs) 감소 = 문장을 덜 읽음/헉헉거림(가쁜 숨은 발화가 아님)
            //  2) 호흡 끊김(pause) 증가 = 말 사이 숨을 삽입
            val contentRatio = test.voicedMs.toDouble() / baseline.voicedMs      // <1 덜 말함
            val pauseDelta = (test.pauseCount - baseline.pauseCount).toDouble()   // 증가 = 호흡 삽입
            val cContent = ((1.0 - contentRatio) / 0.45).coerceIn(0.0, 1.0)       // 45% 감소면 최대
            val cPause = (pauseDelta / 5.0).coerceIn(0.0, 1.0)                    // +5 끊김이면 최대
            diff = (0.55 * cContent + 0.45 * cPause).coerceIn(0.0, 1.0)
            detail = "발화량 기준比 %.0f%%, 끊김 %+d회".format(contentRatio * 100, pauseDelta.toInt())
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
