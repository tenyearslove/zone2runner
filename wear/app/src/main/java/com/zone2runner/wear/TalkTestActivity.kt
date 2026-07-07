package com.zone2runner.wear

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * 토크테스트 전체화면 설문(별도 Activity) — 좁은 러닝 대시보드에 끼워넣지 않고 새 화면으로 덮는다.
 * 5단계 답 중 하나를 누르면 폰으로 전송(/talk/<state>) 후 닫힘. 30초 무응답이면 자동 닫힘.
 * 러닝 세션은 RunService가 계속 소유하므로 이 화면이 떠 있어도 측정/누적은 지속(adr-009).
 * 답은 폰이 개인화(observeTalkTest)에 반영. 언제 띄울지는 폰이 판단해 /run/talk으로 명령(adr-023).
 *
 * 배치(사용자 요청: 달리며 스크롤 불가 → 5개 선택지가 한 화면에):
 * 원형 화면에 맞춰 제목 + [아주편함|편함] / [보통(가운데 크게)] / [벅참|매우벅참] 3행.
 * 강도별 색(파랑→초록→회색→주황→빨강)으로 글 안 읽고도 위치/색만으로 즉답 가능.
 */
class TalkTestActivity : ComponentActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { finish() } // 30초 무응답 시 닫힘

    override fun onResume() { super.onResume(); active = true }
    override fun onPause() { super.onPause(); active = false }

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

    private fun buildUi(): LinearLayout {
        // 스크롤 없이 5개 답이 한 화면에(사용자 요청). 원형 화면이라 위/아래 행은 폭을 줄이고
        // 가운데(가장 넓은 지점)에 "보통"을 크게 — 위치+색만으로 즉답 가능한 배치.
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        col.addView(TextView(this).apply {
            text = "대화 되나요?"; textSize = 15f; setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(C_TEXT); gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        })
        fun row(vararg views: TextView) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            views.forEach { addView(it) }
        }
        // 편함(위) → 벅참(아래) 순서 고정: 위쪽 = 여유, 아래쪽 = 힘듦으로 몸에 익게
        col.addView(row(
            answer("아주편함", "very_comfortable", C_BLUE),
            answer("편함", "comfortable", C_GREEN),
        ))
        col.addView(row(answer("보통", "borderline", C_GRAY, wide = true)))
        col.addView(row(
            answer("벅참", "hard", C_AMBER),
            answer("매우벅참", "very_hard", C_RED),
        ))
        return col
    }

    /** 답 버튼 — 강도색 배경, 누르면 전송 후 닫힘. wide = 가운데 행(원형 화면 최대폭) 단독 버튼. */
    private fun answer(label: String, state: String, color: Int, wide: Boolean = false): TextView = TextView(this).apply {
        text = label; textSize = 13f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        isSingleLine = true
        setPadding(0, dp(12), 0, dp(12)) // 세로 패딩 = 터치 타깃 확보(달리며 눌러야 함)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(24).toFloat() }
        val lp = LinearLayout.LayoutParams(if (wide) dp(150) else dp(88), WRAP_CONTENT)
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
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

    companion object {
        @Volatile var active = false // 설문 표시 중(RunControlService가 중복 실행 방지에 확인)
        private val C_TEXT = Color.parseColor("#E8EAED")
        // 강도색: 존 팔레트와 같은 계열(파랑=여유 → 빨강=한계). "보통"만 중립 회색.
        private val C_BLUE = Color.parseColor("#2E7CB8")
        private val C_GREEN = Color.parseColor("#1E9E45")
        private val C_GRAY = Color.parseColor("#3A3F4A")
        private val C_AMBER = Color.parseColor("#C77400")
        private val C_RED = Color.parseColor("#C22A20")
    }
}
