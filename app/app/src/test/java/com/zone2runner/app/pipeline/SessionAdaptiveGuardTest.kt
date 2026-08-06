package com.zone2runner.app.pipeline

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * QA4 강건성: 세션 적응형 가드 시나리오 검증
 *
 * AC-T2-2: 일시적 이상값 (1초 점프 후 복원) → 대부분 기각
 * AC-T2-3: 지속적 변화 (5초 선형 상승) → 대부분 수용
 * AC-T2-4: 혼합 (점프 + 변화 + 점프) → 점프는 기각, 변화는 수용
 *
 * 검증 방식: 정확한 수치 대신 "기각/수용 비율" 또는 "중앙값 변화 범위"로 판정
 */
class SessionAdaptiveGuardTest {

    /**
     * AC-T2-2: 일시적 이상값 시나리오
     *
     * 입력: 5초 정상(120) → 1초 점프(+40) → 5초 정상(120)
     * 기대: 점프는 기각되고 다시 120으로 복원
     */
    @Test
    fun testTransientOutlier_ShouldRejectJump() {
        val guard = SessionAdaptiveGuard()
        var time = 0L

        // 5초 정상 구간
        repeat(5) {
            val result = guard.process(120, time)
            // 정상값은 수용
            assertTrue("정상값은 수용되어야 함", result in 118..122)
            time += 1000
        }

        val medianBefore = guard.getCurrentMedian()
        val countBefore = guard.getWindowSize()
        println("점프 전: 중앙값=$medianBefore, 샘플=$countBefore")

        // 1초 점프 (160 = 120 + 40)
        val jumpResult = guard.process(160, time)
        time += 1000
        // 충분한 이상값이므로 기각되어야 함
        assertTrue("점프는 기각되어야 함", jumpResult in 118..122)

        // 5초 복원
        val validAfterJump = mutableListOf<Int>()
        repeat(5) {
            val result = guard.process(120, time)
            validAfterJump.add(result)
            time += 1000
        }

        val medianAfter = guard.getCurrentMedian()
        println("복원 후: 중앙값=$medianAfter, 점프값 수용 비율=${validAfterJump.count { it == 160 }}/5")

        // 최종 중앙값은 120 근처 유지
        assertTrue("중앙값 120 근처 유지", medianAfter != null && medianAfter!! in 115.0..125.0)
    }

    /**
     * AC-T2-3: 지속적 정상 변화 시나리오
     *
     * 입력: 5초 정상(120) → 천천한 상승(매초 +3 bpm, 총 15 bpm) → 5초 정상(135)
     * 기대: 중앙값이 점진적으로 상승 (일부 값은 기각될 수 있지만, 트렌드는 명확)
     */
    @Test
    fun testSustainedChange_ShouldShowUpwardTrend() {
        val guard = SessionAdaptiveGuard()
        var time = 0L

        // 5초 정상 구간 (120)
        repeat(5) {
            val result = guard.process(120, time)
            time += 1000
        }

        val medianStart = guard.getCurrentMedian()

        // 천천한 상승 (매초 3bpm, 총 15초)
        val rises = listOf(
            123, 126, 129, 132, 135,  // 5초
            135, 135, 135, 135, 135   // 5초
        )
        val accepted = mutableListOf<Int>()
        rises.forEach { hr ->
            val result = guard.process(hr, time)
            accepted.add(result)
            time += 1000
        }

        val medianMid = guard.getCurrentMedian()

        // 5초 안정 (135)
        repeat(5) {
            val result = guard.process(135, time)
            time += 1000
        }

        val medianEnd = guard.getCurrentMedian()

        println("중앙값 변화: $medianStart → $medianMid → $medianEnd")
        println("수용된 값: ${accepted.take(5)} (상승), ${accepted.drop(5)} (안정)")

        // 중앙값이 상승 추세를 보여야 함 (최소 5 bpm 이상)
        assertTrue("중앙값 상승 추세",
            medianStart != null && medianEnd != null &&
            medianStart!! < medianEnd!! &&
            medianEnd - medianStart >= 5.0)
    }

    /**
     * AC-T2-4: 혼합 시나리오 (점프 + 변화 + 점프)
     *
     * 입력: 5초 정상(120) → 1초 점프(+35) → 5초 천천한 변화(120→130) → 1초 점프(-35) → 5초 정상(130)
     * 기대: 점프는 대부분 기각, 변화는 부분 수용, 최종 중앙값은 상승
     */
    @Test
    fun testMixedScenario_ShouldRejectJumpsPartiallyAcceptChange() {
        val guard = SessionAdaptiveGuard()
        var time = 0L

        // 5초 정상 (120)
        repeat(5) {
            val result = guard.process(120, time)
            time += 1000
        }
        val medianStart = guard.getCurrentMedian()

        // 첫 점프 (+35) — 기각되어야 함
        val jump1Result = guard.process(155, time)
        time += 1000
        assertTrue("첫 점프 기각", jump1Result in 115..125)

        // 천천한 변화 (120→130, 매초 2bpm)
        val changes = listOf(122, 124, 126, 128, 130)
        val changeResults = mutableListOf<Int>()
        changes.forEach { hr ->
            val result = guard.process(hr, time)
            changeResults.add(result)
            time += 1000
        }

        val medianMid = guard.getCurrentMedian()

        // 두 번째 점프 (-35) — 기각되어야 함
        val jump2Result = guard.process(95, time)
        time += 1000
        assertTrue("두 번째 점프 기각", jump2Result in 120..140)

        // 5초 정상 (130)
        repeat(5) {
            val result = guard.process(130, time)
            time += 1000
        }

        val medianFinal = guard.getCurrentMedian()
        println("중앙값: $medianStart → $medianMid → $medianFinal")
        println("변화값 수용 비율: ${changeResults.count { it >= 120 }}/5")

        // 최종 중앙값이 시작보다 높아야 함
        assertTrue("중앙값 상승",
            medianStart != null && medianFinal != null &&
            medianFinal!! > medianStart!! &&
            medianFinal - medianStart >= 3.0)
    }

    /**
     * 윈도우 관리: 10초 이상된 값 제거
     */
    @Test
    fun testWindowManagement_OldValuesExpire() {
        val guard = SessionAdaptiveGuard()
        var time = 0L

        // 0~5초: 5개 샘플
        repeat(5) {
            guard.process(120, time)
            time += 1000
        }
        assertEquals("5초 후 샘플 5개", 5, guard.getWindowSize())

        // 5~10초: 5개 더
        repeat(5) {
            guard.process(120, time)
            time += 1000
        }
        assertEquals("10초 후 샘플 10개", 10, guard.getWindowSize())

        // 10~15초: 첫 5개(0~5초)는 제거, 새 5개 추가
        repeat(5) {
            guard.process(120, time)
            time += 1000
        }
        // 윈도우는 최대 10초분, 약 10개 샘플 유지
        val finalSize = guard.getWindowSize()
        println("15초 후 윈도우 크기: $finalSize (기대: 8~12)")
        assertTrue("윈도우 크기 10초분", finalSize in 8..12)
    }
}
