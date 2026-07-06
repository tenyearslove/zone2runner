package com.zone2runner.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.zone2runner.app.domain.PersonalizationStatus

/**
 * 개인화 진행 시각화 (spec-020 FR3) — "기준값이 얼마나 이동했나"를 한눈에.
 * 위: 공통 bpm 스케일에 초기(공식) Zone2 밴드(반투명)와 현재(학습) 밴드(초록)를 겹쳐 그리고 이동량 표시.
 * 아래: 세션별 상단 경계(uFrac→bpm) 스파크라인 — 관측이 쌓이며 어떻게 수렴했는지.
 */
class PersonalizationView(context: Context) : View(context) {

    private var st: PersonalizationStatus? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11f); color = Palette.MUTED }
    private val rect = RectF()

    fun set(status: PersonalizationStatus) { st = status; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(dp(if (st?.uFracHistory.orEmpty().size >= 2) 150f else 96f).toInt(), MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        val s = st ?: return
        val padL = paddingLeft.toFloat(); val padR = paddingRight.toFloat()
        val fullW = width - padL - padR

        // bpm 스케일: 초기/현재 밴드를 여유 있게 감싸는 창
        val lo = minOf(s.priorLo, s.curLo) - 8
        val hi = maxOf(s.priorHi, s.curHi) + 8
        val range = (hi - lo).coerceAtLeast(1)
        fun x(bpm: Int) = padL + fullW * ((bpm - lo).toFloat() / range).coerceIn(0f, 1f)

        // ---- 상단: 밴드 겹쳐 그리기 ----
        val barTop = dp(22f); val barH = dp(16f)
        // 초기(공식) 밴드 — 반투명 회색
        fill.color = Color.argb(90, 154, 160, 166)
        rect.set(x(s.priorLo), barTop, x(s.priorHi), barTop + barH)
        canvas.drawRoundRect(rect, dp(4f), dp(4f), fill)
        // 현재(학습) 밴드 — 초록(학습됐을 때만 강조; 아니면 초기와 동일해 겹침)
        fill.color = if (s.learned) Palette.ACCENT else Color.argb(0, 0, 0, 0)
        if (s.learned) {
            rect.set(x(s.curLo), barTop, x(s.curHi), barTop + barH)
            canvas.drawRoundRect(rect, dp(4f), dp(4f), fill)
        }

        // 라벨: 초기 / 현재 상단 bpm + 이동량
        text.color = Palette.MUTED; text.textAlign = Paint.Align.LEFT
        canvas.drawText("초기 ${s.priorLo}~${s.priorHi}", padL, dp(14f), text)
        if (s.learned) {
            text.color = Palette.ACCENT; text.textAlign = Paint.Align.RIGHT
            val arrow = if (s.shiftHiBpm > 0) "▲" else if (s.shiftHiBpm < 0) "▼" else "="
            canvas.drawText("현재 ${s.curLo}~${s.curHi}  ($arrow${kotlin.math.abs(s.shiftHiBpm)}bpm)", width - padR, dp(14f), text)
        }
        // 상한선 이동 화살표(초기 hi → 현재 hi)
        if (s.learned && s.priorHi != s.curHi) {
            stroke.color = Palette.ACCENT; stroke.strokeWidth = dp(1.5f)
            val y = barTop + barH + dp(7f)
            canvas.drawLine(x(s.priorHi), y, x(s.curHi), y, stroke)
            fill.color = Palette.ACCENT
            val dir = if (s.curHi > s.priorHi) 1 else -1
            val tipX = x(s.curHi)
            val p = Path().apply {
                moveTo(tipX, y); lineTo(tipX - dir * dp(5f), y - dp(3f)); lineTo(tipX - dir * dp(5f), y + dp(3f)); close()
            }
            canvas.drawPath(p, fill)
        }

        // ---- 아래: 세션별 상단 경계 스파크라인 ----
        val hist = s.uFracHistory
        if (hist.size >= 2) {
            val spTop = barTop + barH + dp(20f); val spBot = height - dp(14f)
            val hbLo = hist.min(); val hbHi = hist.max(); val hbRange = (hbHi - hbLo).coerceAtLeast(1e-4)
            fun sy(u: Double) = spBot - (spBot - spTop) * ((u - hbLo) / hbRange).toFloat()
            fun sx(i: Int) = padL + fullW * (i.toFloat() / (hist.size - 1))
            stroke.color = Palette.ACCENT; stroke.strokeWidth = dp(2f)
            val path = Path()
            hist.forEachIndexed { i, u -> if (i == 0) path.moveTo(sx(i), sy(u)) else path.lineTo(sx(i), sy(u)) }
            canvas.drawPath(path, stroke)
            // 끝점 강조
            fill.color = Palette.ACCENT
            canvas.drawCircle(sx(hist.size - 1), sy(hist.last()), dp(3f), fill)
            text.color = Palette.MUTED; text.textAlign = Paint.Align.LEFT
            canvas.drawText("세션별 상단 경계 변화 (${hist.size}회)", padL, height - dp(2f), text)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
