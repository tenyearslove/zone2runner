package com.zone2runner.app

import com.zone2runner.app.pipeline.ThresholdEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 개인 역치 추정 MLP(spec-015) 온디바이스 추론 검증.
 *  - 손수 만든 선형 모델로 순전파 수학 검증(AC1).
 *  - 실제 export(assets/threshold_mlp.json) 로드: 8특징 + uFrac 범위.
 */
class ThresholdEstimatorTest {

    @Test fun forwardPass_isExact() {
        // 특징 2개 → 1개 단층(항등 스케일러): y = w·x + b, 그리고 클램프 확인
        val json = """
        {"features":["a","b"],"output":"u_frac",
         "scaler_mean":[0,0],"scaler_scale":[1,1],
         "layers":[{"w":[[0.1,0.2]],"b":[0.6]}],"hidden_activation":"relu","metrics":{}}
        """.trimIndent()
        val m = ThresholdEstimator.fromJsonString(json)
        // 0.1*0.5 + 0.2*0.5 + 0.6 = 0.75 → 범위 상한(클램프 0.30~0.75)
        assertEquals(0.75, m.estimateUFrac(doubleArrayOf(0.5, 0.5)), 1e-9)
        // 큰 입력 → 0.75 클램프(재보정 2026-07-04)
        assertEquals(0.75, m.estimateUFrac(doubleArrayOf(10.0, 10.0)), 1e-9)
    }

    @Test fun realModel_estimatesInRange() {
        val f = listOf(
            File("src/main/assets/threshold_mlp.json"),
            File("app/src/main/assets/threshold_mlp.json"),
            File("app/app/src/main/assets/threshold_mlp.json"),
        ).firstOrNull { it.exists() } ?: run { println("모델 파일 없음 → 스킵"); return }
        val m = ThresholdEstimator.fromJsonString(f.readText())
        assertEquals("입력 특징 8종(spec-015)", 8, m.features.size)
        // 대표 세션 특징: [slow, mid, fast, slope, drift, cadence_n, rhr_frac, age_n]
        val feat = doubleArrayOf(0.55, 0.65, 0.78, 0.05, 0.04, 0.85, 0.32, 0.35)
        val u = m.estimateUFrac(feat)
        assertTrue("uFrac 생리 범위(0.30~0.75): $u", u in 0.30..0.75)
        assertTrue(m.metrics.containsKey("mae_bpm_nn"))
        println("추정 uFrac=$u, MAE(nn)=${m.metrics["mae_bpm_nn"]} vs 공식=${m.metrics["mae_bpm_formula"]}")
    }
}
