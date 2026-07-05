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

/**
 * 음성 토크테스트 PoC (폰, ASR 방식). 고정 문장을 낭독하면 온디바이스 ASR이 전사해
 * "어디까지 읽었나(완성도)"를 재고, 호흡 끊김을 보조로 5단계 판정. 완성도는 절대 지표라 기준선 불필요.
 * 워치가 보낸 음향 판정(VoiceChannelService)도 함께 표시한다.
 */
class PhoneActivity : AppCompatActivity() {

    private val asr by lazy { SpeechTalkTest(this) }
    private lateinit var status: TextView
    private lateinit var transcriptView: TextView
    private lateinit var resultView: TextView
    private lateinit var watchView: TextView
    private lateinit var btn: Button
    private var listening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceStore.onChange = { runOnUiThread { renderWatch() } }
        setContentView(buildUi())
        renderWatch()
        ensureMic()
    }

    override fun onDestroy() { super.onDestroy(); VoiceStore.onChange = null }

    private fun buildUi(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        col.addView(text("음성 토크테스트 PoC (ASR)", 22f, bold = true))
        col.addView(text("탭한 뒤 아래 문장을 소리내어 읽어주세요. 끝까지 읽기 힘들수록 벅찬 것으로 판정합니다.", 13f, color = MUTED)
            .also { it.setPadding(0, dp(6), 0, dp(12)) })

        col.addView(card(SENTENCE, 18f, ACCENT))

        btn = button("측정 시작 (탭 후 낭독)") { toggle() }
        col.addView(btn, mt(14))

        status = text("", 13f, color = ACCENT).also { it.setPadding(0, dp(12), 0, 0) }
        col.addView(status)

        col.addView(text("인식된 낭독", 12f, color = MUTED).also { it.setPadding(0, dp(14), 0, dp(2)) })
        transcriptView = text("-", 14f); col.addView(transcriptView)

        col.addView(text("판정", 12f, color = MUTED).also { it.setPadding(0, dp(14), 0, dp(2)) })
        resultView = text("측정 전", 16f, bold = true); col.addView(resultView)

        col.addView(text("워치에서 온 측정(음향)", 12f, color = MUTED).also { it.setPadding(0, dp(16), 0, dp(2)) })
        watchView = text("없음", 13f); col.addView(watchView)

        return ScrollView(this).apply { setBackgroundColor(BG); addView(col) }
    }

    private fun toggle() {
        if (!hasMic()) { ensureMic(); return }
        if (listening) { asr.stop(); status.text = "판정 중…"; return }
        listening = true; btn.text = "완료 (멈춤)"; status.text = "듣는 중… 문장을 읽으세요"
        transcriptView.text = "-"; resultView.text = "…"
        asr.start { r -> runOnUiThread { onAsrResult(r) } }
    }

    private fun onAsrResult(r: SpeechTalkTest.Result) {
        listening = false; btn.text = "측정 시작 (탭 후 낭독)"
        transcriptView.text = r.transcript.ifBlank { "(인식 없음)" }
        if (r.error != null && r.transcript.isBlank()) {
            status.text = r.error; resultView.text = "재시도"
            return
        }
        val comp = Completeness.ratio(SENTENCE, r.transcript)
        val (pauses, _) = RmsPauses.analyze(r.rms)
        val v = AsrTalkJudge.judge(comp, pauses)
        android.util.Log.i("VoicePoC", "[ASR] transcript='${r.transcript}' comp=${"%.2f".format(comp)} pause=$pauses rms=${r.rms.size} level=${v.level} diff=${"%.2f".format(v.difficulty)}")
        resultView.text = "${v.level.label}   (곤란도 %.2f)".format(v.difficulty)
        status.text = v.detail
    }

    private fun renderWatch() {
        val v = VoiceStore.lastWatchVerdict
        watchView.text = if (v == null) "없음 (워치에서 측정하면 표시)"
        else "${v.level.label}  (곤란도 %.2f)\n%s".format(v.difficulty, v.detail)
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun ensureMic() { if (!hasMic()) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1) }

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = TEXT) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun card(s: String, size: Float, color: Int) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER; setPadding(dp(16), dp(18), dp(16), dp(18)); setBackgroundColor(CARD)
    }
    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }
    private fun mt(top: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val SENTENCE = "저는 지금 편안하게 천천히 달리고 있습니다"
        val BG = Color.parseColor("#0E1116"); val CARD = Color.parseColor("#171B22")
        val TEXT = Color.parseColor("#E8EAED"); val MUTED = Color.parseColor("#9AA0A6")
        val ACCENT = Color.parseColor("#30D158")
    }
}
