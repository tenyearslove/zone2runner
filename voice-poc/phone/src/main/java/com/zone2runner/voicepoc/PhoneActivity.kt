package com.zone2runner.voicepoc

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 음성 토크테스트 PoC (폰). 고정 문장을 낭독 → 온디바이스 VAD 분석 → 5단계 판정.
 * 흐름: (1) 편할 때 "기준선 녹음" → (2) 힘들 때 "측정 녹음" → 기준선 대비 곤란도/단계 표시.
 * 워치가 보낸 낭독(VoiceChannelService)도 여기 화면에 함께 표시된다.
 */
class PhoneActivity : AppCompatActivity() {

    private val recorder = AudioRecorder()
    private lateinit var status: TextView
    private lateinit var baselineView: TextView
    private lateinit var resultView: TextView
    private lateinit var watchView: TextView
    private lateinit var btnBase: Button
    private lateinit var btnTest: Button
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceStore.load(this)
        VoiceStore.onChange = { runOnUiThread { renderWatch() } }
        setContentView(buildUi())
        renderBaseline(); renderWatch()
        ensureMic()
    }

    override fun onDestroy() { super.onDestroy(); VoiceStore.onChange = null }

    private fun buildUi(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        col.addView(text("음성 토크테스트 PoC", 22f, bold = true))
        col.addView(text("아래 문장을 소리내어 편하게 읽어주세요.", 13f, color = MUTED).also { it.setPadding(0, dp(6), 0, dp(12)) })

        col.addView(card(SENTENCE, 18f, bold = true, color = ACCENT))

        btnBase = button("① 기준선 녹음 (편하게 숨차지 않을 때)") { doRecord(isBaseline = true) }
        btnTest = button("② 측정 녹음 (힘들 때)") { doRecord(isBaseline = false) }
        col.addView(btnBase, mt(14)); col.addView(btnTest, mt(8))

        status = text("", 13f, color = ACCENT).also { it.setPadding(0, dp(12), 0, 0) }
        col.addView(status)

        col.addView(text("기준선", 12f, color = MUTED).also { it.setPadding(0, dp(16), 0, dp(2)) })
        baselineView = text("아직 없음", 14f); col.addView(baselineView)

        col.addView(text("측정 결과 (폰)", 12f, color = MUTED).also { it.setPadding(0, dp(14), 0, dp(2)) })
        resultView = text("측정 전", 14f); col.addView(resultView)

        col.addView(text("워치에서 온 측정", 12f, color = MUTED).also { it.setPadding(0, dp(14), 0, dp(2)) })
        watchView = text("없음", 14f); col.addView(watchView)

        return ScrollView(this).apply { setBackgroundColor(BG); addView(col) }
    }

    private fun doRecord(isBaseline: Boolean) {
        if (busy) return
        if (!hasMic()) { ensureMic(); return }
        setBusy(true, "녹음 중… 문장을 편하게 읽어주세요")
        lifecycleScope.launch {
            val samples = withContext(Dispatchers.IO) { recorder.record(RECORD_MS) }
            val m = VoiceAnalyzer.analyze(samples, recorder.sampleRate)
            if (isBaseline) {
                VoiceStore.setBaseline(this@PhoneActivity, m)
                renderBaseline()
                setBusy(false, "기준선 저장됨")
            } else {
                val v = TalkJudge.judge(m, VoiceStore.baseline)
                resultView.text = "${v.level.label}  (곤란도 %.2f)\n%s\n[완독 %dms, 끊김 %d, 발화율 %.0f%%]".format(
                    v.difficulty, v.detail, m.speechSpanMs, m.pauseCount, m.voicedRatio * 100
                )
                setBusy(false, "측정 완료")
            }
        }
    }

    private fun renderBaseline() {
        val b = VoiceStore.baseline
        baselineView.text = if (b == null) "아직 없음 (먼저 ① 기준선 녹음)"
        else "완독 %dms, 끊김 %d회, 발화율 %.0f%%".format(b.speechSpanMs, b.pauseCount, b.voicedRatio * 100)
    }

    private fun renderWatch() {
        val v = VoiceStore.lastWatchVerdict
        watchView.text = if (v == null) "없음 (워치에서 측정하면 표시)"
        else "${v.level.label}  (곤란도 %.2f)\n%s".format(v.difficulty, v.detail)
    }

    private fun setBusy(b: Boolean, msg: String?) {
        busy = b; btnBase.isEnabled = !b; btnTest.isEnabled = !b
        if (msg != null) status.text = msg
    }

    // ---- 권한 ----
    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun ensureMic() {
        if (!hasMic()) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    // ---- 뷰 헬퍼 ----
    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = TEXT) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun card(s: String, size: Float, bold: Boolean, color: Int) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER; setPadding(dp(16), dp(18), dp(16), dp(18))
        setBackgroundColor(CARD)
    }
    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }
    private fun mt(top: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val SENTENCE = "저는 지금 편안하게 천천히 달리고 있습니다"
        const val RECORD_MS = 4000
        val BG = Color.parseColor("#0E1116"); val CARD = Color.parseColor("#171B22")
        val TEXT = Color.parseColor("#E8EAED"); val MUTED = Color.parseColor("#9AA0A6")
        val ACCENT = Color.parseColor("#30D158")
    }
}
