package com.zone2runner.app

import com.zone2runner.app.sensor.MockConfig
import com.zone2runner.app.sensor.MockRunSource
import org.junit.Assert.assertTrue
import org.junit.Test

/** 가짜 라이브 소스가 지정한 심박/속도 범위를 지키고 GPS가 실제로 이동하는지 검증(QA 테스트 가능성). */
class MockRunSourceTest {

    @Test fun samples_stayInConfiguredRanges_andGpsMoves() {
        val cfg = MockConfig(hrMin = 120, hrMax = 150, speedMinKmh = 9.0, speedMaxKmh = 12.0)
        val src = MockRunSource(cfg, seed = 42L)

        val paceFast = 60.0 / cfg.speedMaxKmh // 빠를 때(작은 값)
        val paceSlow = 60.0 / cfg.speedMinKmh // 느릴 때(큰 값)

        val first = src.nextSample(0)
        var prevLat = first.lat; var prevLon = first.lon
        var totalMove = 0.0
        for (t in 1..600) {
            val s = src.nextSample(t)
            assertTrue("HR 범위: ${s.hr}", s.hr in cfg.hrMin..cfg.hrMax)
            assertTrue("페이스 범위: ${s.paceMinKm}", s.paceMinKm in (paceFast - 0.05)..(paceSlow + 0.05))
            assertTrue("케이던스 범위: ${s.spm}", s.spm in 150..200)
            totalMove += Math.abs(s.lat - prevLat) + Math.abs(s.lon - prevLon)
            prevLat = s.lat; prevLon = s.lon
        }
        assertTrue("GPS가 누적 이동해야 함", totalMove > 1e-3)
    }

    @Test fun presets_areValidRanges() {
        for ((name, cfg) in MockConfig.PRESETS) {
            assertTrue("$name: HR min<max", cfg.hrMin < cfg.hrMax)
            assertTrue("$name: 속도 min<max", cfg.speedMinKmh < cfg.speedMaxKmh)
        }
    }
}
