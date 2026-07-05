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
 * 호흡 토크테스트 PoC (폰, YAMNet). 문장을 낭독하는 동안 온디바이스 오디오 분류 NN으로
 * 숨소리(Breathing/Gasp/Pant)와 말소리(Speech)를 감지해 "숨참" 정도를 5단계로 판정한다.
 * 내용(무슨 단어를 읽었나)이 아니라 호흡을 직접 본다.
 */
class PhoneActivity : AppCompatActivity() {

    private val recorder = AudioRecorder()
    private var breath: BreathClassifier? = null
    private lateinit var status: TextView
    private lateinit var resultView: TextView
    private lateinit var scoreView: TextView
    private lateinit var topView: TextView
    private lateinit var btn: Button
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        ensureMic()
    }

    override fun onDestroy() { super.onDestroy(); breath?.close() }

    private fun buildUi(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        col.addView(text("호흡 측정 PoC (YAMNet)", 22f, bold = true))
        col.addView(text("탭한 뒤 8초간 말은 하지 말고 마이크 가까이에서 숨만 쉬세요. 숨이 셀수록/빠를수록 벅참으로 판정합니다.", 13f, color = MUTED)
            .also { it.setPadding(0, dp(6), 0, dp(12)) })
        col.addView(card("입으로 숨쉬기\n(폰을 입 가까이)", 16f, ACCENT))

        btn = button("호흡 측정 (탭 후 8초, 말 없이 숨만)") { measure() }
        col.addView(btn, mt(14))

        status = text("", 13f, color = ACCENT).also { it.setPadding(0, dp(12), 0, 0) }
        col.addView(status)

        col.addView(text("판정", 12f, color = MUTED).also { it.setPadding(0, dp(14), 0, dp(2)) })
        resultView = text("측정 전", 16f, bold = true); col.addView(resultView)

        col.addView(text("점수", 12f, color = MUTED).also { it.setPadding(0, dp(12), 0, dp(2)) })
        scoreView = text("-", 13f); col.addView(scoreView)

        col.addView(text("YAMNet 상위 감지", 12f, color = MUTED).also { it.setPadding(0, dp(12), 0, dp(2)) })
        topView = text("-", 12f, color = MUTED); col.addView(topView)

        return ScrollView(this).apply { setBackgroundColor(BG); addView(col) }
    }

    private fun measure() {
        if (busy) return
        if (!hasMic()) { ensureMic(); return }
        busy = true; btn.isEnabled = false; status.text = "듣는 중… 8초간 숨만 쉬세요"
        resultView.text = "…"
        lifecycleScope.launch {
            val triple = withContext(Dispatchers.IO) {
                val samples = recorder.record(RECORD_MS)
                if (breath == null) breath = BreathClassifier(this@PhoneActivity)
                val s = breath!!.classify(samples)
                val sig = BreathEnvelope.analyze(samples, recorder.sampleRate)
                Triple(s, sig, BreathJudge.judge(s, sig))
            }
            val (s, sig, verdict) = triple
            android.util.Log.i("VoicePoC", "[BREATH] diff=${"%.2f".format(verdict.difficulty)} level=${verdict.level} breathSum=${"%.3f".format(s.breathSum)} bpm=${"%.1f".format(sig.breathsPerMin)} peaks=${sig.peaks} db=${"%.1f".format(sig.energyDb)} | top=${s.top.joinToString { "${it.first}:${"%.2f".format(it.second)}" }}")
            resultView.text = "${verdict.level.label}   (곤란도 %.2f)".format(verdict.difficulty)
            scoreView.text = verdict.detail
            topView.text = s.top.joinToString("\n") { "${it.first}  %.2f".format(it.second) }
            status.text = "측정 완료"
            busy = false; btn.isEnabled = true
        }
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
        const val RECORD_MS = 8000
        val BG = Color.parseColor("#0E1116"); val CARD = Color.parseColor("#171B22")
        val TEXT = Color.parseColor("#E8EAED"); val MUTED = Color.parseColor("#9AA0A6")
        val ACCENT = Color.parseColor("#30D158")
    }
}
