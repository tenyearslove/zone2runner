package com.zone2runner.llmverify

import android.os.SystemClock
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout

/**
 * adr-007 검증: S26에서 Gemini Nano(ML Kit Prompt API) 가용성/지연/오프라인 확인.
 * 다운로드 진행률(받은/전체 MB, %) 표시 + 이어받기/백그라운드(DOWNLOADING) 대응.
 */
class GeminiNanoProbe(
    private val log: (String) -> Unit,
    private val speak: (String) -> Unit = {},
) {

    private var totalBytes = 0L

    private fun name(s: Int) = when (s) {
        FeatureStatus.AVAILABLE -> "AVAILABLE"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
        else -> "UNKNOWN($s)"
    }

    private fun mb(b: Long) = "%.1f".format(b / 1_048_576.0)

    private fun logDownload(ds: DownloadStatus) {
        when (ds) {
            is DownloadStatus.DownloadStarted -> {
                totalBytes = ds.bytesToDownload
                log("다운로드 시작: 총 ${mb(totalBytes)}MB")
            }
            is DownloadStatus.DownloadProgress -> {
                val got = ds.totalBytesDownloaded
                if (totalBytes > 0) {
                    val pct = (got * 100.0 / totalBytes).toInt()
                    log("진행: ${mb(got)}/${mb(totalBytes)}MB (${pct}%)")
                } else {
                    log("진행: ${mb(got)}MB 받음")
                }
            }
            is DownloadStatus.DownloadCompleted -> log("다운로드 완료")
            is DownloadStatus.DownloadFailed -> log("다운로드 실패: ${ds.e.message}")
            else -> log("download: $ds")
        }
    }

    suspend fun run() {
        val model = Generation.getClient()
        try {
            var status = model.checkStatus()
            log("FeatureStatus = ${name(status)}")
            if (status == FeatureStatus.UNAVAILABLE) {
                log("→ 이 기기에서 Gemini Nano 사용 불가. 폴백 필요 [adr-007 Plan B/C]")
                return
            }

            // 준비될 때까지 대기
            // - DOWNLOADABLE(최초): download()로 진행률(%) 표시
            // - DOWNLOADING(이어받기/백그라운드): API가 진행률을 안 주므로 상태만 폴링
            var waitedSec = 0
            while (status != FeatureStatus.AVAILABLE) {
                if (status == FeatureStatus.DOWNLOADABLE) {
                    log("다운로드 시작(진행률 표시)...")
                    try {
                        model.download().collect { ds -> logDownload(ds) }
                    } catch (e: Throwable) {
                        log("download flow 종료: ${e.javaClass.simpleName}: ${e.message}")
                    }
                } else { // DOWNLOADING
                    log("백그라운드 다운로드 중 — 진행률 미제공(이어받기). 완료 대기 ${waitedSec}s (Wi-Fi/포그라운드 유지)")
                    delay(5000); waitedSec += 5
                    if (waitedSec >= 600) {
                        log("10분 경과: 여전히 DOWNLOADING. 다운로드가 지연/정체일 수 있음. 나중에 다시 시도.")
                        return
                    }
                }
                status = model.checkStatus()
                log("status = ${name(status)}")
            }

            log("모델 준비 완료(AVAILABLE). warmup...")
            withTimeout(120_000) { model.warmup() }
            log("warmup 완료")

            val prompt =
                "당신은 러닝 코치입니다. 상황: 심박이 Zone 2 상한을 초과했고, 오르막이며, 더운 날씨입니다. " +
                    "한 문장으로 페이스를 낮추라고 자연스럽게 안내하세요."

            var t = SystemClock.elapsedRealtime()
            val cold = withTimeout(60_000) { model.generateContent(prompt) }
            log("cold ${SystemClock.elapsedRealtime() - t}ms → ${cold.candidates.firstOrNull()?.text}")

            t = SystemClock.elapsedRealtime()
            val warm = withTimeout(60_000) { model.generateContent(prompt) }
            val warmText = warm.candidates.firstOrNull()?.text
            log("warm ${SystemClock.elapsedRealtime() - t}ms → $warmText")

            // end-to-end: 생성된 코칭 문장을 실제 음성으로 출력 (TTS)
            if (warmText != null) {
                log("→ TTS로 음성 출력 시도")
                speak(warmText)
            }

            log("=== 통과 기준: 오프라인(비행기모드)에서도 동작 + 웜 지연 ≤ 2~3초 + 음성 출력 정상. ===")
            log("비행기 모드로 전환 후 다시 실행해 오프라인 동작을 확인하세요.")
        } catch (e: Throwable) {
            log("probe 예외: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            model.close()
        }
    }
}
