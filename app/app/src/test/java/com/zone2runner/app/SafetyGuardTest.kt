package com.zone2runner.app

import com.zone2runner.app.pipeline.SafetyGuard
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SafetyGuardTest {

    private val maxHr = 185

    @Test fun belowThreshold_noAlert() {
        val g = SafetyGuard()
        for (t in 0 until 60) assertNull(g.check(t, 150, maxHr)) // 150 < 95%*185≈176
    }

    @Test fun sustainedDanger_altertsAfterHold() {
        val g = SafetyGuard(pctOfMax = 0.95, holdSec = 15, reAlertSec = 30)
        var fired = -1
        for (t in 0 until 20) {
            val a = g.check(t, 180, maxHr) // 180 >= 176
            if (a != null && fired < 0) fired = t
        }
        // 15초 지속 후 발동(t=14에서 overSec=15)
        assert(fired in 13..15) { "발동 시점 예상 15초 근처, got $fired" }
    }

    @Test fun brief_spike_noAlert() {
        val g = SafetyGuard()
        for (t in 0 until 5) assertNull(g.check(t, 182, maxHr)) // 5초만 초과 → 지속 미달
    }

    @Test fun reAlert_throttled() {
        val g = SafetyGuard(holdSec = 15, reAlertSec = 30)
        val alerts = ArrayList<Int>()
        for (t in 0 until 90) g.check(t, 182, maxHr)?.let { alerts += t }
        // 첫 발동(~15) 후 30초 간격 재발동 → 90초 내 2~3회, 매초 아님
        assert(alerts.size in 2..3) { "재경고 스로틀: got ${alerts.size}회 @ $alerts" }
    }

    @Test fun noMaxHr_noAlert() {
        val g = SafetyGuard()
        for (t in 0 until 30) assertNull(g.check(t, 200, 0))
    }

    @Test fun resetsWhenHrDrops() {
        val g = SafetyGuard(holdSec = 15)
        for (t in 0 until 10) g.check(t, 182, maxHr)       // 10초 초과(미발동)
        assertNull(g.check(10, 150, maxHr))                 // 정상으로 하강 → 리셋
        for (t in 11 until 20) assertNull(g.check(t, 182, maxHr)) // 다시 9초만 → 미발동
    }
}
