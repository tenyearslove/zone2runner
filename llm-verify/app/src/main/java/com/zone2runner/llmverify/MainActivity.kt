package com.zone2runner.llmverify

import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakStart = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(32 + b.left, 32 + b.top, 32 + b.right, 32 + b.bottom)
            insets
        }

        val title = TextView(this).apply {
            text = "Zone2 LLM Verify"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        val btn = Button(this).apply { text = "RUN GEMINI NANO CHECK (+TTS)" }
        val ttsBtn = Button(this).apply { text = "TTS ONLY TEST" }
        log = TextView(this).apply { setTextIsSelectable(true) }

        root.addView(title)
        root.addView(btn)
        root.addView(ttsBtn)
        root.addView(ScrollView(this).apply { addView(log) })
        setContentView(root)

        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        append("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        append("SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        initTts()

        btn.setOnClickListener {
            btn.isEnabled = false
            append("=== Gemini Nano (ML Kit Prompt API) + TTS 검증 시작 ===")
            lifecycleScope.launch {
                try {
                    GeminiNanoProbe(::append, ::speak).run()
                } catch (e: Throwable) {
                    append("ERROR ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    btn.isEnabled = true
                }
            }
        }
        ttsBtn.setOnClickListener {
            speak("숨이 차고 오르막이 힘드니, 잠시 페이스를 낮춰서 몸이 적응하도록 해보세요.")
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.KOREAN)
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                append("TTS init: ${if (ttsReady) "OK (KOREAN)" else "한국어 미지원/데이터 없음 (code=$r)"}")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        append("TTS onStart +${SystemClock.elapsedRealtime() - speakStart}ms")
                    }
                    override fun onDone(utteranceId: String?) {
                        append("TTS onDone +${SystemClock.elapsedRealtime() - speakStart}ms (재생 완료)")
                    }
                    @Deprecated("deprecated in API 21")
                    override fun onError(utteranceId: String?) {
                        append("TTS onError")
                    }
                })
            } else {
                append("TTS init FAILED (status=$status)")
            }
        }
    }

    private fun speak(text: String) {
        runOnUiThread {
            val t = tts
            if (t == null || !ttsReady) {
                append("TTS 사용 불가 (초기화 전/한국어 미지원)")
                return@runOnUiThread
            }
            speakStart = SystemClock.elapsedRealtime()
            append("TTS speak → $text")
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "coach")
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun append(s: String) {
        runOnUiThread { log.append(s + "\n\n") }
    }
}
