package com.zone2runner.wear

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * 토크테스트 전체화면 설문(별도 Activity) — 좁은 러닝 대시보드에 끼워넣지 않고 새 화면으로 덮는다.
 * 5단계 답 중 하나를 누르면 폰으로 전송(/talk/<state>) 후 닫힘. 30초 무응답이면 자동 닫힘.
 * 러닝 세션은 RunService가 계속 소유하므로 이 화면이 떠 있어도 측정/누적은 지속(adr-009).
 * 답은 폰이 개인화(observeTalkTest)에 반영. 언제 띄울지는 WearRunActivity가 폰 존 기준으로 결정.
 */
class TalkTestActivity : ComponentActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { finish() } // 30초 무응답 시 닫힘

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        setContentView(buildUi())
        buzz() // 놓치지 않게 짧은 진동
        ui.postDelayed(autoDismiss, 30_000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(autoDismiss)
    }

    private fun buildUi(): ScrollView {
        // ScrollView로 감싸 5개 답이 원형 화면 세로 범위를 넘어도 모두 접근 가능(잘림 방지).
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK); isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        col.addView(TextView(this).apply {
            text = "대화 되나요?"; textSize = 17f; setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(C_TEXT); gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = "지금 강도, 말하기 얼마나 편한가요"; textSize = 10f
            setTextColor(C_MUTED); gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(12))
        })
        col.addView(answer("아주편함", "very_comfortable"))
        col.addView(answer("편함", "comfortable"))
        col.addView(answer("보통", "borderline"))
        col.addView(answer("벅참", "hard"))
        col.addView(answer("매우벅참", "very_hard"))
        scroll.addView(col, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return scroll
    }

    /** 고정 폭 버튼(원형 화면 상하단에서 좌우 잘림 방지) — 누르면 전송 후 닫힘. */
    private fun answer(label: String, state: String): TextView = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(11), dp(10), dp(11))
        background = GradientDrawable().apply { setColor(Color.parseColor("#3A3F4A")); cornerRadius = dp(22).toFloat() }
        val lp = LinearLayout.LayoutParams(dp(150), WRAP_CONTENT); lp.topMargin = dp(5); lp.bottomMargin = dp(5)
        layoutParams = lp
        isClickable = true
        setOnClickListener {
            RunLink.send(this@TalkTestActivity, "/talk/$state")
            android.widget.Toast.makeText(this@TalkTestActivity, "기록됨", android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun buzz() {
        runCatching {
            getSystemService(android.os.Vibrator::class.java)
                ?.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val C_TEXT = Color.parseColor("#E8EAED")
        val C_MUTED = Color.parseColor("#9AA0A6")
    }
}
