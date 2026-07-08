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
    val spm: Int = 0, // 케이던스(0=미상). 범위 밖이면 코칭에 폼 가이드 추가
    /** 선제 코칭(spec-014 FR4): 아직 존 안이지만 동역학 모델이 곧 이탈을 예측 — judgment는 예측된 이탈 방향. */
    val preemptive: Boolean = false,
    // LLM 표현 풍부화용 실측 수치(방향은 규칙이 결정 — 이 값들은 표현 재료일 뿐, adr-002). 0/-1=미상.
    val currentHr: Int = 0,   // 지속 심박(판정 기준 bpm)
    val loBpm: Int = 0,       // 개인 Zone2 하한
    val hiBpm: Int = 0,       // 개인 Zone2 상한
    val predictedHr60: Int = -1, // 60초 뒤 예측 심박
    val tempC: Double? = null,   // 기온(℃). 더위는 드리프트↑ → 표현 맥락(방향 아님). null=미상
) {
    /** 기온 밴드. 더위만 코칭 맥락에 반영(생리적으로 Zone2 드리프트에 영향). 방향은 아님. */
    val heat: HeatBand
        get() = when {
            tempC == null -> HeatBand.UNKNOWN
            tempC >= 28 -> HeatBand.HOT
            else -> HeatBand.OK
        }
    /**
     * 케이던스 판정. 근거: 걸음 빈도를 5~10% 올리면 무릎/고관절 부하가 유의미하게 감소
     * (Heiderscheit et al. 2011, Med Sci Sports Exerc; Schubert et al. 2014 리뷰),
     * 엘리트 준거 ~180spm(Daniels). → 저케이던스(<162 = 180-10%)는 부상 예방 관점에서
     * "보폭 줄이고 빈도 올리기"를 권고. 고케이던스는 합의가 약해 >190에서만 부드럽게 안내.
     */
    val cadence: CadenceBand
        get() = when {
            spm <= 0 -> CadenceBand.UNKNOWN
            spm < 162 -> CadenceBand.LOW
            spm > 190 -> CadenceBand.HIGH
            else -> CadenceBand.OK
        }
}

enum class CadenceBand { UNKNOWN, LOW, OK, HIGH }
enum class HeatBand { UNKNOWN, OK, HOT }

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

    // 케이던스/폼/기온 가이드 절은 방향 판정 대상이 아님 — "발걸음 빈도를 낮추고", "더우니 무리하지 말고"
    // 같은 맥락 문구가 페이스 방향 모순으로 오판되지 않게, 키워드부터 절 경계(구두점)까지 제거 후 검사.
    // 따라서 방향 문구는 이 절 밖에 두어야 한다(예: "천천히, 보폭을 줄여 올라가요" — RuleCoach 준수).
    private val nonDirectionClause = Regex("(발걸음|케이던스|스텝|걸음|보폭|더우|더위|기온|수분|탈수|무리하지)[^.,!?]*")

    fun ok(intent: CoachIntent, text: String): Boolean {
        val t = text.replace(nonDirectionClause, "")
        return when (intent) {
            CoachIntent.SPEED_UP -> downWords.none(t::contains) && upCues.any(t::contains)
            CoachIntent.SLOW_DOWN -> upWords.none(t::contains) && downCues.any(t::contains)
            CoachIntent.MAINTAIN -> upWords.none(t::contains) && downWords.none(t::contains)
        }
    }
}

/**
 * 정적 규칙 코치. 의도별 문구를 상황(경사/케이던스)에 맞게 고른다.
 * spec-024: 말투(페르소나)별 문구 세트 — LLM 폴백(warmup 전 첫 코칭 등)에서도 말투가 유지되게
 * (사용자 보고 2026-07-08: 말투 변경이 두 번째 코칭부터 적용됨 — 첫 코칭=규칙 폴백이 중립 톤이던 문제).
 * 방향어(올려/낮춰 등)는 모든 페르소나에서 고정 — 어미/톤만 다르다.
 */
class RuleCoach(personaKey: String = "default") : Coach {
    override val name = "rule"

    private var counter = 0
    private val p = PHRASES[personaKey] ?: PHRASES.getValue("default")

    /** 페르소나별 문구 세트. 방향어는 고정, 어미만 변형. */
    private class Phrases(
        val preSlow: String, val preUp: String, val preKeep: String,
        val upDownhill: String, val up: List<String>,
        val slowUphill: String, val slow: List<String>,
        val keep: List<String>,
        val cadenceLow: String, val cadenceHigh: String, val heat: String,
    )

    override suspend fun say(ctx: CoachContext): String {
        val uphill = ctx.slopePct > 2
        val downhill = ctx.slopePct < -2
        // 선제 코칭(FR3 선제, 구 spec-014 FR4): 아직 존 안 — "곧 이탈" 예측을 미리 알려 존 체류 시간을 지킨다
        if (ctx.preemptive) {
            val line = when (intentOf(ctx.judgment)) {
                CoachIntent.SLOW_DOWN -> p.preSlow
                CoachIntent.SPEED_UP -> p.preUp
                CoachIntent.MAINTAIN -> p.preKeep
            }
            return guard(line + cadenceTip(ctx) + heatTip(ctx))
        }
        val lines = when (intentOf(ctx.judgment)) {
            CoachIntent.SPEED_UP -> if (downhill) listOf(p.upDownhill) else p.up
            CoachIntent.SLOW_DOWN -> if (uphill) listOf(p.slowUphill) else p.slow
            CoachIntent.MAINTAIN -> p.keep
        }
        return guard(lines[counter++ % lines.size] + cadenceTip(ctx) + heatTip(ctx))
    }

    /** 케이던스 폼 가이드(범위 밖일 때만 덧붙임). 방향 문구가 아니라 폼 문구 — DirectionGuard는 케이던스 절을 제외하고 판정. */
    private fun cadenceTip(ctx: CoachContext): String = when (ctx.cadence) {
        CadenceBand.LOW -> p.cadenceLow
        CadenceBand.HIGH -> p.cadenceHigh
        else -> ""
    }

    /** 더위 맥락(28℃+). 더울수록 심박/드리프트↑ → 무리 말고 수분. 방향 아님(DirectionGuard 제외 절). */
    private fun heatTip(ctx: CoachContext): String =
        if (ctx.heat == HeatBand.HOT) p.heat else ""

    /** 출력 가드: 길이 제한/공백 정리(adr-002 출력 가드의 최소판). */
    private fun guard(s: String): String {
        val t = s.trim().replace(Regex("\\s+"), " ")
        return if (t.length > 80) t.take(79) + "…" else t
    }

    private companion object {
        val PHRASES: Map<String, Phrases> = mapOf(
            "default" to Phrases(
                preSlow = "이대로면 심박이 곧 Zone 2를 넘겠어요. 미리 살짝 늦춰요.",
                preUp = "심박이 곧 Zone 2 아래로 내려가겠어요. 페이스를 조금 올려요.",
                preKeep = "좋아요, Zone 2 유지 중이에요. 이 리듬 그대로.",
                upDownhill = "내리막이에요. 조금 더 밀어서 심박을 Zone 2로 올려볼까요.",
                up = listOf(
                    "여유가 있어요. 페이스를 살짝 올려 Zone 2로 들어가요.",
                    "심박이 낮아요. 조금만 더 속도를 내볼게요.",
                ),
                slowUphill = "오르막이라 심박이 올랐어요. 천천히, 보폭을 줄여 올라가요.",
                slow = listOf(
                    "심박이 Zone 2를 넘었어요. 페이스를 조금 늦춰요.",
                    "약간 빨라요. 호흡을 고르며 속도를 낮춰볼게요.",
                ),
                keep = listOf(
                    "좋아요, Zone 2 유지 중이에요. 이 리듬 그대로.",
                    "완벽해요. 지금 페이스를 계속 지켜주세요.",
                ),
                cadenceLow = " 부상 예방을 위해 보폭은 줄이고 발걸음은 더 자주 디뎌요.",
                cadenceHigh = " 발걸음 빈도는 살짝 낮추고 보폭을 편안하게.",
                heat = " 더우니 무리하지 말고 수분 챙겨요.",
            ),
            "spartan" to Phrases(
                preSlow = "이대로면 곧 Zone 2를 넘는다. 미리 늦춰.",
                preUp = "심박이 곧 Zone 2 아래로 떨어진다. 페이스 올려.",
                preKeep = "Zone 2 유지 중이다. 그대로 간다.",
                upDownhill = "내리막이다. 더 밀어서 심박을 Zone 2로 올려.",
                up = listOf(
                    "심박이 낮다. 페이스 올려.",
                    "여유 부릴 때 아니다. 속도를 올려 Zone 2로 들어가.",
                ),
                slowUphill = "오르막이다. 천천히, 보폭 줄여서 올라가.",
                slow = listOf(
                    "Zone 2 초과다. 페이스 낮춰.",
                    "빠르다. 호흡 다스리고 속도 낮춰.",
                ),
                keep = listOf(
                    "좋다. 이 페이스 그대로 유지해.",
                    "완벽하다. 흐트러지지 마라.",
                ),
                cadenceLow = " 보폭 줄이고 발은 더 자주 디뎌.",
                cadenceHigh = " 발걸음 빈도 살짝 낮추고 보폭 편하게.",
                heat = " 덥다. 무리하지 말고 수분 챙겨.",
            ),
            "friend" to Phrases(
                preSlow = "이대로면 곧 Zone 2 넘겠어. 미리 살짝 늦추자.",
                preUp = "심박이 곧 Zone 2 아래로 내려가겠는데? 페이스 조금 올리자.",
                preKeep = "좋아, Zone 2 유지 중! 이대로 가자.",
                upDownhill = "내리막이야! 조금만 더 밀어서 심박을 Zone 2로 올려보자.",
                up = listOf(
                    "아직 여유 있네. 페이스 살짝 올려볼까?",
                    "심박이 낮아. 조금만 더 속도 올려보자!",
                ),
                slowUphill = "오르막이라 심박이 올라갔네. 천천히, 보폭 줄여서 올라가자.",
                slow = listOf(
                    "Zone 2 살짝 넘었어. 페이스 조금만 늦춰줘.",
                    "좀 빠른데? 호흡 고르면서 속도 낮춰보자.",
                ),
                keep = listOf(
                    "딱 좋아! Zone 2 그대로야. 이 리듬 계속 가자.",
                    "완벽해! 지금 페이스 그대로!",
                ),
                cadenceLow = " 보폭은 줄이고 발은 더 자주! 무릎 아끼자.",
                cadenceHigh = " 발걸음 빈도 살짝 낮추고 보폭 편하게 가자.",
                heat = " 덥다, 무리하지 말고 물 꼭 챙겨!",
            ),
            "calm" to Phrases(
                preSlow = "이대로면 심박이 곧 Zone 2를 넘을 것으로 예상됩니다. 미리 조금 늦춰주십시오.",
                preUp = "심박이 곧 Zone 2 아래로 내려갈 것으로 예상됩니다. 페이스를 살짝 올려주십시오.",
                preKeep = "Zone 2 유지 중입니다. 현재 리듬 그대로 가시면 됩니다.",
                upDownhill = "내리막 구간입니다. 조금 더 밀어 심박을 Zone 2로 올려주십시오.",
                up = listOf(
                    "심박에 여유가 있습니다. 페이스를 살짝 올려주십시오.",
                    "심박이 낮은 상태입니다. 속도를 조금 올려주시기 바랍니다.",
                ),
                slowUphill = "오르막 구간으로 심박이 상승했습니다. 천천히, 보폭을 줄여 오르십시오.",
                slow = listOf(
                    "심박이 Zone 2를 넘었습니다. 페이스를 조금 늦춰주십시오.",
                    "다소 빠릅니다. 호흡을 고르며 속도를 낮춰주시기 바랍니다.",
                ),
                keep = listOf(
                    "Zone 2를 유지하고 있습니다. 현재 리듬을 지켜주십시오.",
                    "안정적입니다. 지금 페이스를 그대로 유지하시기 바랍니다.",
                ),
                cadenceLow = " 부상 예방을 위해 보폭을 줄이고 발걸음을 더 자주 디뎌주십시오.",
                cadenceHigh = " 발걸음 빈도를 살짝 낮추고 보폭을 편안하게 하십시오.",
                heat = " 기온이 높습니다. 무리하지 마시고 수분을 섭취하십시오.",
            ),
        )
    }
}
