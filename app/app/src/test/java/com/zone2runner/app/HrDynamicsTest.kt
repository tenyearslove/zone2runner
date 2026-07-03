package com.zone2runner.app

import com.zone2runner.app.pipeline.HrDynamics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 심박 동역학 모델(spec-014) 온디바이스 추론 검증.
 *  - 손수 만든 선형 모델로 회귀 순전파 수학 검증(AC1).
 *  - 실제 export(assets/hr_dynamics.json) 로드: 예측 생리 범위 + 페이스 역질의 방향성(AC3).
 */
class HrDynamicsTest {

    @Test fun forwardPass_linearModel_isExact() {
        // 특징 2개 -> 출력 2개 단층(항등 스케일러): y = Wx + b
        val json = """
        {"features":["a","b"],"horizons_sec":[30,60],
         "scaler_mean":[0,0],"scaler_scale":[1,1],
         "layers":[{"w":[[1.0,0.5],[0.2,1.0]],"b":[0.1,-0.1]}],
         "hidden_activation":"relu","metrics":{}}
        """.trimIndent()
        val m = HrDynamics.fromJsonString(json)
        val y = m.predictFrac(doubleArrayOf(2.0, 4.0))
        assertEquals(2.0 * 1.0 + 4.0 * 0.5 + 0.1, y[0], 1e-9)
        assertEquals(2.0 * 0.2 + 4.0 * 1.0 - 0.1, y[1], 1e-9)
    }

    @Test fun realExportedModel_predictsPhysiologicalRange_andPaceDirection() {
        val f = findAsset("hr_dynamics.json") ?: run {
            println("실 모델 파일을 찾지 못해 스킵"); return
        }
        val m = HrDynamics.fromJsonString(f.readText())
        assertEquals("입력 특징 8종(spec-014)", 8, m.features.size)
        assertEquals(listOf(30, 60), m.horizonsSec)

        // Zone2 부근 정상 상태: [hr_now, hr_sus, dHR, pace, slope, spm, elapsed, decoupling]
        val feat = doubleArrayOf(0.65, 0.64, 0.0, 6.5, 0.0, 170.0, 10.0, 0.05)
        val y = m.predictFrac(feat)
        assertTrue("30초 예측 생리 범위(HRR 0.2~1.0): ${y[0]}", y[0] in 0.2..1.0)
        assertTrue("60초 예측 생리 범위: ${y[1]}", y[1] in 0.2..1.0)

        // AC3 방향성: 페이스를 늦추면(값 증가) 예측 심박이 내려가야 함
        val fast = feat.copyOf().also { it[3] = 5.0 }
        val slow = feat.copyOf().also { it[3] = 9.0 }
        val hrFast = m.predictFrac(fast)[1]
        val hrSlow = m.predictFrac(slow)[1]
        println("pace 5.0 -> frac ${"%.3f".format(hrFast)}, pace 9.0 -> frac ${"%.3f".format(hrSlow)}")
        assertTrue("빠른 페이스가 더 높은 심박을 예측해야 함", hrFast > hrSlow)

        // 역질의: 추천 페이스가 스윕 범위 안이고, 추천 페이스의 예측이 밴드에 근접
        val rec = m.recommendPace(feat, loFrac = 0.60, hiFrac = 0.70)
        assertTrue("추천 페이스 범위: $rec", rec in HrDynamics.PACE_MIN..HrDynamics.PACE_MAX)
        val atRec = m.predictFrac(feat.copyOf().also { it[3] = rec })[1]
        assertTrue("추천 페이스의 60초 예측이 밴드 부근이어야: $atRec", Math.abs(atRec - 0.65) < 0.10)
        assertTrue(m.metrics.containsKey("rmse_bpm_60"))
    }

    private fun findAsset(name: String): File? {
        val candidates = listOf(
            File("src/main/assets/$name"),
            File("app/src/main/assets/$name"),
            File("app/app/src/main/assets/$name"),
        )
        return candidates.firstOrNull { it.exists() }
    }
}
