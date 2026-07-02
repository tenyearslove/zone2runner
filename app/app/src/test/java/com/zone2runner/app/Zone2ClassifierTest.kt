package com.zone2runner.app

import com.zone2runner.app.domain.ZoneJudgment
import com.zone2runner.app.pipeline.Zone2Classifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 온디바이스 MLP 순전파(순수 Kotlin) 검증.
 *  - 손수 만든 단층 모델로 argmax가 정확한지(수학 검증).
 *  - export_model.py가 뽑은 실제 assets/zone2_mlp.json을 로드해 추론/스케일러/메트릭 검증.
 */
class Zone2ClassifierTest {

    @Test fun forwardPass_singleLayer_argmaxIsCorrect() {
        // features 2개, 단층(=로짓). w[out][in], 스케일러 항등.
        val json = """
        {"features":["a","b"],"labels":["below","in","above"],
         "scaler_mean":[0,0],"scaler_scale":[1,1],
         "layers":[{"w":[[1,0],[0,1],[-1,-1]],"b":[0,0,0]}],
         "hidden_activation":"relu","metrics":{}}
        """.trimIndent()
        val clf = Zone2Classifier.fromJsonString(json)
        // x=[2,1] -> logits=[2,1,-3] -> argmax=0 -> BELOW
        assertEquals(ZoneJudgment.BELOW, clf.classify(doubleArrayOf(2.0, 1.0)).judgment)
        // x=[0,5] -> logits=[0,5,-5] -> argmax=1 -> IN
        assertEquals(ZoneJudgment.IN, clf.classify(doubleArrayOf(0.0, 5.0)).judgment)
        // 확률 합≈1
        val probs = clf.classify(doubleArrayOf(2.0, 1.0)).probs
        assertEquals(1.0f, probs.sum(), 1e-4f)
    }

    @Test fun realExportedModel_loadsAndInfersValidly() {
        val f = findAsset("zone2_mlp.json") ?: run {
            println("실 모델 파일을 찾지 못해 스킵"); return
        }
        val clf = Zone2Classifier.fromJsonString(f.readText())
        assertEquals("입력 특징 7종(spec-006)", 7, clf.features.size)
        // 명확히 높은 HR(초과) / 낮은 HR(미달) 특징에서 방향이 맞는지(대략)
        // feat=[hr_norm_u, hr_norm_l, dHR, pace, spm, decoupling, slope]
        val high = doubleArrayOf(0.15, 0.25, 0.2, 6.0, 175.0, 0.05, 0.0)  // 상한 크게 초과
        val low = doubleArrayOf(-0.20, -0.10, -0.2, 7.0, 165.0, 0.0, 0.0) // 하한 미달
        val jHigh = clf.classify(high).judgment
        val jLow = clf.classify(low).judgment
        println("MLP high->$jHigh, low->$jLow, metrics=${clf.metrics}")
        assertTrue("초과 특징이 미달로 분류되면 안 됨", jHigh != ZoneJudgment.BELOW)
        assertTrue("미달 특징이 초과로 분류되면 안 됨", jLow != ZoneJudgment.ABOVE)
        // export 시 기록한 메트릭 존재
        assertTrue(clf.metrics.containsKey("mlp_acc"))
    }

    @Test fun ruleFallback_matchesThresholds() {
        // feat[0]=hr_norm_u, feat[1]=hr_norm_l
        assertEquals(ZoneJudgment.BELOW, Zone2Classifier.ruleClassify(doubleArrayOf(-0.1, -0.05, 0.0, 6.0, 170.0, 0.0, 0.0)))
        assertEquals(ZoneJudgment.ABOVE, Zone2Classifier.ruleClassify(doubleArrayOf(0.1, 0.2, 0.0, 6.0, 170.0, 0.0, 0.0)))
        assertEquals(ZoneJudgment.IN, Zone2Classifier.ruleClassify(doubleArrayOf(-0.05, 0.05, 0.0, 6.0, 170.0, 0.0, 0.0)))
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
