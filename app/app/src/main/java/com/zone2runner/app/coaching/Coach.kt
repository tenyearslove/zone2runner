package com.zone2runner.app.coaching

import com.zone2runner.app.domain.ZoneJudgment

/**
 * 코칭 의도(방향)는 규칙이 결정하고, 표현은 교체 가능(adr-002).
 * - RuleCoach: 정적 템플릿(LLM 없이 항상 동작, 콜드스타트/폴백).
 * - LlmCoach(후속): 규칙 의도 + Gemini Nano 표현 + 출력 가드, 실패 시 RuleCoach로 폴백.
 */
data class CoachContext(
    val judgment: ZoneJudgment,
    val slopePct: Double,
    val paceMinKm: Double,
    val elapsedSec: Int,
)

/** 규칙이 정하는 코칭 방향(의도). LLM은 이 의도를 표현만 바꾼다. */
enum class CoachIntent { SPEED_UP, MAINTAIN, SLOW_DOWN }

fun intentOf(j: ZoneJudgment): CoachIntent = when (j) {
    ZoneJudgment.BELOW -> CoachIntent.SPEED_UP
    ZoneJudgment.ABOVE -> CoachIntent.SLOW_DOWN
    ZoneJudgment.IN -> CoachIntent.MAINTAIN
}

interface Coach {
    /** 코칭 문장 생성. suspend(LLM 대비). */
    suspend fun say(ctx: CoachContext): String
    val name: String
}

/** 정적 규칙 코치. 의도별 문구를 상황(경사)에 맞게 고른다. */
class RuleCoach : Coach {
    override val name = "rule"

    private var counter = 0

    override suspend fun say(ctx: CoachContext): String {
        val uphill = ctx.slopePct > 2
        val downhill = ctx.slopePct < -2
        val lines = when (intentOf(ctx.judgment)) {
            CoachIntent.SPEED_UP -> when {
                downhill -> listOf("내리막이에요. 조금 더 밀어서 심박을 Zone 2로 올려볼까요.")
                else -> listOf(
                    "여유가 있어요. 페이스를 살짝 올려 Zone 2로 들어가요.",
                    "심박이 낮아요. 조금만 더 속도를 내볼게요.",
                )
            }
            CoachIntent.SLOW_DOWN -> when {
                uphill -> listOf("오르막이라 심박이 올랐어요. 보폭을 줄여 천천히 올라가요.")
                else -> listOf(
                    "심박이 Zone 2를 넘었어요. 페이스를 조금 늦춰요.",
                    "약간 빨라요. 호흡을 고르며 속도를 낮춰볼게요.",
                )
            }
            CoachIntent.MAINTAIN -> listOf(
                "좋아요, Zone 2 유지 중이에요. 이 리듬 그대로.",
                "완벽해요. 지금 페이스를 계속 지켜주세요.",
            )
        }
        return guard(lines[counter++ % lines.size])
    }

    /** 출력 가드: 길이 제한/공백 정리(adr-002 출력 가드의 최소판). */
    private fun guard(s: String): String {
        val t = s.trim().replace(Regex("\\s+"), " ")
        return if (t.length > 80) t.take(79) + "…" else t
    }
}
