package com.zone2runner.wear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 원형 화면 베젤을 따라 5개 존 세그먼트를 그리는 게이지.
 * 하단(6시 방향)은 열어두어(270도 sweep) 버튼 공간을 확보한다.
 * 현재 존 세그먼트를 강조하고, 현재 HR 위치에 흰 마커를 찍는다.
 */
class ZoneGaugeView(context: Context) : View(context) {

    private val strokeW = dp(11f)
    private val rect = RectF()

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }

    private val startAngle = 135f
    private val totalSweep = 270f
    private val segSweep = totalSweep / HrZone.values().size

    private var fraction = 0f
    private var current: HrZone? = null
    private var active = false

    fun update(zone: HrZone?, frac: Float, running: Boolean) {
        current = zone; fraction = frac; active = running
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = strokeW / 2f + dp(5f)
        rect.set(pad, pad, width - pad, height - pad)

        HrZone.values().forEachIndexed { i, z ->
            val isCur = active && z == current
            arcPaint.color = if (isCur) z.color else dim(z.color)
            arcPaint.strokeWidth = if (isCur) strokeW * 1.35f else strokeW
            val a = startAngle + i * segSweep
            canvas.drawArc(rect, a + 1.6f, segSweep - 3.2f, false, arcPaint)
        }

        if (active) {
            val ang = Math.toRadians((startAngle + fraction * totalSweep).toDouble())
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f - pad
            val mx = cx + (r * Math.cos(ang)).toFloat()
            val my = cy + (r * Math.sin(ang)).toFloat()
            canvas.drawCircle(mx, my, dp(7f), markerFill)
            canvas.drawCircle(mx, my, dp(7f), markerRing)
        }
    }

    private fun dim(c: Int) = Color.argb(60, Color.red(c), Color.green(c), Color.blue(c))
    private fun dp(v: Float) = v * resources.displayMetrics.density
}
