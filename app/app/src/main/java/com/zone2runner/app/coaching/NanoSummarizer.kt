package com.zone2runner.app.coaching

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 세션 리포트 요약(Gemini Nano, ML Kit GenAI Summarization — adr-026).
 * 입력 = 리포트가 만든 실제 도출 사실 텍스트(SessionExplainer.facts 등)만. LLM은 표현(요약)만 하고
 * 없는 숫자를 만들지 않는다(입력에 없는 값은 안 나옴). 미가용/실패/입력 과소 시 null → 호출부가 규칙 폴백.
 * 배치(세션 종료 1회), 포그라운드 전용. 한국어 지원.
 */
class NanoSummarizer(context: Context) {

    private val client: Summarizer by lazy {
        Summarization.getClient(
            SummarizerOptions.builder(context)
                .setInputType(SummarizerOptions.InputType.ARTICLE)
                .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                .setLanguage(SummarizerOptions.Language.KOREAN)
                .build()
        )
    }
    private var checked = false
    private var available = false

    private suspend fun ready(): Boolean = withContext(Dispatchers.IO) {
        if (checked) return@withContext available
        checked = true
        available = try {
            client.checkFeatureStatus().get(4, TimeUnit.SECONDS) == FeatureStatus.AVAILABLE // AVAILABLE일 때만(다운로드 트리거 안 함)
        } catch (e: Throwable) { false }
        available
    }

    /** article(실제 사실 텍스트)를 불릿 요약. 미가용/실패/너무 짧으면 null(호출부 폴백). */
    suspend fun summarize(article: String): String? {
        if (article.length < 200 || !ready()) return null // 너무 짧으면 요약 이득 없음
        return try {
            withContext(Dispatchers.IO) {
                client.runInference(SummarizationRequest.builder(article).build()).get(15, TimeUnit.SECONDS).summary
            }?.trim()?.ifBlank { null }
        } catch (e: Throwable) { null }
    }
}
