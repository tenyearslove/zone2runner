package com.zone2runner.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * 효율 곡선 산점도(FR6) — x=페이스(min/km, 왼쪽이 빠름), y=심박(bpm).
 * 이번 세션(초록)과 직전 세션(회색)을 겹쳐, 같은 페이스에서 심박이 낮아지면(=아래로 이동) 유산소 개선을 시각화.
 */
class ScatterView(context: Context) : View(context) {
    private var cur: List<Pair<Float, Float>> = emptyList()  // (pace, hr)
    private var prev: List<Pair<Float, Float>> = emptyList()

    private val curPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Palette.ACCENT }
    private val prevPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Palette.MUTED }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.STROKE; strokeWidth = 1.5f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.MUTED; textSize = 22f }

    fun set(cur: List<Pair<Float, Float>>, prev: List<Pair<Float, Float>>) {
        this.cur = cur; this.prev = prev; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val all = cur + prev
        if (all.size < 4) return
        val pad = 40f
        val w = width - pad * 2; val h = height - pad * 2
        val xs = all.map { it.first }; val ys = all.map { it.second }
        val xMin = xs.min(); val xMax = xs.max().coerceAtLeast(xMin + 0.1f)
        val yMin = ys.min(); val yMax = ys.max().coerceAtLeast(yMin + 1f)
        // 페이스 x축은 왼쪽이 빠름(작은 min/km) → 그대로 매핑(작을수록 왼쪽)
        fun px(v: Float) = pad + w * (v - xMin) / (xMax - xMin)
        fun py(v: Float) = pad + h * (1f - (v - yMin) / (yMax - yMin))

        canvas.drawLine(pad, pad + h, pad + w, pad + h, axis) // x
        canvas.drawLine(pad, pad, pad, pad + h, axis)          // y
        canvas.drawText("빠름 ← 페이스 → 느림", pad, height - 8f, label)
        canvas.drawText("심박", 4f, pad - 8f, label)

        for (p in prev) canvas.drawCircle(px(p.first), py(p.second), 3.5f, prevPaint)
        for (p in cur) canvas.drawCircle(px(p.first), py(p.second), 3.5f, curPaint)
    }
}
