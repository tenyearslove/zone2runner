package com.zone2runner.llmverify

import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val btn = Button(this).apply { text = "Run Gemini Nano check" }
        log = TextView(this).apply { setTextIsSelectable(true) }
        root.addView(btn)
        root.addView(ScrollView(this).apply { addView(log) })
        setContentView(root)

        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        append("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        append("SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        append("아래 버튼을 눌러 온디바이스 LLM 가용성/지연을 검증합니다.")

        btn.setOnClickListener {
            btn.isEnabled = false
            append("=== Gemini Nano (ML Kit Prompt API) 검증 시작 ===")
            lifecycleScope.launch {
                try {
                    GeminiNanoProbe(::append).run()
                } catch (e: Throwable) {
                    append("ERROR ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    btn.isEnabled = true
                }
            }
        }
    }

    private fun append(s: String) {
        runOnUiThread { log.append(s + "\n\n") }
    }
}
