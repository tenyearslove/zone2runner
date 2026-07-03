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

/**
 * adr-002 출력 가드 - 방향 잠금 검사. LLM 문장이 규칙이 정한 방향과 모순(반대 지시)이거나
 * 방향을 전달하지 않으면 기각 → 호출부(LlmCoach)는 규칙 폴백.
 * 필드 실측(2026-07-03): "초과" 상황에서 "힘내세요!" 같은 무방향/역방향 문장이 가드를 통과했음.
 *
 * 어휘 2단계: 좁은 명령어(upWords/downWords)는 모순 판정용 — 오탐을 피하려고 명령형만
 * ("올라가요"의 "올라", "내리막"의 "내리"는 안 걸리게 "올려/올리", "늦춰/낮춰"만).
 * 넓은 단서(upCues/downCues)는 방향 전달 확인용.
 */
object DirectionGuard {
    private val upWords = listOf("올려", "올리", "높여", "높이", "빠르게", "밀어", "박차", "스퍼트")
    private val downWords = listOf("늦춰", "늦추", "낮춰", "낮추", "줄여", "줄이", "천천히", "느리게")
    // "속도를 내"는 require용 단서로만(모순 판정 upWords엔 없음 — "속도를 내려"와의 충돌 방지)
    private val upCues = upWords + listOf("끌어올", "페이스를 올", "속도를 올", "속도를 내")
    private val downCues = downWords + listOf("내려", "내리", "호흡", "고르", "여유", "진정", "편안", "무리하지", "가라앉")

    fun ok(intent: CoachIntent, text: String): Boolean = when (intent) {
        CoachIntent.SPEED_UP -> downWords.none(text::contains) && upCues.any(text::contains)
        CoachIntent.SLOW_DOWN -> upWords.none(text::contains) && downCues.any(text::contains)
        CoachIntent.MAINTAIN -> upWords.none(text::contains) && downWords.none(text::contains)
    }
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
