package com.zone2runner.app

import com.zone2runner.app.analysis.NoiseFloor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseFloorTest {

    @Test fun constantInput_meanConverges_sigmaSmall() {
        val f = NoiseFloor(alpha = 0.2)
        repeat(50) { f.observe(5.0) }
        assertEquals(5.0, f.mean(), 1e-6)
        assertTrue(f.sigma() < 1e-3)
        assertEquals(5.0, f.threshold(2.0), 1e-3)  // m + k·0
    }

    @Test fun varyingInput_sigmaPositive_thresholdAboveMean() {
        val f = NoiseFloor(alpha = 0.2)
        val xs = listOf(2.0, 6.0, 3.0, 7.0, 2.0, 8.0, 4.0, 6.0, 3.0, 7.0)
        repeat(5) { xs.forEach { f.observe(it) } }
        assertTrue(f.sigma() > 0.5)
        assertTrue(f.threshold(2.0) > f.mean())
    }

    @Test fun ready_afterEnoughObservations() {
        val f = NoiseFloor()
        assertFalse(f.ready(5))
        repeat(5) { f.observe(3.0) }
        assertTrue(f.ready(5))
    }

    @Test fun seededState_restores() {
        val f = NoiseFloor(alpha = 0.2, seedMean = 4.0, seedVar = 1.0, seedCount = 10)
        assertEquals(4.0, f.mean(), 1e-9)
        assertEquals(1.0, f.sigma(), 1e-9)
        assertTrue(f.ready(5))
    }
}
