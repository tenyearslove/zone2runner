package com.zone2runner.app.pipeline

import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * SessionAdaptiveGuard 로직 검증 — 간단한 케이스
 */
class SessionAdaptiveGuardDebugTest {

    @Test
    fun testFirstSample() {
        val guard = SessionAdaptiveGuard()
        val result = guard.process(120, 0L)
        println("첫 샘플 입력: 120, 출력: $result, 기대: 120")
        assertEquals(120, result)
    }

    @Test
    fun testTwoNormalSamples() {
        val guard = SessionAdaptiveGuard()
        val r1 = guard.process(120, 0L)
        println("1번째: 입력=120, 출력=$r1")
        assertEquals(120, r1)

        val r2 = guard.process(122, 1000L)
        println("2번째: 입력=122, 출력=$r2")
        assertEquals(122, r2)
    }

    @Test
    fun testObviousOutlier() {
        val guard = SessionAdaptiveGuard()
        var currentTime = 0L

        // 안정 구간 (5개, 120 근처)
        repeat(5) {
            val result = guard.process(120, currentTime)
            currentTime += 1000
            println("안정[$it]: 입력=120, 출력=$result")
        }

        println("\n===== 점프 시작 =====")
        // 현재 중앙값은 120 근처, IQR은 작음
        val median = guard.getCurrentMedian()
        println("점프 전 중앙값: $median, 윈도우: ${guard.getWindowSize()}")

        // 점프 (40 bpm 상승)
        val jumpResult = guard.process(160, currentTime)
        currentTime += 1000
        println("점프: 입력=160, 출력=$jumpResult, 직전값=${guard.getLastValidHr()}")

        // 원래대로 돌아옴
        val recoveryResult = guard.process(120, currentTime)
        println("복원: 입력=120, 출력=$recoveryResult")
    }
}
