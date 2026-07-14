package com.zone2runner.app.coaching

import com.zone2runner.app.domain.RunReport

/**
 * 사후 세션 스토리 생성 (spec-023 FR2, 설명용이성 QA). "이번 러닝에서 왜 이렇게 코칭했는가"를
 * 세션 종료 시 1회 만들어 리포트에 저장한다(이후 조회는 저장 텍스트, LLM 재호출 없음).
 *
 * 무결성 원칙(PersonalizationExplainer와 동일): **사실은 규칙/리포트가 확정**, LLM은 표현만.
 * 코칭 로그의 사유 태그((초과)/(미달) 등, FR3)를 집계해 근거로 삼으므로 지어낸 수치가 없다.
 */
object SessionExplainer {

    /** 리포트가 만든 사실 요약(항상 정확). 폴백 스토리이자 LLM 프롬프트의 근거. */
    fun facts(r: RunReport): String {
        val min = r.durationSec / 60
        val hrr = (r.maxHrProfile - r.restingHr).coerceAtLeast(1)
        val startHi = r.restingHr + (r.uEstStartFrac * hrr).toInt()
        val endHi = r.restingHr + (r.uEstEndFrac * hrr).toInt()
        val shift = endHi - startHi
        val over = r.coachingLines.count { it.contains("(초과") }
        val under = r.coachingLines.count { it.contains("(미달") }
        val drift = r.cardiacDriftPct

        val sb = StringBuilder()
        sb.append("이번 러닝 ${min}분 중 Zone 2를 ${r.zone2Pct}% 유지했어요. ")
        when {
            over > 0 && under > 0 -> sb.append("강도가 높아 초과 알림 ${over}회, 낮아 미달 알림 ${under}회를 보냈습니다. ")
            over > 0 -> sb.append("강도가 상한을 넘으려 해 초과 알림을 ${over}회 보냈습니다. ")
            under > 0 -> sb.append("강도가 부족해 미달 알림을 ${under}회 보냈습니다. ")
            else -> sb.append("대체로 목표 범위 안이라 알림은 거의 없었습니다. ")
        }
        if (shift != 0) {
            val d = if (shift > 0) "+$shift" else "$shift"
            sb.append("관측을 반영해 개인 Zone 2 상단을 ${startHi}→${endHi}bpm(${d}bpm) 조정했어요. ")
        }
        if (drift >= 5.0) {
            sb.append("후반 심혈관 드리프트가 ${"%.0f".format(drift)}%로 올라, 같은 페이스에서도 심박이 높아져 후반 코칭이 더 자주 나갔습니다. ")
        }
        return sb.toString().trim()
    }

    /** 요약(Nano Summarization, adr-026) 입력용 확장 사실 텍스트 = facts + 분석 지표 라인. 실제 도출값만 담는다. */
    fun article(r: RunReport): String = buildString {
        append(facts(r))
        r.analysisLines.forEach { if (it.isNotBlank()) { append(" "); append(it) } }
    }

    /** LLM 프롬프트: 위 팩트를 주고 쉬운 말로 풀게 한다(사실 변경/추가 금지). */
    fun prompt(r: RunReport): String =
        "다음은 러닝 앱의 이번 세션 사실입니다:\n" +
            "- ${facts(r)}\n" +
            "이 사실만 근거로, 사용자에게 '이번 러닝에서 왜 이렇게 코칭했는지'를 2~3문장으로 쉽고 친근하게 설명하세요. " +
            "숫자를 지어내지 말고 위 사실만 쓰세요. 전문용어 없이. 따옴표와 이모지 없이."
}
