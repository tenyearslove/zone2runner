package com.zone2runner.app.coaching

import android.content.Context
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizerOptions
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Gemini Nano 기능 모델의 상태 확인/다운로드 단일 책임 모듈(adr-027, SRP).
 * 다운로드는 세션 밖(홈/설정)에서만 트리거하고, 사용 시점 정책(AVAILABLE만 사용,
 * 아니면 무손상 폴백 — adr-026)은 LlmCoach/NanoRewriter/NanoSummarizer에 그대로 둔다.
 * 다운로드 중에도 코칭은 규칙으로 무중단(강건성 불변).
 */
class NanoModelManager(private val context: Context) {

    /** 앱이 쓰는 Nano 기능 3종. 각 기능 모델은 AICore가 기능 단위로 관리한다. */
    enum class Feature(val label: String) {
        REWRITE("코칭 톤 재작성"),
        SUMMARIZE("리포트 요약"),
        PROMPT("자유 표현/설명"),
    }

    enum class State { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

    // 상태/다운로드 전용 클라이언트(옵션 값은 기능 식별용 — 다운로드는 기능 단위라 톤/타입 무관)
    private val rewriter by lazy {
        Rewriting.getClient(
            RewriterOptions.builder(context)
                .setOutputType(RewriterOptions.OutputType.FRIENDLY)
                .setLanguage(RewriterOptions.Language.KOREAN)
                .build()
        )
    }
    private val summarizer by lazy {
        Summarization.getClient(
            SummarizerOptions.builder(context)
                .setInputType(SummarizerOptions.InputType.ARTICLE)
                .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                .setLanguage(SummarizerOptions.Language.KOREAN)
                .build()
        )
    }
    private val prompt by lazy { Generation.getClient() }

    /** 기능 상태 조회. 오류/타임아웃은 UNAVAILABLE로 정직 표기(다운로드 권하지 않음). */
    suspend fun status(f: Feature): State = withContext(Dispatchers.IO) {
        runCatching {
            val code = when (f) {
                Feature.REWRITE -> rewriter.checkFeatureStatus().get(6, TimeUnit.SECONDS)
                Feature.SUMMARIZE -> summarizer.checkFeatureStatus().get(6, TimeUnit.SECONDS)
                Feature.PROMPT -> prompt.checkStatus()
            }
            mapStatus(code)
        }.getOrDefault(State.UNAVAILABLE)
    }

    suspend fun statusAll(): Map<Feature, State> = Feature.entries.associateWith { status(it) }

    /**
     * 기능 모델 다운로드(DOWNLOADABLE일 때 호출). 완료 true / 실패 false.
     * onProgress(downloadedBytes, totalBytes) — totalBytes는 시작 이벤트 전 0일 수 있다.
     */
    suspend fun download(f: Feature, onProgress: (Long, Long) -> Unit): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            when (f) {
                Feature.PROMPT -> {
                    var total = 0L
                    var ok = false
                    prompt.download().collect { st ->
                        when (st) {
                            is DownloadStatus.DownloadStarted -> { total = st.bytesToDownload; onProgress(0, total) }
                            is DownloadStatus.DownloadProgress -> onProgress(st.totalBytesDownloaded, total)
                            is DownloadStatus.DownloadCompleted -> ok = true
                            is DownloadStatus.DownloadFailed -> ok = false
                        }
                    }
                    ok
                }
                Feature.REWRITE -> callbackDownload(onProgress) { cb -> rewriter.downloadFeature(cb) }
                Feature.SUMMARIZE -> callbackDownload(onProgress) { cb -> summarizer.downloadFeature(cb) }
            }
        }.getOrDefault(false)
    }

    /** ListenableFuture+DownloadCallback API(Rewriting/Summarization)를 suspend로 변환. */
    private suspend fun callbackDownload(
        onProgress: (Long, Long) -> Unit,
        start: (DownloadCallback) -> Any,
    ): Boolean = suspendCancellableCoroutine { cont ->
        var total = 0L
        val cb = object : DownloadCallback {
            override fun onDownloadStarted(bytesToDownload: Long) { total = bytesToDownload; onProgress(0, total) }
            override fun onDownloadProgress(totalBytesDownloaded: Long) { onProgress(totalBytesDownloaded, total) }
            override fun onDownloadCompleted() { if (cont.isActive) cont.resume(true) }
            override fun onDownloadFailed(e: GenAiException) { if (cont.isActive) cont.resume(false) }
        }
        runCatching { start(cb) }.onFailure { if (cont.isActive) cont.resume(false) }
    }

    companion object {
        /** ML Kit FeatureStatus 정수 → 상태. 미지의 코드는 UNAVAILABLE(정직 폴백). */
        fun mapStatus(code: Int): State = when (code) {
            FeatureStatus.AVAILABLE -> State.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> State.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> State.DOWNLOADING
            else -> State.UNAVAILABLE
        }

        fun stateLabel(s: State): String = when (s) {
            State.AVAILABLE -> "사용 가능"
            State.DOWNLOADABLE -> "다운로드 필요"
            State.DOWNLOADING -> "다운로드 중…"
            State.UNAVAILABLE -> "이 기기에서 미지원/확인 불가"
        }
    }
}
