package com.zone2runner.app.coaching

import android.content.Context
import org.json.JSONObject

/**
 * LLM 코칭 프롬프트 — 간결 구조화 템플릿(spec-028 FR1, adr-028).
 * 형태: "러닝 코치. 말투: {persona}. 상황: {facts}. 임무: {task} {limits}"
 * - 사실(facts)은 규칙이 확정한 관측 숫자만 나열(코드가 조립) — 판단은 규칙, 언어는 LLM.
 * - 임무(task)는 방향 3종 + 특수 6종(마일스톤/워밍업/회복/오르막 예방/관절 보호/드리프트 여유).
 * - 템플릿 문구는 `assets/coach_prompt.json` 외부화 유지(adr-002), 실패 시 내장 기본(DEFAULT).
 */
class CoachPrompt private constructor(private val t: JSONObject) {

    fun render(ctx: CoachContext, personaKey: String = "default"): String {
        val persona = t.g("persona").optString(personaKey).ifBlank { t.g("persona").optString("default") }
        val filled = t.optString("base")
            .replace("{persona}", persona)
            .replace("{facts}", facts(ctx))
            .replace("{task}", task(ctx))
            .replace("{limits}", t.optString("limits"))
        return filled.replace(Regex("\\s+"), " ").trim()
    }

    /** 규칙이 확정한 사실 숫자만 나열. 숫자 무결성 가드(NumberGuard)의 허용 집합과 같은 원천. */
    private fun facts(ctx: CoachContext): String {
        val parts = ArrayList<String>()
        if (ctx.currentHr > 0 && ctx.hiBpm > ctx.loBpm)
            parts += "심박 ${ctx.currentHr}(목표 ${ctx.loBpm}~${ctx.hiBpm})"
        parts += when { ctx.slopePct > 2 -> "오르막"; ctx.slopePct < -2 -> "내리막"; else -> "평지" }
        if (ctx.heat == HeatBand.HOT) parts += "기온 ${ctx.tempC?.toInt()}도 더움"
        when (ctx.cadence) {
            CadenceBand.LOW -> parts += "케이던스 ${ctx.spm} 낮음"
            CadenceBand.HIGH -> parts += "케이던스 ${ctx.spm} 높음"
            else -> {}
        }
        return parts.joinToString(", ")
    }

    /** 임무 선택 — RuleCoach(폴백 큐)와 같은 우선순위. */
    private fun task(ctx: CoachContext): String = when {
        ctx.milestoneMin > 0 -> t.g("task").optString("milestone").replace("{min}", ctx.milestoneMin.toString())
        ctx.jointProtect -> t.g("task").optString("joint")
        ctx.uphillWarn -> t.g("task").optString("uphill_warn")
        ctx.recovering -> t.g("task").optString("recovery")
        ctx.warmup -> t.g("task").optString("warmup")
        ctx.driftRising && ctx.judgment == com.zone2runner.app.domain.ZoneJudgment.IN ->
            if (ctx.latePacing) t.g("task").optString("late") else t.g("task").optString("drift")
        else -> when (intentOf(ctx.judgment)) {
            CoachIntent.SPEED_UP -> t.g("task").optString("speed_up")
            CoachIntent.SLOW_DOWN -> t.g("task").optString("slow_down")
            CoachIntent.MAINTAIN -> t.g("task").optString("maintain")
        }
    }

    private fun JSONObject.g(key: String): JSONObject = optJSONObject(key) ?: JSONObject()

    companion object {
        /** 에셋에서 로드, 실패 시 내장 기본값. */
        fun load(context: Context): CoachPrompt = runCatching {
            val raw = context.assets.open("coach_prompt.json").bufferedReader().use { it.readText() }
            CoachPrompt(JSONObject(raw))
        }.getOrElse { CoachPrompt(JSONObject(DEFAULT)) }

        /** 테스트/폴백용 — Android Context 없이 기본 템플릿. */
        fun default(): CoachPrompt = CoachPrompt(JSONObject(DEFAULT))

        // 에셋과 동일 내용(폴백). 방향 임무의 어휘 강제는 DirectionGuard 통과율을 높이는 실용 장치.
        private const val DEFAULT = """{
          "base": "러닝 코치. 말투: {persona}. 상황: {facts}. 임무: {task} {limits}",
          "limits": "한국어 한 문장, 35자 내외, 따옴표와 이모지 없이, 숫자는 상황에 준 것만.",
          "persona": {
            "default": "보통",
            "spartan": "짧고 단호한 명령형",
            "friend": "친한 친구 반말",
            "calm": "차분한 존댓말"
          },
          "task": {
            "speed_up": "페이스를 올리게 하라('올려' 또는 '높여' 포함).",
            "slow_down": "페이스를 낮추게 하라('낮춰' 또는 '천천히' 포함).",
            "maintain": "지금 페이스 유지를 격려하라(올리거나 낮추라는 말 금지).",
            "milestone": "Zone 2 연속 {min}분 달성을 축하하라.",
            "warmup": "초반이니 천천히 올리라고 안내하라.",
            "recovery": "심박이 잘 내려가는 중임을 알려라.",
            "uphill_warn": "곧 오르막이니 미리 여유를 두라고 하라.",
            "joint": "내리막이니 보폭을 줄이고 부드럽게 착지해 무릎을 보호하라고 하라(속도 올리라는 말 금지).",
            "late": "세션 후반이니 끝까지 유지하도록 여유를 두라고 하라.",
            "drift": "정속인데 심박이 서서히 오르는 중이니 여유를 두라고 하라."
          }
        }"""
    }
}
