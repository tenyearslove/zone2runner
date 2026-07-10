package com.zone2runner.app

import com.zone2runner.app.analysis.LinearRegression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinearRegressionTest {

    @Test fun perfectLine_slopeInterceptR2() {
        // y = 2x + 3
        val xs = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0)
        val ys = doubleArrayOf(3.0, 5.0, 7.0, 9.0, 11.0)
        val f = LinearRegression.fit(xs, ys)!!
        assertEquals(2.0, f.slope, 1e-9)
        assertEquals(3.0, f.intercept, 1e-9)
        assertEquals(1.0, f.r2, 1e-9)
        assertEquals(0.0, f.se, 1e-9)
        assertEquals(5, f.n)
    }

    @Test fun noisyLine_r2Below1_seFinite() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0)
        val ys = doubleArrayOf(3.1, 4.9, 7.2, 8.8, 11.1)
        val f = LinearRegression.fit(xs, ys)!!
        assertEquals(2.0, f.slope, 0.2)      // 대략 2
        assertTrue(f.r2 > 0.98 && f.r2 < 1.0)
        assertTrue(f.se.isFinite() && f.se > 0.0)
    }

    @Test fun flatLine_zeroSlope() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0, 3.0)
        val ys = doubleArrayOf(5.0, 5.0, 5.0, 5.0)
        val f = LinearRegression.fit(xs, ys)!!
        assertEquals(0.0, f.slope, 1e-9)
    }

    @Test fun degenerate_nullCases() {
        assertNull(LinearRegression.fit(doubleArrayOf(1.0), doubleArrayOf(2.0)))       // n<2
        assertNull(LinearRegression.fit(doubleArrayOf(2.0, 2.0, 2.0), doubleArrayOf(1.0, 2.0, 3.0))) // x 변동 0
    }

    @Test fun twoPoints_seNaN_slopeExact() {
        val f = LinearRegression.fit(doubleArrayOf(0.0, 2.0), doubleArrayOf(1.0, 5.0))!!
        assertEquals(2.0, f.slope, 1e-9)   // (5-1)/(2-0)
        assertTrue(f.se.isNaN())           // n<3 → SE 정의 안 됨
    }
}
