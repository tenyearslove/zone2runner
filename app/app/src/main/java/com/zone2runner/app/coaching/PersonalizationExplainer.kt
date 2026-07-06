package com.zone2runner.app.coaching

import com.zone2runner.app.domain.PersonalizationStatus

/**
 * 개인화 결과 설명 생성 (spec-020, 설명용이성 QA). "AI가 왜 이렇게 보정했는가"를 사용자에게 설명한다.
 *
 * 무결성 원칙(코칭 DirectionGuard와 동일): **사실(수치/방향)은 규칙이 확정**하고, LLM은 그 사실을
 * 자연스러운 말로 **표현만** 한다. LLM이 근거를 지어내지 못하게 팩트를 프롬프트에 명시하고,
 * LLM 미가용 시 규칙 문장으로 폴백한다. → 설명이 항상 실제 데이터에 근거함을 보장.
 */
object PersonalizationExplainer {

    /** 규칙이 만든 팩트 요약(항상 정확). 폴백 설명이자 LLM 프롬프트의 근거. */
    fun facts(s: PersonalizationStatus): String {
        if (!s.learned) return "아직 러닝 데이터가 없어 프로필 공식으로 계산한 초기값(${s.priorLo}~${s.priorHi}bpm)을 쓰고 있어요. 러닝하며 말하기 테스트에 답하면 내 몸에 맞게 조정됩니다."
        val dir = when {
            s.shiftHiBpm > 0 -> "위로 ${s.shiftHiBpm}bpm 올렸어요"
            s.shiftHiBpm < 0 -> "아래로 ${-s.shiftHiBpm}bpm 내렸어요"
            else -> "거의 그대로예요"
        }
        val why = when {
            s.shiftHiBpm > 0 -> "관측에서 이 강도가 아직 편하다고 나와, 유산소 상한을 조금 높게 잡았습니다."
            s.shiftHiBpm < 0 -> "관측에서 이 강도가 생각보다 벅차다고 나와, 유산소 상한을 조금 낮게 잡았습니다."
            else -> "관측이 공식 초기값과 잘 맞아 크게 바꾸지 않았습니다."
        }
        val conf = s.sigmaBpm?.let { "현재 신뢰도는 ±${it.toInt()}bpm(작을수록 확신)입니다." } ?: ""
        return "러닝 ${s.sessions}회, 말하기 테스트 ${s.talkObs}회를 근거로 Zone 2 상단을 초기 ${s.priorHi}에서 현재 ${s.curHi}bpm으로 $dir. $why $conf".trim()
    }

    /** LLM 프롬프트: 위 팩트를 주고 쉬운 말로 풀게 한다(사실 변경/추가 금지). */
    fun prompt(s: PersonalizationStatus): String =
        "다음은 러닝 앱이 사용자의 개인 Zone 2(유산소 강도) 범위를 학습한 결과 사실입니다:\n" +
            "- ${facts(s)}\n" +
            "이 사실만 근거로, 사용자에게 왜 이렇게 조정됐는지 2~3문장으로 쉽고 친근하게 설명하세요. " +
            "숫자를 지어내지 말고 위 사실만 쓰세요. 전문용어(uFrac, 베이지안 등) 없이. 따옴표와 이모지 없이."
}
