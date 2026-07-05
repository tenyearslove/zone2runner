package com.zone2runner.app

import com.zone2runner.app.pipeline.HrPredictionLearner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HrPredictionLearnerTest {

    /** df: [hr_now, sus, dHR, pace, slope, spm, elapsed, decoupling]. 잔차모델은 [1,sus,dHR,elapsed] 사용. */
    private fun df(now: Double = 0.5, sus: Double = 0.5, dHR: Double = 0.0, elapsed: Double = 0.0) =
        doubleArrayOf(now, sus, dHR, 6.0, 0.0, 160.0, elapsed, 0.0)

    @Test fun learnsSystematicBias_reducesError() {
        // 기본 NN이 실제보다 항상 0.1(frac) 낮게 예측하는 개인 → 보정이 이를 학습해 오차를 줄여야
        val L = HrPredictionLearner()
        val bias = 0.1
        val actualNow = 0.5
        for (t in 0..200) {
            val d = df(now = actualNow)
            // base 예측 = 실제 - bias (항상 과소예측)
            L.record(t, d, doubleArrayOf(actualNow - bias, actualNow - bias), plannedPace = 6.0)
            L.observe(t, actualNow, actualPace = 6.0)
        }
        val r = L.rmseBpm(150.0)
        assertTrue("갱신 발생", L.updates > 100)
        assertTrue("base60 오차 ~15bpm", r.base60 > 12.0)
        assertTrue("보정이 오차를 크게 줄임", r.corr60 < r.base60 * 0.4)
    }

    @Test fun paceNotMaintained_skipsLearning() {
        // 예측은 페이스 6.0 가정인데 실제 페이스 8.0(2 차이 > 허용) → 학습 제외
        val L = HrPredictionLearner()
        for (t in 0..120) {
            L.record(t, df(), doubleArrayOf(0.4, 0.4), plannedPace = 6.0)
            L.observe(t, 0.5, actualPace = 8.0)
        }
        assertEquals("페이스 안 맞으면 학습 안 함", 0, L.updates)
    }

    @Test fun weightsRoundTrip() {
        val L = HrPredictionLearner()
        for (t in 0..120) { L.record(t, df(), doubleArrayOf(0.4, 0.4), 6.0); L.observe(t, 0.5, 6.0) }
        val w = L.weights()
        assertEquals(8, w.size)
        // 저장된 가중치로 새 학습기를 만들면 동일 보정을 낸다
        val L2 = HrPredictionLearner(w.copyOfRange(0, 4), w.copyOfRange(4, 8))
        assertEquals(L.correct(df(), doubleArrayOf(0.4, 0.4))[1], L2.correct(df(), doubleArrayOf(0.4, 0.4))[1], 1e-9)
    }
}
