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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 시간제한 카운팅 Talk Test (spec-017). VT1(Zone2 상단)을 노린다.
 * 고정 시간(WINDOW) 동안 숫자를 최대한 이어서 세게 한다. 숨쉬는 시간이 말하는 시간을 자동으로
 * 깎으므로, 벅찰수록 "말한 비율/센 개수"가 준다 → 호흡 검출 없이 처리량으로 VT1 근접을 본다.
 * 낭독 후 검증된 3답(예/예…근데/아니오)을 라벨로 저장 → 학습 데이터.
 */
class PacedTalkActivity : AppCompatActivity() {

    private val recorder = AudioRecorder()

    private lateinit var big: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var startBtn: Button
    private lateinit var result: TextView
    private lateinit var labelRow: LinearLayout
    private lateinit var countView: TextView

    private var busy = false
    private var lastSamples: ShortArray? = null
    private var lastMetrics: UtteranceMetrics? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refreshCount(); ensureMic()
    }

    private fun buildUi(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(tv("카운팅 Talk Test (VT1)", 20f, bold = true))
        col.addView(tv("시작하면 ${WINDOW_SEC}초 동안 숫자를 최대한 이어서 세세요(하나 둘 셋 넷…). 숨차면 쉬어도 되지만 계속 이어가세요. 편할수록 더 많이 셉니다.", 12f, MUTED)
            .also { it.gravity = Gravity.CENTER; it.setPadding(0, dp(6), 0, dp(14)) })

        big = tv("준비", 60f, ACCENT, bold = true).also { it.gravity = Gravity.CENTER }
        col.addView(big, LinearLayout.LayoutParams(MATCH_PARENT, dp(110)))
        status = tv("", 14f, MUTED).also { it.gravity = Gravity.CENTER }; col.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = WINDOW_SEC }
        col.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(10) })

        startBtn = Button(this).apply { text = "시작"; isAllCaps = false; setOnClickListener { start() } }
        col.addView(startBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(12) })

        result = tv("", 14f).also { it.gravity = Gravity.CENTER; it.setPadding(0, dp(14), 0, dp(4)) }
        col.addView(result)
        col.addView(tv("측정 후 '지금 이 강도에서 편하게 말할 수 있었나?'", 12f, MUTED)
            .also { it.gravity = Gravity.CENTER; it.setPadding(0, dp(8), 0, dp(4)) })
        labelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labelRow.addView(labelBtn("예", "POS")); labelRow.addView(labelBtn("예…근데", "EQ")); labelRow.addView(labelBtn("아니오", "NEG"))
        setLabelEnabled(false)
        col.addView(labelRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        countView = tv("수집: 0개", 12f, MUTED).also { it.gravity = Gravity.CENTER; it.setPadding(0, dp(16), 0, 0) }
        col.addView(countView)
        return ScrollView(this).apply { setBackgroundColor(BG); addView(col) }
    }

    private fun start() {
        if (busy) return
        if (!hasMic()) { ensureMic(); return }
        busy = true; startBtn.isEnabled = false; setLabelEnabled(false); result.text = ""; progress.progress = 0
        lifecycleScope.launch {
            for (c in listOf("3", "2", "1", "세세요!")) { big.text = c; big.setTextColor(AMBER); delay(650) }
            big.setTextColor(ACCENT); status.text = "숫자를 이어서 세는 중…"
            val rec = async(Dispatchers.IO) { recorder.record(WINDOW_SEC * 1000) }
            for (s in WINDOW_SEC downTo 1) { big.text = s.toString(); progress.progress = WINDOW_SEC - s + 1; delay(1000) }
            big.text = "…"; status.text = "분석 중…"
            val samples = rec.await()
            val m = UtteranceAnalyzer.analyze(samples, recorder.sampleRate, WINDOW_SEC * 1000)
            lastSamples = samples; lastMetrics = m
            android.util.Log.i("VoicePoC", "[COUNT] segments=${m.voicedSegments} speakRatio=${"%.2f".format(m.speakingRatio)} breaths=${m.breaths} bpm=${"%.1f".format(m.breathsPerMin)} meanUtter=${"%.2f".format(m.meanUtteranceSec)}")
            big.text = "완료"; status.text = ""
            result.text = "센 개수(발화 덩어리) %d개, 말한 비율 %.0f%%\n(긴 들숨 %d회) → 라벨을 눌러 저장".format(
                m.voicedSegments, m.speakingRatio * 100, m.breaths
            )
            setLabelEnabled(true); startBtn.isEnabled = true; busy = false
        }
    }

    private fun saveLabel(label: String) {
        val s = lastSamples ?: return; val m = lastMetrics ?: return
        val id = "${label}_${SampleStore.count(this) + 1}_${s.size}"
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SampleStore.save(this@PacedTalkActivity, id, s, recorder.sampleRate, label, m, WINDOW_SEC * 1000) }
            Toast.makeText(this@PacedTalkActivity, "저장됨: $label", Toast.LENGTH_SHORT).show()
            setLabelEnabled(false); refreshCount(); result.text = ""; big.text = "준비"
        }
    }

    private fun refreshCount() { countView.text = "수집: ${SampleStore.count(this)}개  (filesDir/talktest)" }
    private fun setLabelEnabled(b: Boolean) { for (i in 0 until labelRow.childCount) labelRow.getChildAt(i).isEnabled = b }
    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun ensureMic() { if (!hasMic()) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1) }

    private fun tv(s: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun labelBtn(label: String, code: String) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { saveLabel(code) }
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val WINDOW_SEC = 20 // 고정 측정 창
        val BG = Color.parseColor("#0E1116"); val TEXT = Color.parseColor("#E8EAED")
        val MUTED = Color.parseColor("#9AA0A6"); val ACCENT = Color.parseColor("#30D158")
        val AMBER = Color.parseColor("#FF9F0A")
    }
}
