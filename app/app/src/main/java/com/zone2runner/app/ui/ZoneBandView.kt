package com.zone2runner.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.zone2runner.app.domain.DisplayZones

/**
 * Zone 2 밴드 게이지 (spec-011 대시보드) — "현재 심박이 목표 구간에서 얼마나 벗어났나"를 한눈에.
 * 가로 스케일: [하한-1밴드폭 .. 최대심박]. 구간 색: 5존 공통 팔레트(adr-023, 워치와 동일 색)
 *   Z1(파랑)/Z2(초록, 목표)/Z3(노랑)/Z4(주황)/Z5(빨강) — Z3~Z5는 상한~최대심박 3등분.
 * 두 가지 심박 마커를 함께 그린다(하단 범례와 매칭):
 *   ● 실측  = 순간 심박 — 표시 존/색 기준(adr-023, 존 색 채운 원)
 *   | 평균  = 지속 심박(최근 60초 평균) — 코칭/통계 기준(얇은 흰 틱)
 * 개인화 갱신 시 밴드가 함께 움직인다.
 */
class ZoneBandView(context: Context) : View(context) {

    private var lo = 0      // Zone2 하한(bpm)
    private var hi = 0      // Zone2 상한(bpm)
    private var maxHr = 190
    private var hr = -1        // 순간 심박 — 솔리드 마커/표시 존 기준(adr-023)
    private var susHr = -1     // 지속 심박(최근 60초 평균) — 얇은 흰 틱(코칭 기준)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f); color = Palette.MUTED
    }
    private val rect = RectF()

    fun update(lo: Int, hi: Int, maxHr: Int, hr: Int, susHr: Int = -1) {
        this.lo = lo; this.hi = hi; this.maxHr = maxHr
        this.hr = hr; this.susHr = susHr
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(78f).toInt(), MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        if (hi <= lo) return
        val band = hi - lo
        val scaleLo = lo - band            // 왼쪽 여백 = 1밴드폭(Z1 구간)
        val scaleHi = maxHr
        val range = (scaleHi - scaleLo).coerceAtLeast(1)
        fun x(bpm: Double): Float =
            paddingLeft + (width - paddingLeft - paddingRight) *
                ((bpm - scaleLo).toFloat() / range).coerceIn(0f, 1f)
        fun x(bpm: Int): Float = x(bpm.toDouble())

        val barTop = dp(12f)
        val barBot = barTop + dp(9f)
        val r = dp(4.5f)

        fun seg(from: Double, to: Double, color: Int) {
            rect.set(x(from), barTop, x(to), barBot)
            paint.color = color
            canvas.drawRoundRect(rect, r, r, paint)
        }
        // Z3~Z5 = 상한~최대심박 3등분(DisplayZones.rawZone과 동일 분할, adr-023)
        val segW = ((maxHr - hi) / 3.0).coerceAtLeast(1.0)
        seg(scaleLo.toDouble(), lo.toDouble(), withAlpha(Palette.BLUE, 110))
        seg(hi.toDouble(), hi + segW, withAlpha(Palette.YELLOW, 110))
        seg(hi + segW, hi + 2 * segW, withAlpha(Palette.AMBER, 110))
        seg(hi + 2 * segW, scaleHi.toDouble(), withAlpha(Palette.RED, 100))
        seg(lo.toDouble(), hi.toDouble(), Palette.ACCENT) // Zone2는 불투명 — 목표 구간 강조

        // 하한/상한 라벨
        text.color = Palette.MUTED
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("$lo", x(lo), barBot + dp(13f), text)
        canvas.drawText("$hi", x(hi), barBot + dp(13f), text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("$maxHr", width - paddingRight.toFloat(), barBot + dp(13f), text)

        // 존 이름 라벨(구간별) — 색과 매칭. 좁은 구간 겹침 방지로 Z1/Z2/고강도 3개만.
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

        fun zoneColorOf(bpm: Int) =
            Color.parseColor(DisplayZones.rawZone(bpm, lo, hi, maxHr).colorHex)

        // 지속 심박(최근 60초 평균): 얇은 흰 틱 — 코칭/통계 기준(참고). 솔리드 마커와 떨어져 있으면 급변 중.
        if (susHr > 0 && susHr != hr) {
            paint.color = withAlpha(Color.WHITE, 150)
            val tx = x(susHr)
            rect.set(tx - dp(1f), barTop - dp(2f), tx + dp(1f), barBot + dp(2f))
            canvas.drawRoundRect(rect, dp(1f), dp(1f), paint)
        }

        // 순간 심박 마커 — 표시 존/칩과 같은 기준(adr-023). 존 색으로 채운다.
        if (hr > 0) {
            val cx = x(hr)
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, dp(7f), paint)
            paint.color = zoneColorOf(hr)
            canvas.drawCircle(cx, cy, dp(4.5f), paint)
        }

        // 범례: ● 실측 · | 평균
        val ly = zy + dp(20f)
        val gap = dp(11f)
        text.textAlign = Paint.Align.LEFT; text.color = Palette.MUTED
        var lx = paddingLeft.toFloat()
        // ● 실측(순간)
        paint.color = Palette.TEXT; canvas.drawCircle(lx + dp(4f), ly - dp(3f), dp(4f), paint)
        lx += gap; canvas.drawText("실측", lx, ly, text); lx += text.measureText("실측") + dp(10f)
        // | 평균(60초)
        paint.color = withAlpha(Color.WHITE, 200)
        rect.set(lx + dp(2f), ly - dp(9f), lx + dp(4f), ly + dp(1f)); canvas.drawRoundRect(rect, dp(1f), dp(1f), paint)
        lx += gap; canvas.drawText("평균", lx, ly, text)
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    private fun dp(v: Float) = v * resources.displayMetrics.density
}
