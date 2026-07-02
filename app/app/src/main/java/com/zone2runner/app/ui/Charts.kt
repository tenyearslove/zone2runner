package com.zone2runner.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.zone2runner.app.domain.ZoneJudgment

/**
 * 시계열 라인차트(축 없는 경량). HR/페이스 추이를 그리고, 목표 밴드(Zone2 범위)를 음영으로 강조.
 * 값 단위(bpm 등)와 무관하게 min/max 자동 스케일. 좌측에 min/max 라벨만 표기.
 */
class TimeSeriesChartView(context: Context) : View(context) {

    private var values: FloatArray = FloatArray(0)
    private var bandLo = Float.NaN
    private var bandHi = Float.NaN
    private var lineColor = Color.parseColor("#30D158")
    private var unit = ""

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.2f); strokeJoin = Paint.Join.ROUND }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(38, 48, 209, 88) }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2F3A"); strokeWidth = dp(1f) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9AA0A6"); textSize = dp(10f) }

    fun set(values: List<Float>, bandLo: Float, bandHi: Float, lineColor: Int, unit: String) {
        this.values = values.toFloatArray()
        this.bandLo = bandLo; this.bandHi = bandHi; this.lineColor = lineColor; this.unit = unit
        line.color = lineColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (values.size < 2) return
        val padL = dp(34f); val padR = dp(8f); val padT = dp(8f); val padB = dp(8f)
        val w = width - padL - padR
        val h = height - padT - padB

        var mn = values.min(); var mx = values.max()
        if (!bandLo.isNaN()) { mn = minOf(mn, bandLo); mx = maxOf(mx, bandHi) }
        val pad = (mx - mn) * 0.12f + 1e-3f
        mn -= pad; mx += pad
        val span = (mx - mn).coerceAtLeast(1e-3f)
        fun y(v: Float) = padT + h * (1f - (v - mn) / span)
        fun x(i: Int) = padL + w * (i.toFloat() / (values.size - 1))

        // 목표 밴드
        if (!bandLo.isNaN()) {
            canvas.drawRect(RectF(padL, y(bandHi), padL + w, y(bandLo)), band)
        }
        // 격자(3분할) + 값 라벨
        for (k in 0..2) {
            val v = mn + span * k / 2f
            val yy = y(v)
            canvas.drawLine(padL, yy, padL + w, yy, grid)
            canvas.drawText("${v.toInt()}", dp(2f), yy + dp(3f), label)
        }
        // 라인
        val path = Path()
        for (i in values.indices) {
            val xx = x(i); val yy = y(values[i])
            if (i == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy)
        }
        canvas.drawPath(path, line)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}

/**
 * 존 타임라인 스트립 — 시간 순서의 판정(미달/유지/초과)을 가로 색띠로 표시.
 * 존 체류 "분포"(ZoneBarView)와 달리 "언제" 어느 존이었는지를 보여준다.
 */
class ZoneTimelineView(context: Context) : View(context) {
    private var indices: IntArray = IntArray(0)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(judgmentIndices: List<Int>) { indices = judgmentIndices.toIntArray(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (indices.isEmpty()) return
        val n = indices.size
        val cw = width.toFloat() / n
        val h = height.toFloat()
        for (i in 0 until n) {
            p.color = colorOf(indices[i])
            canvas.drawRect(i * cw, 0f, (i + 1) * cw + 1f, h, p)
        }
    }

    private fun colorOf(idx: Int): Int = when (idx) {
        0 -> ZoneJudgment.BELOW.color
        1 -> ZoneJudgment.IN.color
        2 -> ZoneJudgment.ABOVE.color
        else -> Color.parseColor("#3A3F4A")
    }
}
