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
    val jointProtect: Boolean = false,  // 내리막 관절 보호(spec-026) — 관절 위험군 내리막 시 Zone2보다 우선, 미달 오코칭 대체
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

    /** 가속 명령어 포함 여부 — 방향 없는 특수 코칭(워밍업/오르막 예방/관절 보호)의 금지 검사(spec-028 FR2). */
    fun containsUpCommand(text: String): Boolean =
        upWords.any(text.replace(nonDirectionClause, "")::contains)
}

/**
 * 숫자 무결성 가드(spec-028 FR2, adr-028) — "없는 숫자 금지" 제1원칙의 기계 검증.
 * LLM 출력의 모든 숫자가 입력 사실(컨텍스트)의 숫자 집합 안에 있어야 한다. 언어 무관이라
 * 다국어에서도 그대로 동작한다. 위반 시 호출부가 기각/폴백.
 */
object NumberGuard {
    /** 컨텍스트에서 허용 숫자 집합 도출. 상수 2 = "Zone 2"(제품 용어). */
    fun allowedOf(ctx: CoachContext): Set<Long> = buildSet {
        add(2L)
        if (ctx.currentHr > 0) add(ctx.currentHr.toLong())
        if (ctx.loBpm > 0) add(ctx.loBpm.toLong())
        if (ctx.hiBpm > 0) add(ctx.hiBpm.toLong())
        if (ctx.spm > 0) add(ctx.spm.toLong())
        if (ctx.milestoneMin > 0) add(ctx.milestoneMin.toLong())
        ctx.tempC?.let { add(it.toLong()); add(Math.round(it)) }
        if (ctx.paceMinKm > 0.1) { add(ctx.paceMinKm.toLong()); add(Math.round(ctx.paceMinKm)) }
        ctx.gapPaceMinKm?.let { add(it.toLong()); add(Math.round(it)) }
        if (ctx.elapsedSec > 0) add(ctx.elapsedSec.toLong() / 60) // 경과 분
    }

    fun ok(allowed: Set<Long>, text: String): Boolean =
        Regex("\\d+").findAll(text).all { m -> m.value.toLongOrNull()?.let { it in allowed } ?: false }
}

/**
 * 코칭 근거 데이터 요약(spec-027 확장, 2026-07-24 사용자 요청) — "이 코칭이 왜 나왔나"의
 * 관측 스냅샷. 리포트 프로비넌스 팝업에서 프롬프트와 함께 표시된다. 전부 실제 관측/도출값.
 */
object CoachEvidence {
    fun of(ctx: CoachContext): String {
        val parts = ArrayList<String>()
        parts += buildString {
            append("판정 ").append(ctx.judgment.label)
            if (ctx.currentHr > 0 && ctx.hiBpm > ctx.loBpm)
                append(" (지속심박 ${ctx.currentHr}bpm, 개인 경계 ${ctx.loBpm}~${ctx.hiBpm}bpm)")
        }
        parts += "경과 %d:%02d".format(ctx.elapsedSec / 60, ctx.elapsedSec % 60)
        parts += "경사 %.1f%%".format(ctx.slopePct) +
            when { ctx.slopePct > 2 -> " (오르막)"; ctx.slopePct < -2 -> " (내리막)"; else -> "" }
        if (ctx.paceMinKm in 0.1..30.0)
            parts += "페이스 %d'%02d\"/km".format(ctx.paceMinKm.toInt(), ((ctx.paceMinKm % 1) * 60).toInt())
        ctx.gapPaceMinKm?.let { if (it in 0.1..30.0)
            parts += "경사보정 %d'%02d\"/km".format(it.toInt(), ((it % 1) * 60).toInt()) }
        if (ctx.spm > 0) parts += "케이던스 ${ctx.spm}spm"
        ctx.tempC?.let { parts += "기온 ${it.toInt()}도" }
        val triggers = ArrayList<String>()
        if (ctx.milestoneMin > 0) triggers += "Zone 2 연속 ${ctx.milestoneMin}분 달성"
        if (ctx.warmup) triggers += "초반 심박 급상승(워밍업)"
        if (ctx.recovering) triggers += "심박 하강 중(회복)"
        if (ctx.uphillWarn) triggers += "오르막 진입(개인 초과 경향 학습)"
        if (ctx.jointProtect) triggers += "내리막 관절 보호(프로필)"
        if (ctx.driftRising) triggers += "드리프트 상승 관측"
        if (ctx.latePacing) triggers += "세션 후반"
        if (triggers.isNotEmpty()) parts += "트리거: " + triggers.joinToString(", ")
        return parts.joinToString(" / ")
    }
}

/**
 * 폴백 코치 — 단어 수준 큐(spec-028 FR3, adr-028). 문장을 조합하지 않는다.
 * LLM 미지원 단말이거나 해당 호출이 실패/기각된 그 1회에만 나간다(지원 단말에서
 * 상시화되면 결함 — spec-027 감사 기록으로 검출). 페르소나는 LLM 전용이라 여기선 중립.
 * personaKey 파라미터는 호출부 호환용으로만 유지한다.
 */
class RuleCoach(@Suppress("UNUSED_PARAMETER") personaKey: String = "default") : Coach {
    override val name = "rule"

    override suspend fun say(ctx: CoachContext): String = when {
        ctx.milestoneMin > 0 -> "Zone 2 ${ctx.milestoneMin}분 유지 성공"
        ctx.jointProtect -> "내리막, 무릎 보호, 보폭 줄이기" // spec-026: 미달이어도 가속 지시 없음
        ctx.uphillWarn -> "오르막 앞, 미리 감속"
        ctx.recovering -> "회복 중"
        ctx.warmup -> "천천히 시작"
        ctx.driftRising && ctx.judgment == ZoneJudgment.IN ->
            if (ctx.latePacing) "후반, 페이스 유지" else "심박 오르는 중, 여유"
        else -> when (intentOf(ctx.judgment)) {
            CoachIntent.SPEED_UP -> "속도 올리기"
            CoachIntent.SLOW_DOWN -> "속도 줄이기"
            CoachIntent.MAINTAIN -> "좋아요, 유지"
        }
    }
}
