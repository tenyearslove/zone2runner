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
    private val personaKey: String = "default", // 말투(spec-024 FR3) — 문체만, 방향은 규칙 불변
    private val fallback: RuleCoach = RuleCoach(personaKey), // 폴백(첫 코칭 warmup 전 포함)도 같은 말투
    private val template: CoachPrompt = CoachPrompt.load(context), // 프롬프트 문구는 assets에서(코드 밖)
) : Coach {
    override val name = "llm"

    private val client by lazy { Generation.getClient() }
    private val rewriter = NanoRewriter(context, personaKey) // 규칙 문장 톤 재작성(adr-026, 1순위). 미가용 시 Prompt 폴백.
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

    /** 자유 설명 생성(코칭 외 용도 — 예: Zone2 계산 설명 팝업). 미가용/실패 시 null. */
    suspend fun freeform(prompt: String): String? {
        if (!ensureReady()) return null
        return try {
            val res = withContext(Dispatchers.Default) {
                withTimeout(8_000) { client.generateContent(prompt) }
            }
            stripEmoji(res.candidates.firstOrNull()?.text ?: "").trim().ifBlank { null }
        } catch (e: Throwable) { null }
    }

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
        // 격려/인지/예방 코칭(마일스톤/워밍업/회복/오르막 예방)은 방향이 없어 LLM 방향 표현이 부적절 → 규칙 문구.
        if (ctx.milestoneMin > 0 || ctx.warmup || ctx.recovering || ctx.uphillWarn) {
            lastPath = "rule(특수 코칭)"; return fallback.say(ctx)
        }
        // 1순위(adr-026): 규칙이 확정한 문장의 '톤'만 Nano로 재작성(내용=규칙이라 방향 잠금이 더 강함).
        val ruleLine = fallback.say(ctx)
        val toned = rewriter.rewrite(ruleLine)
        if (toned != ruleLine) {
            val g = guard(toned)
            if (g != null && DirectionGuard.ok(intentOf(ctx.judgment), g)) {
                usedLlmOnce = true; lastPrompt = ruleLine; lastPath = "llm(톤 재작성)"; return g
            }
            if (g != null) directionRejects++ // 재작성이 방향을 흐리면 아래 Prompt/규칙으로 폴백
        }
        // 2순위(폴백): 기존 Prompt 자유 표현 경로
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

    /** 프롬프트 생성 = 외부 템플릿(assets/coach_prompt.json) 위임(adr-002). 방향은 규칙이 정하고
     *  템플릿은 문구 껍데기만 채운다 — CoachPrompt.render 참조. 문구 편집은 코드가 아니라 에셋에서. */
    private fun buildPrompt(ctx: CoachContext): String = template.render(ctx, personaKey)

    /** 출력 가드(adr-002): 이모지 제거(TTS가 읽음)/공백 정리/따옴표 제거/최대 2문장/길이 제한. 비면 null(폴백). */
    private fun guard(raw: String?): String? {
        val t = raw?.let(::stripEmoji)?.trim()?.replace(Regex("\\s+"), " ")?.trim('"', '\'', '“', '”') ?: return null
        if (t.isBlank()) return null
        val sentences = t.split(Regex("(?<=[.!?。])")).map { it.trim() }.filter { it.isNotBlank() }
        val kept = sentences.take(2).joinToString(" ").ifBlank { t }
        return if (kept.length > 90) kept.take(89) + "…" else kept
    }

    /** 이모지/픽토그램 제거 — 프롬프트로 금지해도 LLM이 종종 붙이고, TTS가 "웃는 얼굴"처럼 읽는다. */
    private fun stripEmoji(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val emoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
                cp in 0xFE00..0xFE0F || cp == 0x200D || cp in 0x1F1E6..0x1F1FF
            if (!emoji) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
