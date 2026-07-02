package com.zone2runner.llmverify

import android.os.SystemClock
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.flow.collect

/**
 * adr-007 검증: S26 Ultra에서 Gemini Nano(ML Kit Prompt API)가 실제로 되는지 확인.
 * - 가용성(FeatureStatus) 조회
 * - 필요 시 모델 다운로드
 * - 코칭형 프롬프트로 텍스트 생성 + 지연(콜드/웜) 측정
 */
class GeminiNanoProbe(private val log: (String) -> Unit) {

    suspend fun run() {
        val model = Generation.getClient()
        try {
            val status = model.checkStatus()
            log(
                "FeatureStatus=$status  " +
                    "(UNAVAILABLE=${FeatureStatus.UNAVAILABLE}, DOWNLOADABLE=${FeatureStatus.DOWNLOADABLE}, " +
                    "DOWNLOADING=${FeatureStatus.DOWNLOADING}, AVAILABLE=${FeatureStatus.AVAILABLE})"
            )

            if (status == FeatureStatus.UNAVAILABLE) {
                log("→ 이 기기에서 Gemini Nano(AICore) 사용 불가. 폴백(자체탑재/서버) 필요. [adr-007 Plan B/C]")
                return
            }
            if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
                log("모델 다운로드 시작...")
                model.download().collect { ds -> log("download: $ds") }
                log("다운로드 완료")
            }

            log("warmup...")
            model.warmup()

            val prompt =
                "당신은 러닝 코치입니다. 상황: 심박이 Zone 2 상한을 초과했고, 오르막이며, 더운 날씨입니다. " +
                    "한 문장으로 페이스를 낮추라고 자연스럽게 안내하세요."

            var t = SystemClock.elapsedRealtime()
            val cold = model.generateContent(prompt)
            log("cold ${SystemClock.elapsedRealtime() - t}ms → ${cold.candidates.firstOrNull()?.text}")

            t = SystemClock.elapsedRealtime()
            val warm = model.generateContent(prompt)
            log("warm ${SystemClock.elapsedRealtime() - t}ms → ${warm.candidates.firstOrNull()?.text}")

            log("=== 통과 기준: 오프라인(비행기모드)에서도 동작 + 웜 지연 ≤ 2~3초(TTS 포함 5초 예산). ===")
            log("비행기 모드로 전환 후 다시 실행해 오프라인 동작을 확인하세요.")
        } finally {
            model.close()
        }
    }
}
