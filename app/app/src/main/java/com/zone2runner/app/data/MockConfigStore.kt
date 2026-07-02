package com.zone2runner.app.data

import android.content.Context
import com.zone2runner.app.sensor.MockConfig

/** 가짜 라이브(테스트) 설정 영속화 — QA/개발 반복 편의를 위해 마지막 값 저장. */
object MockConfigStore {
    private const val PREF = "zone2_mock"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): MockConfig {
        val p = prefs(ctx)
        val d = MockConfig()
        return MockConfig(
            hrMin = p.getInt("hrMin", d.hrMin),
            hrMax = p.getInt("hrMax", d.hrMax),
            speedMinKmh = p.getFloat("spdMin", d.speedMinKmh.toFloat()).toDouble(),
            speedMaxKmh = p.getFloat("spdMax", d.speedMaxKmh.toFloat()).toDouble(),
        )
    }

    fun save(ctx: Context, c: MockConfig) {
        prefs(ctx).edit()
            .putInt("hrMin", c.hrMin)
            .putInt("hrMax", c.hrMax)
            .putFloat("spdMin", c.speedMinKmh.toFloat())
            .putFloat("spdMax", c.speedMaxKmh.toFloat())
            .apply()
    }
}
