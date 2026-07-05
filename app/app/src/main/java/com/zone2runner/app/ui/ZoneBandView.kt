package com.zone2runner.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Zone 2 밴드 게이지 (spec-011 대시보드) — "현재 심박이 목표 구간에서 얼마나 벗어났나"를 한눈에.
 * 가로 스케일: [하한-1밴드폭 .. 최대심박]. 구간 색: 미달(파랑) / Zone2(초록) / 초과 1구간(주황) / 그 위(빨강).
 * 세 가지 심박 마커를 함께 그린다(하단 범례와 매칭):
 *   ● 평균  = 지속 심박(최근 60초 평균) — 판정 기준(존 색 채운 원)
 *   | 실측  = 지금 이 순간 심박(얇은 흰 틱)
 *   ◇ 예측  = 60초 뒤 예측 심박(HrDynamics NN, 속 빈 마름모)
 * 개인화 갱신 시 밴드가 함께 움직인다.
 */
class ZoneBandView(context: Context) : View(context) {

    private var lo = 0      // Zone2 하한(bpm)
    private var hi = 0      // Zone2 상한(bpm)
    private var maxHr = 190
    private var hr = -1        // 지속 심박(최근 60초 평균) — 솔리드 마커/판정 기준
    private var instantHr = -1 // 순간 심박 — 얇은 흰 틱(참고)
    private var predHr = -1    // 60초 뒤 예측 심박 — 속 빈 마름모(HrDynamics)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f); color = Palette.MUTED
    }
    private val rect = RectF()

    fun update(lo: Int, hi: Int, maxHr: Int, hr: Int, instantHr: Int = -1, predHr: Int = -1) {
        this.lo = lo; this.hi = hi; this.maxHr = maxHr
        this.hr = hr; this.instantHr = instantHr; this.predHr = predHr
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(78f).toInt(), MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        if (hi <= lo) return
        val band = hi - lo
        val scaleLo = lo - band            // 왼쪽 여백 = 1밴드폭(미달 구간)
        val scaleHi = maxHr
        val range = (scaleHi - scaleLo).coerceAtLeast(1)
        fun x(bpm: Int): Float =
            paddingLeft + (width - paddingLeft - paddingRight) *
                ((bpm - scaleLo).toFloat() / range).coerceIn(0f, 1f)

        val barTop = dp(12f)
        val barBot = barTop + dp(9f)
        val r = dp(4.5f)

        fun seg(from: Int, to: Int, color: Int) {
            rect.set(x(from), barTop, x(to), barBot)
            paint.color = color
            canvas.drawRoundRect(rect, r, r, paint)
        }
        val over1 = hi + (maxHr - hi) / 3 // Zone3 근사(상한~max 3등분의 첫 구간)
        seg(scaleLo, lo, withAlpha(Palette.BLUE, 110))
        seg(hi, over1, withAlpha(Palette.AMBER, 110))
        seg(over1, scaleHi, withAlpha(Palette.RED, 100))
        seg(lo, hi, Palette.ACCENT) // Zone2는 불투명 — 목표 구간 강조

        // 하한/상한 라벨
        text.color = Palette.MUTED
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("$lo", x(lo), barBot + dp(13f), text)
        canvas.drawText("$hi", x(hi), barBot + dp(13f), text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("$maxHr", width - paddingRight.toFloat(), barBot + dp(13f), text)

        // 존 이름 라벨(구간별) — 색과 매칭. 좁은 구간 겹침 방지로 미달/Zone2/고강도 3개만.
        text.textAlign = Paint.Align.CENTER
        val zy = barBot + dp(26f)
        fun zoneLabel(from: Int, to: Int, label: String, color: Int) {
            text.color = color
            canvas.drawText(label, (x(from) + x(to)) / 2f, zy, text)
        }
        zoneLabel(scaleLo, lo, "낮음", Palette.BLUE)
        zoneLabel(lo, hi, "Zone 2", Palette.ACCENT)
        zoneLabel(hi, scaleHi, "높음", Palette.AMBER)

        val cy = (barTop + barBot) / 2

        fun zoneColorOf(bpm: Int) = when {
            bpm < lo -> Palette.BLUE
            bpm <= hi -> Palette.ACCENT
            bpm <= over1 -> Palette.AMBER
            else -> Palette.RED
        }

        // 예측 심박(60초 뒤): 속 빈 마름모(존 색 테두리). "곧 이쪽으로 간다"를 미리 보여줌.
        if (predHr > 0) {
            val px = x(predHr)
            stroke.color = zoneColorOf(predHr); stroke.strokeWidth = dp(2f)
            drawDiamond(canvas, px, cy, dp(6f), stroke)
        }

        // 순간 심박: 얇은 흰 틱(참고용). 지속 마커와 떨어져 있으면 심박이 급변 중이란 신호.
        if (instantHr > 0 && instantHr != hr) {
            paint.color = withAlpha(Color.WHITE, 150)
            val tx = x(instantHr)
            rect.set(tx - dp(1f), barTop - dp(2f), tx + dp(1f), barBot + dp(2f))
            canvas.drawRoundRect(rect, dp(1f), dp(1f), paint)
        }

        // 지속 심박(최근 60초 평균) 마커 — 판정 칩과 같은 기준. 존 색으로 채운다.
        if (hr > 0) {
            val cx = x(hr)
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, dp(7f), paint)
            paint.color = zoneColorOf(hr)
            canvas.drawCircle(cx, cy, dp(4.5f), paint)
        }

        // 범례: ● 평균 · | 실측 · ◇ 예측(60초)
        val ly = zy + dp(20f)
        val gap = dp(11f)
        text.textAlign = Paint.Align.LEFT; text.color = Palette.MUTED
        var lx = paddingLeft.toFloat()
        // ● 평균
        paint.color = Palette.TEXT; canvas.drawCircle(lx + dp(4f), ly - dp(3f), dp(4f), paint)
        lx += gap; canvas.drawText("평균", lx, ly, text); lx += text.measureText("평균") + dp(10f)
        // | 실측
        paint.color = withAlpha(Color.WHITE, 200)
        rect.set(lx + dp(2f), ly - dp(9f), lx + dp(4f), ly + dp(1f)); canvas.drawRoundRect(rect, dp(1f), dp(1f), paint)
        lx += gap; canvas.drawText("실측", lx, ly, text); lx += text.measureText("실측") + dp(10f)
        // ◇ 예측
        stroke.color = Palette.TEXT; stroke.strokeWidth = dp(1.5f)
        drawDiamond(canvas, lx + dp(4f), ly - dp(3f), dp(4.5f), stroke)
        lx += gap; canvas.drawText("예측(60초)", lx, ly, text)
    }

    private fun drawDiamond(canvas: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        val path = android.graphics.Path().apply {
            moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close()
        }
        canvas.drawPath(path, p)
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    private fun dp(v: Float) = v * resources.displayMetrics.density
}
