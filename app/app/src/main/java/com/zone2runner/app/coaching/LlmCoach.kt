package com.zone2runner.app.coaching

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 온디바이스 LLM 코치 (Gemini Nano, ML Kit Prompt API — adr-007에서 실기기 검증).
 * adr-002 원칙: 코칭 "방향(의도)"은 규칙이 결정하고, LLM은 그 의도를 자연스러운 문장으로 "표현"만 한다.
 * 출력 가드(길이/문장/따옴표 제거)를 거치고, 실패/미가용 시 RuleCoach로 폴백한다.
 *
 * 제약(adr-007): 포그라운드 전용, 모델 미다운로드/미지원 기기에서는 미가용 -> 규칙 폴백.
 * 여기서는 안전하게 모델 자동 다운로드를 트리거하지 않는다(AVAILABLE일 때만 사용).
 */
class LlmCoach(
    context: Context,
    private val fallback: RuleCoach = RuleCoach(),
) : Coach {
    override val name = "llm"

    private val client by lazy { Generation.getClient() }
    private var checked = false
    private var available = false
    private var usedLlmOnce = false

    /** 방향 잠금 가드(DirectionGuard)가 LLM 문장을 기각한 횟수(필드 로그 end 이벤트용). */
    @Volatile var directionRejects = 0
        private set

    /** 마지막 코칭에 사용한 프롬프트(시뮬/목 모드 디버그 노출용). LLM 미가용이어도 채워 보여준다. */
    @Volatile var lastPrompt: String? = null
        private set

    /** 마지막 코칭 문장의 경로: llm / rule(미가용) / rule(빈 출력) / rule(방향 기각) / rule(오류·타임아웃). */
    @Volatile var lastPath: String = ""
        private set

    /** 이번 세션에서 실제로 LLM 문장을 한 번이라도 냈는지(리포트 coachSource 표기용). */
    fun sessionSource(): String = if (usedLlmOnce) "llm" else "rule"

    /** 세션 시작 시 미리 호출해 checkStatus+warmup(최대 30초)을 첫 코칭 경로에서 빼낸다. */
    suspend fun prewarm() { ensureReady() }

    // ML Kit 호출(checkStatus/warmup/generateContent)이 메인 스레드를 물지 않도록 Default로 격리
    private suspend fun ensureReady(): Boolean = withContext(Dispatchers.Default) {
        if (checked) return@withContext available
        checked = true
        available = try {
            val status = client.checkStatus()
            if (status == FeatureStatus.AVAILABLE) {
                runCatching { withTimeout(30_000) { client.warmup() } }
                true
            } else false // DOWNLOADABLE/DOWNLOADING/UNAVAILABLE -> 폴백(다운로드 트리거 안 함)
        } catch (e: Throwable) {
            false
        }
        available
    }

    override suspend fun say(ctx: CoachContext): String {
        val prompt = buildPrompt(ctx)
        lastPrompt = prompt // 디버그 노출: 실제 LLM 호출 여부와 무관하게 "이 프롬프트를 쓴다"를 보여준다
        if (!ensureReady()) { lastPath = "rule(LLM 미가용)"; return fallback.say(ctx) }
        return try {
            val res = withContext(Dispatchers.Default) {
                withTimeout(6_000) { client.generateContent(prompt) }
            }
            val text = res.candidates.firstOrNull()?.text
            val guarded = guard(text)
            when {
                guarded == null -> { lastPath = "rule(빈 출력)"; fallback.say(ctx) }
                !DirectionGuard.ok(intentOf(ctx.judgment), guarded) -> {
                    directionRejects++ // 방향 모순/무방향 문장 기각(adr-002 방향 잠금)
                    lastPath = "rule(방향 기각: \"${guarded.take(24)}…\")"
                    fallback.say(ctx)
                }
                else -> { usedLlmOnce = true; lastPath = "llm"; guarded }
            }
        } catch (e: Throwable) {
            lastPath = "rule(오류/타임아웃)"
            fallback.say(ctx) // 포그라운드 제약(ErrorCode 30)/타임아웃 등 -> 규칙 폴백
        }
    }

    /** 규칙이 정한 방향을 프롬프트에 명시(방향 잠금) + 지형 맥락. LLM은 표현만 바꾼다.
     *  방향 표현을 반드시 포함하도록 지시 — DirectionGuard 통과율을 높인다(미포함 시 규칙 폴백). */
    private fun buildPrompt(ctx: CoachContext): String {
        val (direction, must) = when (intentOf(ctx.judgment)) {
            CoachIntent.SPEED_UP ->
                (if (ctx.preemptive) "아직 Zone 2 안이지만 심박이 곧 아래로 내려갈 것으로 예측되니 미리 페이스를 살짝 올리도록"
                 else "페이스를 살짝 올려 심박을 Zone 2로 높이도록") to
                    "'올려' 또는 '높여' 같은 올리는 표현을 문장에 반드시 포함하세요."
            CoachIntent.SLOW_DOWN ->
                (if (ctx.preemptive) "아직 Zone 2 안이지만 심박이 곧 상한을 넘을 것으로 예측되니 미리 페이스를 조금 낮추도록"
                 else "페이스를 조금 낮춰 심박을 Zone 2로 내리도록") to
                    "'낮춰' 또는 '천천히' 같은 낮추는 표현을 문장에 반드시 포함하세요."
            CoachIntent.MAINTAIN ->
                "지금 페이스를 그대로 유지하도록" to "속도를 올리거나 낮추라는 말은 하지 마세요."
        }
        val terrain = when {
            ctx.slopePct > 2 -> "지형은 오르막"
            ctx.slopePct < -2 -> "지형은 내리막"
            else -> "지형은 평지"
        }
        // 케이던스 폼 가이드(범위 밖일 때만): 방향과 별개의 폼 조언 — DirectionGuard는 케이던스 절 제외 판정
        val cadence = when (ctx.cadence) {
            CadenceBand.LOW -> " 케이던스가 ${ctx.spm}spm으로 낮아 보폭이 큰 편입니다. 발걸음을 잘게 자주 디디라는 조언을 짧게 덧붙이세요."
            CadenceBand.HIGH -> " 케이던스가 ${ctx.spm}spm으로 지나치게 높습니다. 발걸음 빈도를 살짝 낮추라는 조언을 짧게 덧붙이세요."
            else -> ""
        }
        return "당신은 러닝 코치입니다. $terrain 입니다. 러너에게 ${direction} " +
            "격려하는 한국어 한 문장으로 자연스럽게 안내하세요. $must$cadence 35자 내외, 따옴표와 이모지 없이."
    }

    /** 출력 가드(adr-002): 공백 정리/따옴표 제거/최대 2문장(방향+케이던스 폼)/길이 제한. 비면 null(폴백). */
    private fun guard(raw: String?): String? {
        val t = raw?.trim()?.replace(Regex("\\s+"), " ")?.trim('"', '\'', '“', '”') ?: return null
        if (t.isBlank()) return null
        val sentences = t.split(Regex("(?<=[.!?。])")).map { it.trim() }.filter { it.isNotBlank() }
        val kept = sentences.take(2).joinToString(" ").ifBlank { t }
        return if (kept.length > 90) kept.take(89) + "…" else kept
    }
}
