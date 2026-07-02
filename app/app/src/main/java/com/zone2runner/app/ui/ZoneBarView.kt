package com.zone2runner.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.zone2runner.app.domain.ZoneJudgment

/** 존 체류 분포(미달/유지/초과)를 가로 막대로 표시. */
class ZoneBarView @JvmOverloads constructor(ctx: Context, a: AttributeSet? = null) : View(ctx, a) {
    private var below = 0; private var inz = 0; private var above = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun set(belowSec: Int, inSec: Int, aboveSec: Int) {
        below = belowSec; inz = inSec; above = aboveSec; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val total = (below + inz + above).coerceAtLeast(1)
        val h = height.toFloat()
        val r = h / 2f
        var x = 0f
        val segs = listOf(
            Triple(below, ZoneJudgment.BELOW.color, "미달"),
            Triple(inz, ZoneJudgment.IN.color, "Zone 2"),
            Triple(above, ZoneJudgment.ABOVE.color, "초과"),
        )
        for ((v, c, label) in segs) {
            if (v == 0) continue
            val w = width * (v.toFloat() / total)
            p.color = c
            rect.set(x, 0f, x + w, h)
            canvas.drawRoundRect(rect, r, r, p)
            val pct = v * 100 / total
            if (w > 90) canvas.drawText("$label $pct%", x + w / 2, h / 2 + 10f, txt)
            x += w + 4f
        }
    }
}
