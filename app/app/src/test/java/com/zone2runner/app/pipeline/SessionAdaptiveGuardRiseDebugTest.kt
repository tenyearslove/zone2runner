package com.zone2runner.app.pipeline

import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * 선형 상승 시나리오 디버그
 */
class SessionAdaptiveGuardRiseDebugTest {

    @Test
    fun debugRiseScenario() {
        val guard = SessionAdaptiveGuard()
        var time = 0L

        // 5초 정상 (120)
        println("\n=== 정상 구간 (120) ===")
        repeat(5) {
            val result = guard.process(120, time)
            println("[$it] 입력=120, 출력=$result")
            time += 1000
        }

        val medianStart = guard.getCurrentMedian()
        println("중앙값: $medianStart, 윈도우: ${guard.getWindowSize()}")

        // 선형 상승 (120→140)
        println("\n=== 상승 구간 (124→140) ===")
        val rises = listOf(124, 128, 132, 136, 140)
        rises.forEachIndexed { idx, hr ->
            val result = guard.process(hr, time)
            val median = guard.getCurrentMedian()
            println("[$idx] 입력=$hr, 출력=$result, 중앙값=$median")
            time += 1000
        }

        val medianMid = guard.getCurrentMedian()
        println("최종 중앙값: $medianMid, 윈도우: ${guard.getWindowSize()}")
    }
}
