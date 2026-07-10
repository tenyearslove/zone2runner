package com.zone2runner.app.analysis

import kotlin.math.sqrt

/**
 * 분석 엔진이 지표에 제공하는 신호 읽기 뷰(SRP 경계). 지표는 FeatureExtractor 내부에 결합되지 않고
 * 이 인터페이스로만 hr/pace/spm/slope 창을 읽는다(낮은 결합).
 *
 * 시간 인덱스는 tSec(초). [fromSec, toSec) 반열림 구간, 가용 범위로 클램프. toSec에 tNow+1을 주면 "현재까지".
 */
interface SignalWindow {
    val tNow: Int      // 마지막 표본의 tSec
    val count: Int     // 총 표본 수

    fun hr(fromSec: Int, toSec: Int): DoubleArray      // 이상치 제거된 bpm
    fun pace(fromSec: Int, toSec: Int): DoubleArray     // min/km
    fun spm(fromSec: Int, toSec: Int): DoubleArray
    fun slope(fromSec: Int, toSec: Int): DoubleArray    // %
    fun times(fromSec: Int, toSec: Int): DoubleArray    // tSec을 Double로(회귀 x축, 위 배열과 정렬)

    fun latestPace(): Double
    fun latestSlope(): Double

    /** 창 페이스 변동계수(σ/μ). 표본<2 또는 μ≈0이면 null. 정속 게이트용. */
    fun paceCv(fromSec: Int, toSec: Int): Double? {
        val p = pace(fromSec, toSec)
        if (p.size < 2) return null
        val mean = p.average()
        if (mean <= 1e-6) return null
        var v = 0.0
        for (x in p) v += (x - mean) * (x - mean)
        val sd = sqrt(v / p.size)
        return sd / mean
    }
}

/**
 * SignalWindow 구현 — 엔진이 매 틱 append. 세션 전체를 보관(세션종료 지표가 전 구간을 봐야 함).
 * 1Hz지만 이상치로 스킵된 틱이 있을 수 있어 tSec을 함께 저장하고 범위 질의는 뒤에서 스캔한다.
 */
class SignalBuffer : SignalWindow {
    private val t = ArrayList<Int>()
    private val hrs = ArrayList<Double>()
    private val paces = ArrayList<Double>()
    private val spms = ArrayList<Double>()
    private val slopes = ArrayList<Double>()

    override val tNow: Int get() = t.lastOrNull() ?: -1
    override val count: Int get() = t.size

    fun add(tSec: Int, hr: Double, paceMinKm: Double, spm: Int, slopePct: Double) {
        t += tSec; hrs += hr; paces += paceMinKm; spms += spm.toDouble(); slopes += slopePct
    }

    private inline fun range(fromSec: Int, toSec: Int, src: ArrayList<Double>): DoubleArray {
        // t는 오름차순 append. [fromSec,toSec) 인덱스 구간을 찾는다.
        val out = ArrayList<Double>()
        for (i in t.indices) { val ti = t[i]; if (ti in fromSec until toSec) out += src[i] }
        return out.toDoubleArray()
    }

    override fun hr(fromSec: Int, toSec: Int) = range(fromSec, toSec, hrs)
    override fun pace(fromSec: Int, toSec: Int) = range(fromSec, toSec, paces)
    override fun spm(fromSec: Int, toSec: Int) = range(fromSec, toSec, spms)
    override fun slope(fromSec: Int, toSec: Int) = range(fromSec, toSec, slopes)
    override fun times(fromSec: Int, toSec: Int): DoubleArray {
        val out = ArrayList<Double>()
        for (ti in t) if (ti in fromSec until toSec) out += ti.toDouble()
        return out.toDoubleArray()
    }

    override fun latestPace(): Double = paces.lastOrNull() ?: 0.0
    override fun latestSlope(): Double = slopes.lastOrNull() ?: 0.0
}
