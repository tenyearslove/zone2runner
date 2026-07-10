package com.zone2runner.app

import com.zone2runner.app.analysis.AnalysisEngine
import com.zone2runner.app.analysis.AnalysisInput
import com.zone2runner.app.analysis.AnalysisMetric
import com.zone2runner.app.analysis.MetricMode
import com.zone2runner.app.analysis.MetricSample
import com.zone2runner.app.analysis.SignalBuffer
import com.zone2runner.app.domain.Profile
import com.zone2runner.app.domain.Zone2Boundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisEngineTest {

    private fun input(buf: SignalBuffer) =
        AnalysisInput(buf.tNow, Profile.default(), Zone2Boundary(0.70, 0.58), buf)

    private class DummyRealtime(override val id: String) : AnalysisMetric {
        override val mode = MetricMode.REALTIME
        override fun onTick(input: AnalysisInput) = MetricSample(id, input.tSec.toDouble())
    }

    private class DummySessionEnd(override val id: String) : AnalysisMetric {
        override val mode = MetricMode.SESSION_END
        override fun onSessionEnd(input: AnalysisInput) = MetricSample(id, input.window.count.toDouble())
    }

    @Test fun ocp_addMetricByRegistrationOnly() {
        // 지표 추가가 엔진 코드 수정 없이 리스트 등록만으로 된다(OCP).
        val buf = SignalBuffer().apply { add(0, 120.0, 6.0, 170, 0.0) }
        val engine = AnalysisEngine(listOf(DummyRealtime("a"), DummyRealtime("b"), DummyRealtime("c")))
        val out = engine.onTick(input(buf))
        assertEquals(3, out.size)
        assertEquals(setOf("a", "b", "c"), out.map { it.id }.toSet())
    }

    @Test fun modeFiltering_tickVsSessionEnd() {
        val buf = SignalBuffer().apply { add(0, 120.0, 6.0, 170, 0.0); add(1, 121.0, 6.0, 170, 0.0) }
        val engine = AnalysisEngine(listOf(DummyRealtime("rt"), DummySessionEnd("se")))
        val tick = engine.onTick(input(buf))
        assertEquals(listOf("rt"), tick.map { it.id })       // 세션종료 지표는 틱에 안 나옴
        val end = engine.onSessionEnd(input(buf))
        assertEquals(listOf("se"), end.map { it.id })         // 실시간 지표는 종료에 안 나옴
        assertEquals(2.0, end[0].value, 1e-9)                 // window.count
    }

    @Test fun latest_tracksMostRecentTick() {
        val engine = AnalysisEngine(listOf(DummyRealtime("a")))
        val buf = SignalBuffer()
        buf.add(0, 120.0, 6.0, 170, 0.0); engine.onTick(input(buf))
        buf.add(1, 121.0, 6.0, 170, 0.0); engine.onTick(input(buf))
        assertEquals(1.0, engine.latest("a")!!.value, 1e-9)
        assertNull(engine.latest("missing"))
    }

    @Test fun signalBuffer_rangeQueriesAndCv() {
        val buf = SignalBuffer()
        for (t in 0 until 10) buf.add(t, 120.0 + t, 6.0, 170 + t, 1.0)
        assertEquals(10, buf.count)
        assertEquals(9, buf.tNow)
        // [2,5) → t=2,3,4
        val hr = buf.hr(2, 5)
        assertTrue(hr.contentEquals(doubleArrayOf(122.0, 123.0, 124.0)))
        // 페이스 일정 → CV≈0
        assertEquals(0.0, buf.paceCv(0, 10)!!, 1e-9)
        assertEquals(6.0, buf.latestPace(), 1e-9)
    }
}
