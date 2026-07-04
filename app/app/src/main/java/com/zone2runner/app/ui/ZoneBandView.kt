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
 * 현재 HR 마커(흰 링 + 존 색)와 하한/상한 bpm 라벨을 함께 그린다. 개인화 갱신 시 밴드가 함께 움직인다.
 */
class ZoneBandView(context: Context) : View(context) {

    private var lo = 0      // Zone2 하한(bpm)
    private var hi = 0      // Zone2 상한(bpm)
    private var maxHr = 190
    private var hr = -1        // 지속 심박(최근 60초 평균) — 솔리드 마커/판정 기준
    private var instantHr = -1 // 순간 심박 — 얇은 흰 틱(참고)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f); color = Palette.MUTED
    }
    private val rect = RectF()

    fun update(lo: Int, hi: Int, maxHr: Int, hr: Int, instantHr: Int = -1) {
        this.lo = lo; this.hi = hi; this.maxHr = maxHr; this.hr = hr; this.instantHr = instantHr
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(58f).toInt(), MeasureSpec.EXACTLY))
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
            val zoneColor = when {
                hr < lo -> Palette.BLUE
                hr <= hi -> Palette.ACCENT
                hr <= over1 -> Palette.AMBER
                else -> Palette.RED
            }
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, dp(7f), paint)
            paint.color = zoneColor
            canvas.drawCircle(cx, cy, dp(4.5f), paint)
        }
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    private fun dp(v: Float) = v * resources.displayMetrics.density
}
