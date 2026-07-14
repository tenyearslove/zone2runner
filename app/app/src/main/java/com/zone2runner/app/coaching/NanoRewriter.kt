package com.zone2runner.app.coaching

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.rewriting.Rewriter
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 코칭 문장 톤 재작성(Gemini Nano, ML Kit GenAI Rewriting — adr-026).
 * ★ 내용은 규칙(RuleCoach)이 확정하고, 이 클래스는 그 문장의 '톤'만 바꾼다(adr-002 방향 잠금 강화 —
 * 자유 프롬프트보다 결정론적: 내용이 규칙 문장에서 출발하므로 방향이 새지 않음). 호출부는 DirectionGuard로 재검증.
 * 미가용/실패 시 원문 그대로 반환. 포그라운드 전용. 한국어 지원.
 *
 * 페르소나→톤 매핑(정직한 한계): Rewriting 톤은 고정 집합이라 4페르소나를 완벽히 못 담는다.
 * 친절/다정→FRIENDLY, 차분→PROFESSIONAL, 스파르타→SHORTEN(간결/단호 근사). 그 외 REPHRASE.
 */
class NanoRewriter(context: Context, personaKey: String) {

    private val tone: Int = when (personaKey) {
        "calm" -> RewriterOptions.OutputType.PROFESSIONAL
        "spartan", "strict" -> RewriterOptions.OutputType.SHORTEN
        "friend", "default" -> RewriterOptions.OutputType.FRIENDLY
        else -> RewriterOptions.OutputType.REPHRASE
    }

    private val client: Rewriter by lazy {
        Rewriting.getClient(
            RewriterOptions.builder(context)
                .setOutputType(tone)
                .setLanguage(RewriterOptions.Language.KOREAN)
                .build()
        )
    }
    private var checked = false
    private var available = false

    private suspend fun ready(): Boolean = withContext(Dispatchers.IO) {
        if (checked) return@withContext available
        checked = true
        available = try {
            client.checkFeatureStatus().get(4, TimeUnit.SECONDS) == FeatureStatus.AVAILABLE // AVAILABLE일 때만
        } catch (e: Throwable) { false }
        available
    }

    /** 문장 톤 재작성. 미가용/실패/빈 결과 시 원문 반환(무손상 폴백). */
    suspend fun rewrite(text: String): String {
        if (text.isBlank() || !ready()) return text
        return try {
            withContext(Dispatchers.IO) {
                val res = client.runInference(RewritingRequest.builder(text).build()).get(6, TimeUnit.SECONDS)
                res.results.firstOrNull()?.text?.trim()?.ifBlank { null } ?: text
            }
        } catch (e: Throwable) { text }
    }
}
