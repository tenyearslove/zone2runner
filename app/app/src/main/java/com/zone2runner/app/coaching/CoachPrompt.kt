package com.zone2runner.app.coaching

import android.content.Context
import org.json.JSONObject

/**
 * LLM 코칭 프롬프트 템플릿 (외부화 — adr-002 표현/로직 분리, 사용자 요청 2026-07-06).
 * 템플릿 텍스트는 `assets/coach_prompt.json`에 두어 **코드 수정 없이 문구 편집**이 가능하다.
 * 로직(방향을 규칙이 정함, DirectionGuard로 가드)은 코드에 남고, 여기서는 "무엇을 어떻게 말할지"의
 * 문구 껍데기만 채운다. 에셋 누락/파싱 실패 시 내장 기본값(DEFAULT)으로 폴백해 항상 동작한다.
 *
 * 3번(맥락 확장): {context} 줄에 현재 심박/개인 목표 범위/60초 예측을 넣어 LLM이 상황을 수치로
 * 인지하고 자연스럽게 표현하게 한다. 방향과 모순되면 DirectionGuard가 기각하므로 안전.
 */
class CoachPrompt private constructor(private val t: JSONObject) {

    /** 규칙이 정한 방향/의도 + 상황을 채워 최종 프롬프트 문자열 생성. */
    fun render(ctx: CoachContext): String {
        val intent = intentOf(ctx.judgment)
        val terrain = t.g("terrain").optString(
            when { ctx.slopePct > 2 -> "uphill"; ctx.slopePct < -2 -> "downhill"; else -> "flat" }
        )
        val dirKey = when (intent) {
            CoachIntent.SPEED_UP -> if (ctx.preemptive) "speed_up_preemptive" else "speed_up"
            CoachIntent.SLOW_DOWN -> if (ctx.preemptive) "slow_down_preemptive" else "slow_down"
            CoachIntent.MAINTAIN -> "maintain"
        }
        val mustKey = when (intent) {
            CoachIntent.SPEED_UP -> "speed_up"; CoachIntent.SLOW_DOWN -> "slow_down"; CoachIntent.MAINTAIN -> "maintain"
        }
        val cadence = when (ctx.cadence) {
            CadenceBand.LOW -> t.g("cadence").optString("low").replace("{spm}", ctx.spm.toString())
            CadenceBand.HIGH -> t.g("cadence").optString("high").replace("{spm}", ctx.spm.toString())
            else -> ""
        }
        // 기온 맥락(더울 때만): 방향 아님 — LLM이 무리 말고 수분 챙기라는 조언을 덧붙이게(옵션 절)
        val heat = if (ctx.heat == HeatBand.HOT)
            t.optString("heat_hot").replace("{temp}", ctx.tempC?.toInt()?.toString() ?: "") else ""
        val context = buildContext(ctx)
        return t.optString("base")
            .replace("{terrain}", terrain)
            .replace("{context}", context)
            .replace("{direction}", t.g("direction").optString(dirKey))
            .replace("{must}", t.g("must").optString(mustKey))
            .replace("{cadence}", cadence)
            .replace("{heat}", heat)
            .replace("{limits}", t.optString("limits"))
            .replace(Regex("\\s+"), " ").trim()
    }

    /** 현재 심박/목표 범위/예측이 유효할 때만 {context} 줄을 채운다(없으면 빈 문자열 → 기존 동작). */
    private fun buildContext(ctx: CoachContext): String {
        if (ctx.currentHr <= 0 || ctx.hiBpm <= ctx.loBpm) return ""
        val predStr = if (ctx.predictedHr60 > 0)
            t.optString("context_pred").replace("{pred}", ctx.predictedHr60.toString()) else ""
        return t.optString("context")
            .replace("{cur}", ctx.currentHr.toString())
            .replace("{lo}", ctx.loBpm.toString())
            .replace("{hi}", ctx.hiBpm.toString())
            .replace("{pred}", predStr)
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

        // 에셋과 동일 내용(폴백). 에셋을 지워도 기존 동작 보존.
        private const val DEFAULT = """{
          "base": "당신은 러닝 코치입니다. {terrain} 입니다. {context}러너에게 {direction} 격려하는 한국어 한 문장으로 자연스럽게 안내하세요. {must}{cadence}{heat} {limits}",
          "limits": "35자 내외, 따옴표와 이모지 없이.",
          "context": "현재 심박 {cur}bpm, 목표 {lo}~{hi}bpm{pred}. ",
          "context_pred": ", 이대로면 60초 뒤 {pred}bpm 예상",
          "heat_hot": " 기온이 {temp}도로 덥습니다. 무리하지 말고 수분을 챙기라는 조언을 짧게 덧붙이세요.",
          "terrain": {"uphill":"지형은 오르막","flat":"지형은 평지","downhill":"지형은 내리막"},
          "direction": {
            "speed_up":"페이스를 살짝 올려 심박을 Zone 2로 높이도록",
            "speed_up_preemptive":"아직 Zone 2 안이지만 심박이 곧 아래로 내려갈 것으로 예측되니 미리 페이스를 살짝 올리도록",
            "slow_down":"페이스를 조금 낮춰 심박을 Zone 2로 내리도록",
            "slow_down_preemptive":"아직 Zone 2 안이지만 심박이 곧 상한을 넘을 것으로 예측되니 미리 페이스를 조금 낮추도록",
            "maintain":"지금 페이스를 그대로 유지하도록"
          },
          "must": {
            "speed_up":"'올려' 또는 '높여' 같은 올리는 표현을 문장에 반드시 포함하세요.",
            "slow_down":"'낮춰' 또는 '천천히' 같은 낮추는 표현을 문장에 반드시 포함하세요.",
            "maintain":"속도를 올리거나 낮추라는 말은 하지 마세요."
          },
          "cadence": {
            "low":" 케이던스가 {spm}spm으로 낮아 보폭이 큰 편입니다. 발걸음을 잘게 자주 디디라는 조언을 짧게 덧붙이세요.",
            "high":" 케이던스가 {spm}spm으로 지나치게 높습니다. 발걸음 빈도를 살짝 낮추라는 조언을 짧게 덧붙이세요."
          }
        }"""
    }
}
