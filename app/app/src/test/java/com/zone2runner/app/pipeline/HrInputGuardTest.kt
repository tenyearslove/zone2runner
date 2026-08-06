package com.zone2runner.app.pipeline

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * QA4 Robustness: HrInputGuard integration test (Tier 1 + Tier 2)
 */
class HrInputGuardTest {

    /**
     * AC-T1-1: Tier 1 rejects out-of-range
     */
    @Test
    fun testTier1_RejectsOutOfRange() {
        val guard = HrInputGuard()
        var time = 0L

        // First, normal value
        val r1 = guard.process(120, time)
        assertEquals("Normal in-range", 120, r1)
        time += 1000

        // Out of range (below 40)
        val r2 = guard.process(30, time)
        assertEquals("Below 40 rejected -> fallback 120", 120, r2)
        time += 1000

        // Out of range (above 220)
        val r3 = guard.process(250, time)
        assertEquals("Above 220 rejected -> fallback 120", 120, r3)
        time += 1000
    }

    /**
     * AC-T1-2 + AC-T2: Basic Tier 2 window function
     */
    @Test
    fun testTier2_WindowWorks() {
        val guard = HrInputGuard()
        var time = 0L

        // Build up window
        repeat(5) {
            guard.process(120, time)
            time += 1000
        }

        assertEquals("Window has 5 samples", 5, guard.getWindowSize())

        // 5 more seconds
        repeat(5) {
            guard.process(120, time)
            time += 1000
        }

        assertEquals("Window has 10 samples", 10, guard.getWindowSize())

        // 5 more seconds -> oldest should expire
        repeat(5) {
            guard.process(120, time)
            time += 1000
        }

        val finalSize = guard.getWindowSize()
        assertTrue("Window expired old values", finalSize in 8..12)
    }
}
