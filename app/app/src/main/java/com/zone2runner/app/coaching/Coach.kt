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
    // LLM 표현 풍부화용 실측 수치(방향은 규칙이 결정 — 이 값들은 표현 재료일 뿐, adr-002). 0/-1=미상.
    val currentHr: Int = 0,   // 지속 심박(판정 기준 bpm)
    val loBpm: Int = 0,       // 개인 Zone2 하한
    val hiBpm: Int = 0,       // 개인 Zone2 상한
    val tempC: Double? = null,   // 기온(℃). 더위는 드리프트↑ → 표현 맥락(방향 아님). null=미상
    // 관측 분석 엔진(FR3, spec-025) 반응형 코칭 컨텍스트. 예측 대체 — 관측된 드리프트 상승에 반응.
    val driftRising: Boolean = false,   // 정속인데 심박이 유의하게 오르는 중(드리프트↑) → 선제적 감속 안내
    val gapPaceMinKm: Double? = null,   // 경사보정 페이스(오르막 맥락, 방향 아님)
    val milestoneMin: Int = 0,          // Zone 2 연속 유지 마일스톤(분). >0이면 격려 코칭(FR5)
    val warmup: Boolean = false,        // 초반 심박 급상승 → 천천히 올리라는 워밍업 큐
    val latePacing: Boolean = false,    // 세션 후반 드리프트 → 끝까지 유지하려면 여유
    val recovering: Boolean = false,    // 심박 급강하(회복 구간) → 잘 회복 중 인지
    val uphillWarn: Boolean = false,    // 오르막 초과 경향 학습 → 오르막 진입 시 사전 예방(패턴 학습)
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
        val upDownhill: String, val up: List<String>,
        val slowUphill: String, val slow: List<String>,
        val keep: List<String>,
        val cadenceLow: String, val cadenceHigh: String, val heat: String,
        val driftWarn: String,
        val milestone: String, // "{min}" 치환
        val warmupCue: String, val latePace: String, val recovery: String,
        val uphillWarn: String,
    )

    override suspend fun say(ctx: CoachContext): String {
        val uphill = ctx.slopePct > 2
        val downhill = ctx.slopePct < -2
        // 마일스톤 격려(FR5): Zone 2 연속 유지 N분 달성 — 동기부여(방향 아님).
        if (ctx.milestoneMin > 0) {
            return guard(p.milestone.replace("{min}", ctx.milestoneMin.toString()))
        }
        // 패턴 학습 예방(FR5): 오르막서 자주 초과하던 사람 → 오르막 진입 시 미리 안내(방향 아님).
        if (ctx.uphillWarn) return guard(p.uphillWarn + heatTip(ctx))
        // 회복 구간 인지(FR5): 심박이 빠르게 내려가는 중 — 잘 회복하고 있음을 알림.
        if (ctx.recovering) return guard(p.recovery + heatTip(ctx))
        // 워밍업 큐(FR5): 초반 급상승 — 천천히 올리라는 안내(방향 아님).
        if (ctx.warmup) return guard(p.warmupCue + heatTip(ctx))
        // 반응형(FR5, spec-025): 정속인데 심박이 오르는 중(관측 드리프트↑) → 미리 여유. 후반이면 후반 페이싱 문구.
        // 예측이 아니라 "지금 관측된 추세"에 반응(방향 강제 없이 MAINTAIN 톤 — 잘못된 방향 위험 없음).
        if (ctx.driftRising && ctx.judgment == ZoneJudgment.IN) {
            val base = if (ctx.latePacing) p.latePace else p.driftWarn
            return guard(base + cadenceTip(ctx) + heatTip(ctx)) // heatTip = 더위+드리프트 결합(더위 페이싱 강화)
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
                driftWarn = "심박이 서서히 오르고 있어요. 지금 페이스에 여유를 두고 리듬을 지켜봐요.",
                milestone = "Zone 2 {min}분째 완벽 유지예요! 이 리듬 그대로 가요.",
                warmupCue = "출발이 좋아요. 초반엔 천천히 올려서 몸을 데워요.",
                latePace = "후반이에요. 끝까지 Zone 2로 마치려면 지금 살짝 여유를 둬요.",
                recovery = "좋아요, 심박이 잘 내려가고 있어요. 편하게 회복해요.",
                uphillWarn = "오르막이 시작돼요. 여기서 심박이 잘 오르니 미리 페이스를 살짝 늦춰 두세요.",
            ),
            "spartan" to Phrases(
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
                driftWarn = "심박이 슬슬 오른다. 지금 페이스에 여유 두고 리듬 지켜.",
                milestone = "Zone 2 {min}분 유지. 잘하고 있다. 그대로 간다.",
                warmupCue = "초반이다. 천천히 올려 몸부터 데워.",
                latePace = "후반이다. 끝까지 유지하려면 지금 여유 둬.",
                recovery = "좋다. 심박 잘 내려간다. 편하게 회복해.",
                uphillWarn = "오르막 시작이다. 여기서 심박 잘 튄다. 미리 페이스 늦춰.",
            ),
            "friend" to Phrases(
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
                driftWarn = "심박이 슬슬 올라오네. 지금 페이스에 여유 두고 리듬 지켜보자.",
                milestone = "Zone 2 {min}분째야! 완전 잘하고 있어. 이대로 가자.",
                warmupCue = "출발 좋아! 초반엔 천천히 올려서 몸 데우자.",
                latePace = "이제 후반이야. 끝까지 Zone 2로 가려면 살짝 여유 두자.",
                recovery = "좋아, 심박 잘 내려간다. 편하게 회복하자.",
                uphillWarn = "오르막 시작이야. 여기서 심박 잘 올라. 미리 페이스 살짝 늦추자.",
            ),
            "calm" to Phrases(
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
                driftWarn = "심박이 서서히 상승하고 있습니다. 현재 페이스에 여유를 두고 리듬을 지켜보십시오.",
                milestone = "Zone 2를 {min}분째 유지하고 있습니다. 아주 좋습니다. 현재 리듬을 지켜주십시오.",
                warmupCue = "출발이 좋습니다. 초반에는 천천히 올려 몸을 데워주십시오.",
                latePace = "후반부입니다. 끝까지 Zone 2로 마치려면 지금 여유를 두시기 바랍니다.",
                recovery = "좋습니다. 심박이 잘 내려가고 있습니다. 편안하게 회복하십시오.",
                uphillWarn = "오르막이 시작됩니다. 이 구간에서 심박이 잘 오르니 미리 페이스를 조금 늦춰주십시오.",
            ),
        )
    }
}
