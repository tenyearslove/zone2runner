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

/**
 * 페이스 강제 낭독 Talk Test (spec-017). VT1(Zone2 상단)을 노린다.
 * 박자에 맞춰 숫자를 읽게 하고(말 부하 + 속도 고정), 낭독 중 호흡 끊김을 세어
 * "숨당 발화 길이"를 측정한다. 편하게 길게 말할수록 환기 여유가 큼(VT1 아래).
 * 낭독 후 검증된 3답(예/예…근데/아니오)을 라벨로 저장 → 학습 데이터.
 */
class PacedTalkActivity : AppCompatActivity() {

    private val recorder = AudioRecorder()
    private val tokens = (1..40).map { it.toString() }

    private lateinit var big: TextView
    private lateinit var sub: TextView
    private lateinit var progress: ProgressBar
    private lateinit var startBtn: Button
    private lateinit var result: TextView
    private lateinit var labelRow: LinearLayout
    private lateinit var countView: TextView

    private var running = false
    private var lastSamples: ShortArray? = null
    private var lastMetrics: UtteranceMetrics? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refreshCount()
        ensureMic()
    }

    private fun buildUi(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(tv("페이스 토크테스트 (VT1)", 20f, bold = true))
        col.addView(tv("박자에 맞춰 숫자를 소리내어 읽으세요. 한 숨에 길게 말할수록 편한 것으로 봅니다.", 12f, MUTED)
            .also { it.gravity = Gravity.CENTER; it.setPadding(0, dp(6), 0, dp(16)) })

        big = tv("준비", 68f, bold = true, color = ACCENT).also { it.gravity = Gravity.CENTER }
        col.addView(big, LinearLayout.LayoutParams(MATCH_PARENT, dp(120)).also { })
        sub = tv("", 15f, MUTED).also { it.gravity = Gravity.CENTER }; col.addView(sub)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = tokens.size; progress = 0
        }
        col.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(12) })

        startBtn = Button(this).apply { text = "시작"; isAllCaps = false; setOnClickListener { start() } }
        col.addView(startBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(12) })

        result = tv("", 14f).also { it.gravity = Gravity.CENTER; it.setPadding(0, dp(14), 0, dp(4)) }
        col.addView(result)

        col.addView(tv("낭독 후 '지금 이 강도에서 편하게 말할 수 있었나?'", 12f, MUTED)
            .also { it.gravity = Gravity.CENTER; it.setPadding(0, dp(8), 0, dp(4)) })
        labelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labelRow.addView(labelBtn("예", "POS"))
        labelRow.addView(labelBtn("예…근데", "EQ"))
        labelRow.addView(labelBtn("아니오", "NEG"))
        setLabelEnabled(false)
        col.addView(labelRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(4) })

        countView = tv("수집: 0개", 12f, MUTED).also { it.gravity = Gravity.CENTER; it.setPadding(0, dp(16), 0, 0) }
        col.addView(countView)

        return ScrollView(this).apply { setBackgroundColor(BG); addView(col) }
    }

    private fun start() {
        if (running) return
        if (!hasMic()) { ensureMic(); return }
        running = true; startBtn.isEnabled = false; setLabelEnabled(false)
        result.text = ""; progress.progress = 0
        val totalMs = tokens.size * BEAT_MS + 900
        lifecycleScope.launch {
            // 카운트다운
            for (c in listOf("3", "2", "1", "읽어요!")) { big.text = c; big.setTextColor(AMBER); delay(700) }
            big.setTextColor(ACCENT)
            // 녹음(백그라운드)과 페이스 하이라이트(메인)를 동시에
            val rec = async(Dispatchers.IO) { recorder.record(totalMs) }
            for (i in tokens.indices) {
                big.text = tokens[i]
                sub.text = if (i + 1 < tokens.size) "다음: ${tokens[i + 1]}" else "끝"
                progress.progress = i + 1
                delay(BEAT_MS.toLong())
            }
            big.text = "…"; sub.text = "분석 중"
            val samples = rec.await()
            val m = UtteranceAnalyzer.analyze(samples, recorder.sampleRate, BEAT_MS)
            lastSamples = samples; lastMetrics = m
            android.util.Log.i("VoicePoC", "[PACED] breaths=${m.breaths} bpm=${"%.1f".format(m.breathsPerMin)} meanUtter=${"%.2f".format(m.meanUtteranceSec)}s tokens/breath=${"%.1f".format(m.tokensPerBreath)} speakRatio=${"%.2f".format(m.speakingRatio)} span=${"%.1f".format(m.spanSec)}")
            big.text = "완료"
            sub.text = ""
            result.text = "숨당 %.1f개(%.1f초) 발화, 호흡 %d회, %.0f회/분\n→ 라벨을 눌러 저장하세요".format(
                m.tokensPerBreath, m.meanUtteranceSec, m.breaths, m.breathsPerMin
            )
            setLabelEnabled(true)
            startBtn.isEnabled = true
            running = false
        }
    }

    private fun saveLabel(label: String) {
        val s = lastSamples; val m = lastMetrics ?: return
        if (s == null) return
        val id = "${label}_${SampleStore.count(this) + 1}_${s.size}"
        lifecycleScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                SampleStore.save(this@PacedTalkActivity, id, s, recorder.sampleRate, label, m, BEAT_MS)
            }
            Toast.makeText(this@PacedTalkActivity, "저장됨: $label", Toast.LENGTH_SHORT).show()
            setLabelEnabled(false); refreshCount()
            big.text = "준비"; result.text = ""
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
        const val BEAT_MS = 550 // 토큰당 박자(고정 페이스)
        val BG = Color.parseColor("#0E1116"); val TEXT = Color.parseColor("#E8EAED")
        val MUTED = Color.parseColor("#9AA0A6"); val ACCENT = Color.parseColor("#30D158")
        val AMBER = Color.parseColor("#FF9F0A")
    }
}
