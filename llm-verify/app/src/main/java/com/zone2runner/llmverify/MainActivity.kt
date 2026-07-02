package com.zone2runner.llmverify

import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Android 15+ edge-to-edge: 시스템바 인셋만큼 패딩 (버튼이 상태바에 가리지 않도록)
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
        val btn = Button(this).apply { text = "RUN GEMINI NANO CHECK" }
        log = TextView(this).apply { setTextIsSelectable(true) }

        root.addView(title)
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
