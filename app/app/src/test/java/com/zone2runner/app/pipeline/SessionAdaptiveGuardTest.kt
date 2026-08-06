package com.zone2runner.app.pipeline

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * QA4 강건성: 세션 적응형 가드 테스트
 *
 * AC-T2-2: 일시적 이상값 시나리오 (점프 1초, 기대 100% 일치)
 * AC-T2-3: 지속적 정상 변화 시나리오 (선형 상승 5초, 기대 100% 일치)
 * AC-T2-4: 혼합 시나리오 (점프+변화+점프, 기대 100% 일치)
 */
class SessionAdaptiveGuardTest {

    /**
     * AC-T2-2: 일시적 이상값 시나리오
     */
    @Test
    fun testTransientOutlier_1SecondJump() {
        val guard = SessionAdaptiveGuard()
        var currentTime = 0L

        // 안정 구간 (0~5초)
        val stableHrs = listOf(118, 120, 122, 120, 119)
        stableHrs.forEachIndexed { idx, hr ->
            val result = guard.process(hr, currentTime)
            assertEquals("샘플[$idx] 기대=$hr 실제=$result", hr, result)
            currentTime += 1000
        }

        val medianBeforeJump = guard.getCurrentMedian()

        // 점프 (5~6초, +35 bpm)
        val jumpResult = guard.process(155, currentTime)
        currentTime += 1000
        assertEquals(119, jumpResult)

        // 복원 (6~11초)
        val recoveryHrs = listOf(118, 121, 120, 122, 119)
        recoveryHrs.forEach { hr ->
            val result = guard.process(hr, currentTime)
            assertEquals(hr, result)
            currentTime += 1000
        }

        val medianAfterRecovery = guard.getCurrentMedian()
        println("점프 전 중앙값: $medianBeforeJump, 복원 후: $medianAfterRecovery")
        assertTrue(medianBeforeJump != null && medianAfterRecovery != null)
    }

    /**
     * AC-T2-3: 지속적 정상 변화 시나리오
     */
    @Test
    fun testSustainedChange_LinearRiseOver5Seconds() {
        val guard = SessionAdaptiveGuard()
        var currentTime = 0L

        // 안정 구간 (0~5초)
        val stableStart = listOf(118, 120, 122, 121, 119)
        stableStart.forEach { hr ->
            val result = guard.process(hr, currentTime)
            assertEquals(hr, result)
            currentTime += 1000
        }

        val medianStart = guard.getCurrentMedian()

        // 선형 상승 (5~10초, 120→140)
        val riseHrs = listOf(124, 128, 132, 136, 140)
        riseHrs.forEach { hr ->
            val result = guard.process(hr, currentTime)
            assertEquals(hr, result)
            currentTime += 1000
        }

        val medianMidway = guard.getCurrentMedian()

        // 복원 (10~13초)
        val stableEnd = listOf(140, 141, 139)
        stableEnd.forEach { hr ->
            val result = guard.process(hr, currentTime)
            assertEquals(hr, result)
            currentTime += 1000
        }

        val medianEnd = guard.getCurrentMedian()

        println("시작: $medianStart, 중간: $medianMidway, 끝: $medianEnd")
        assertTrue(medianStart != null && medianMidway != null && medianEnd != null)
    }

    /**
     * AC-T2-4: 혼합 시나리오 (점프 + 변화 + 점프)
     */
    @Test
    fun testMixedScenario_JumpPlusChangeAndJump() {
        val guard = SessionAdaptiveGuard()
        var currentTime = 0L

        // 구간 A (0~5초, 120±2)
        val sectionA = listOf(118, 120, 122, 120, 119)
        sectionA.forEach { hr ->
            val result = guard.process(hr, currentTime)
            assertEquals(hr, result)
            currentTime += 1000
        }

        val medianA = guard.getCurrentMedian()
        println("구간 A 중앙값: $medianA")

        // 첫 번째 점프 (5~6초, +35 bpm)
        val jumpResult1 = guard.process(155, currentTime)
        currentTime += 1000
        assertEquals(119, jumpResult1)

        // 구간 B (6~11초, 125±2, 5초 지속)
        val sectionB = listOf(123, 125, 127, 125, 124)
        sectionB.forEach { hr ->
            val result = guard.process(hr, currentTime)
            assertEquals(hr, result)
            currentTime += 1000
        }

        val medianB = guard.getCurrentMedian()
        println("구간 B 중앙값: $medianB")

        // 두 번째 점프 (11~12초, −40 bpm)
        val jumpResult2 = guard.process(85, currentTime)
        currentTime += 1000
        assertEquals(124, jumpResult2)

        // 복원 (12~15초, 125±2)
        val recovery = listOf(126, 124, 125)
        recovery.forEach { hr ->
            val result = guard.process(hr, currentTime)
            assertEquals(hr, result)
            currentTime += 1000
        }

        val medianFinal = guard.getCurrentMedian()
        println("최종 중앙값: $medianFinal")

        assertTrue(medianA != null && medianB != null && medianFinal != null)
    }

    /**
     * 보조 테스트: 윈도우 관리 확인
     */
    @Test
    fun testWindowManagement_OldValuesRemoved() {
        val guard = SessionAdaptiveGuard()
        var currentTime = 0L

        // 0~5초: 5개 샘플
        repeat(5) {
            guard.process(120, currentTime)
            currentTime += 1000
        }
        assertEquals(5, guard.getWindowSize())

        // 5~10초: 5개 더 추가
        repeat(5) {
            guard.process(120, currentTime)
            currentTime += 1000
        }
        assertEquals(10, guard.getWindowSize())

        // 10~15초: 5개 더 추가 시 첫 5개는 제거되어야 함
        repeat(5) {
            guard.process(120, currentTime)
            currentTime += 1000
        }
        println("최종 윈도우 크기: ${guard.getWindowSize()}")
        assertTrue(guard.getWindowSize() in 8..12)
    }
}
