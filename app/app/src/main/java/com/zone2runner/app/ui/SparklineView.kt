package com.zone2runner.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * 미니 추세 그래프(스파크라인, FR6) — 세션 간 지표 시퀀스를 작게 그린다.
 * 마지막 점을 강조하고, "좋아지는 방향"(higherBetter 대비 처음→끝)이면 초록, 아니면 앰버.
 */
class SparklineView(context: Context) : View(context) {
    private var values: List<Double> = emptyList()
    private var higherBetter = true

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Palette.MUTED }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun set(values: List<Double>, higherBetter: Boolean) {
        this.values = values; this.higherBetter = higherBetter; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val vs = values
        if (vs.size < 2) return
        val pad = 6f
        val w = width - pad * 2; val h = height - pad * 2
        val min = vs.min(); val max = vs.max()
        val span = (max - min).let { if (it < 1e-9) 1.0 else it }
        fun x(i: Int) = pad + w * i / (vs.size - 1)
        fun y(v: Double) = pad + h * (1.0 - (v - min) / span).toFloat()

        val improving = if (higherBetter) vs.last() >= vs.first() else vs.last() <= vs.first()
        val accent = if (improving) Palette.ACCENT else Palette.AMBER
        line.color = Palette.MUTED
        val path = Path()
        for (i in vs.indices) {
            val px = x(i); val py = y(vs[i])
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, line)
        dot.color = accent
        canvas.drawCircle(x(vs.size - 1), y(vs.last()), 5f, dot)
    }
}
