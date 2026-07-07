package com.zone2runner.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 앱 전역 팔레트(다크). 존 색은 domain.DisplayZone(폰-워치 공통 5색, adr-023)과 일치. */
object Palette {
    val BG = Color.parseColor("#0E1116")
    val CARD = Color.parseColor("#171B22")
    val STROKE = Color.parseColor("#2A2F3A")
    val TEXT = Color.parseColor("#E8EAED")
    val MUTED = Color.parseColor("#9AA0A6")
    val ACCENT = Color.parseColor("#30D158")
    val BLUE = Color.parseColor("#5AC8FA")
    val YELLOW = Color.parseColor("#FFD60A")
    val AMBER = Color.parseColor("#FF9F0A")
    val RED = Color.parseColor("#FF3B30")
}

fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/**
 * targetSdk 35(Android 15+)는 edge-to-edge가 강제라 콘텐츠가 상태바/내비게이션바 밑까지 그려진다.
 * 루트 뷰에 시스템 바 인셋만큼 패딩을 더해 가림을 막는다. setContentView 직전에 루트에 호출.
 */
fun <T : View> T.withSystemBarInsets(): T {
    val l = paddingLeft; val t = paddingTop; val r = paddingRight; val b = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
        insets
    }
    return this
}

/** 제목 + 내용 카드(둥근 모서리 + 테두리). title=null 이면 제목 생략. */
fun Context.card(title: String?, content: View, topMarginDp: Int = 10): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Palette.CARD); cornerRadius = dpi(16).toFloat(); setStroke(dpi(1), Palette.STROKE)
        }
        setPadding(dpi(16), dpi(13), dpi(16), dpi(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(topMarginDp) }
        if (title != null) addView(TextView(this@card).apply {
            text = title; textSize = 12f; setTextColor(Palette.MUTED); setPadding(0, 0, 0, dpi(8))
        })
        addView(content)
    }

/** 큰 액션 버튼(둥근 알약). */
fun Context.bigButton(label: String, color: Int, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label; textSize = 16f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dpi(20), dpi(15), dpi(20), dpi(15))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dpi(28).toFloat() }
        isClickable = true; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(10) }
    }

/** 값 + 라벨 세로 타일(그리드 셀). */
fun Context.statTile(value: String, label: String, valueColor: Int = Palette.TEXT): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dpi(6), 0, dpi(6))
        addView(TextView(this@statTile).apply {
            text = value; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(valueColor)
        })
        addView(TextView(this@statTile).apply { text = label; textSize = 11f; setTextColor(Palette.MUTED) })
    }

fun Context.title(text: String, size: Float = 24f): TextView =
    TextView(this).apply {
        this.text = text; textSize = size; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.TEXT)
    }

fun Context.subtitle(text: String): TextView =
    TextView(this).apply { this.text = text; textSize = 12f; setTextColor(Palette.MUTED); setPadding(0, dpi(2), 0, 0) }
