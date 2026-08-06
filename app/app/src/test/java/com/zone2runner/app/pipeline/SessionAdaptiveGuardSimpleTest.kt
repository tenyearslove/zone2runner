package com.zone2runner.app.pipeline

import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * SessionAdaptiveGuard 단위 테스트 — 극도로 단순한 케이스
 */
class SessionAdaptiveGuardSimpleTest {

    @Test
    fun test_sample1_expected_120() {
        val guard = SessionAdaptiveGuard()
        val result = guard.process(120, 0L)
        System.err.println("Test 1: 입력=120, 출력=$result, 기대=120")
        assertEquals("첫 샘플", 120, result)
    }

    @Test
    fun test_sample2_expected_122() {
        val guard = SessionAdaptiveGuard()
        guard.process(120, 0L)
        val result = guard.process(122, 1000L)
        System.err.println("Test 2: 입력=122, 출력=$result, 기대=122")
        assertEquals("두 번째 정상값", 122, result)
    }

    @Test
    fun test_three_stable_samples() {
        val guard = SessionAdaptiveGuard()
        val r1 = guard.process(120, 0L)
        assertEquals("첫째", 120, r1)

        val r2 = guard.process(121, 1000L)
        assertEquals("둘째", 121, r2)

        val r3 = guard.process(119, 2000L)
        assertEquals("셋째", 119, r3)
    }

    @Test
    fun test_obvious_outlier_immediately_after_stable() {
        val guard = SessionAdaptiveGuard()

        // 5개 정상값으로 윈도우 채우기
        for (i in 0..4) {
            val result = guard.process(120, (i * 1000).toLong())
            assertEquals("정상값[$i]", 120, result)
        }

        System.err.println("윈도우: ${guard.getWindowSize()}, 중앙값: ${guard.getCurrentMedian()}")

        // 명백한 점프: 160 (120 + 40)
        val jumpResult = guard.process(160, 5000L)
        System.err.println("점프: 입력=160, 출력=$jumpResult, 기대=120 (직전값)")
        // 160은 충분히 멀어서 기각되고 직전값 120이 반환되어야 함
        assertEquals("이상값은 기각", 120, jumpResult)
    }
}
