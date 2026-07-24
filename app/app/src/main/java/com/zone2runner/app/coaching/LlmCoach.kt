package com.zone2runner.app.coaching

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private val callLog: LlmCallLog? = null, // 프롬프트 프로비넌스/사용 기록 싱크(spec-027). null=기록 안 함
) : Coach {
    override val name = "llm"

    private val client by lazy { Generation.getClient() }
    private var checked = false
    private var available = false
    private var usedLlmOnce = false

    /** 준비 판정 완료 여부(가용/미가용 확정). false인 동안은 코칭 보류(스킵) — spec-028 로딩 정책. */
    @Volatile private var resolved = false
    private val warmKicked = java.util.concurrent.atomic.AtomicBoolean(false)
    private val warmScope = kotlinx.coroutines.CoroutineScope(
        Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

    /** 준비 완료 + 가용(시뮬 디버그 표시용). */
    fun isReady(): Boolean = resolved && available
    fun isResolved(): Boolean = resolved

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

    /** 시뮬 실시간 비교 토글(spec-028 검증): false면 LLM을 건너뛰고 단어 폴백 — 다음 코칭부터 즉시 반영. */
    @Volatile var llmEnabled: Boolean = true

    /** 세션 시작 시 미리 호출해 checkStatus+warmup(최대 30초)을 첫 코칭 경로에서 빼낸다. */
    suspend fun prewarm() { ensureReady() }

    /**
     * 자유 설명 생성(코칭 외 용도 — 세션 스토리 폴백/개인화 설명/Zone2 계산 설명 팝업). 미가용/실패 시 null.
     * purpose/tSec = 프로비넌스 기록용(spec-027). null 반환도 기록한다(폴백 사유 = 프로비넌스의 일부).
     */
    suspend fun freeform(prompt: String, purpose: String = "freeform", tSec: Int = -1): String? {
        val t0 = System.currentTimeMillis()
        fun log(engine: String, path: String, output: String) =
            callLog?.record(tSec, purpose, engine, path, prompt, output, System.currentTimeMillis() - t0)
        if (!ensureReady()) { log("rule", "rule(LLM 미가용)", ""); return null }
        return try {
            val res = withContext(Dispatchers.Default) {
                withTimeout(8_000) { client.generateContent(prompt) }
            }
            val out = stripEmoji(res.candidates.firstOrNull()?.text ?: "").trim().ifBlank { null }
            if (out == null) log("rule", "rule(빈 출력)", "") else log("nano-prompt", "llm(자유 생성)", out)
            out
        } catch (e: Throwable) { log("rule", "rule(오류/타임아웃)", ""); null }
    }

    // ML Kit 호출(checkStatus/warmup/generateContent)이 메인 스레드를 물지 않도록 Default로 격리
    private suspend fun ensureReady(): Boolean = withContext(Dispatchers.Default) {
        if (checked) return@withContext available
        checked = true
        try {
            available = try {
                val status = client.checkStatus()
                if (status == FeatureStatus.AVAILABLE) {
                    runCatching { withTimeout(30_000) { client.warmup() } }
                    true
                } else false // DOWNLOADABLE/DOWNLOADING/UNAVAILABLE -> 폴백(다운로드 트리거 안 함)
            } catch (e: Throwable) {
                false
            }
        } finally {
            resolved = true // 가용/미가용 확정 — 이때부터 코칭 재개(LLM 또는 단어 폴백)
        }
        available
    }

    override suspend fun say(ctx: CoachContext): String {
        val t0 = System.currentTimeMillis()
        val o = sayInner(ctx)
        lastPath = o.path
        lastPrompt = o.prompt
        // 프로비넌스 기록(spec-027): 코칭 1문장 = 기록 1건(리포트 코칭 로그와 1:1 정렬). 규칙 폴백도 기록.
        // 보류(빈 문장)는 코칭 라인이 안 생기므로 기록도 남기지 않는다(1:1 정렬 유지).
        if (o.text.isNotBlank()) {
            callLog?.record(ctx.elapsedSec, "coach", o.engine, o.path, o.prompt ?: "", o.text,
                System.currentTimeMillis() - t0, facts = CoachEvidence.of(ctx))
        }
        return o.text
    }

    /** say의 귀결 1건: 최종 문장 + 프로비넌스(엔진/경로/LLM 입력). */
    private data class Outcome(val text: String, val engine: String, val path: String, val prompt: String?)

    private suspend fun sayInner(ctx: CoachContext): Outcome {
        // spec-028/adr-028: 모든 코칭(방향+특수)이 LLM 직생성 1순위. 규칙은 판단/사실만 확정하고
        // 언어는 LLM이 만든다. 폴백(RuleCoach)은 단어 수준 큐 — 미지원/실패/기각의 그 1회에만.
        val prompt = buildPrompt(ctx) // 미가용이어도 채워 "이 프롬프트를 쓴다"를 감사 기록에 남긴다
        if (!llmEnabled) return Outcome(fallback.say(ctx), "rule", "rule(LLM 꺼짐)", prompt) // 수동 off(시뮬 토글)
        // 모델 로딩(상태 확인+워밍업) 중에는 코칭 보류(빈 문장 = 스킵, 사용자 결정 2026-07-23):
        // 로딩 중 폴백 문구를 내지 않고, 준비 확정 후 LLM(가용) 또는 단어 폴백(미가용)으로 재개.
        if (!resolved) {
            if (warmKicked.compareAndSet(false, true)) warmScope.launch { ensureReady() } // prewarm 미호출 대비
            return Outcome("", "skip", "skip(모델 로딩 중)", prompt)
        }
        if (!ensureReady()) return Outcome(fallback.say(ctx), "rule", "rule(LLM 미가용)", prompt)
        // 방향 검사 대상: 방향 코칭만. 특수 중 워밍업/오르막 예방/관절 보호는 가속 명령만 금지(spec-028 FR2).
        val special = ctx.milestoneMin > 0 || ctx.warmup || ctx.recovering || ctx.uphillWarn || ctx.jointProtect
        val upForbidden = ctx.warmup || ctx.uphillWarn || ctx.jointProtect
        return try {
            val res = withContext(Dispatchers.Default) {
                withTimeout(6_000) { client.generateContent(prompt) }
            }
            val text = res.candidates.firstOrNull()?.text
            val guarded = guard(text)
            when {
                guarded == null -> Outcome(fallback.say(ctx), "rule", "rule(빈 출력)", prompt)
                // 숫자 무결성(언어 무관): 출력 숫자 ⊆ 입력 사실 숫자 — 없는 숫자 금지의 기계 검증
                !NumberGuard.ok(NumberGuard.allowedOf(ctx), guarded) -> {
                    directionRejects++
                    Outcome(fallback.say(ctx), "rule", "rule(숫자 기각: \"${guarded.take(24)}…\")", prompt)
                }
                special && upForbidden && DirectionGuard.containsUpCommand(guarded) -> {
                    directionRejects++
                    Outcome(fallback.say(ctx), "rule", "rule(방향 기각: \"${guarded.take(24)}…\")", prompt)
                }
                !special && !DirectionGuard.ok(intentOf(ctx.judgment), guarded) -> {
                    directionRejects++ // 방향 모순/무방향 문장 기각(adr-002 방향 잠금)
                    Outcome(fallback.say(ctx), "rule", "rule(방향 기각: \"${guarded.take(24)}…\")", prompt)
                }
                else -> { usedLlmOnce = true; Outcome(guarded, "nano-prompt", "llm", prompt) }
            }
        } catch (e: Throwable) {
            // 포그라운드 제약(ErrorCode 30)/타임아웃 등 -> 단어 큐 폴백
            Outcome(fallback.say(ctx), "rule", "rule(오류/타임아웃)", prompt)
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
