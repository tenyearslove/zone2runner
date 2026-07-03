package com.zone2runner.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zone2runner.app.data.MockConfigStore
import com.zone2runner.app.sensor.MockConfig
import com.zone2runner.app.ui.Palette
import com.zone2runner.app.ui.bigButton
import com.zone2runner.app.ui.card
import com.zone2runner.app.ui.dpi
import com.zone2runner.app.ui.subtitle
import com.zone2runner.app.ui.title
import com.zone2runner.app.ui.withSystemBarInsets

/**
 * 가짜 라이브(테스트) 설정 — 심박/속도 범위를 지정해 워치 없이 실시간 러닝을 흉내낸다.
 * QA 테스트 가능성(입력 통제) + 개발/시연 편의. 저장 후 RunActivity를 MODE_MOCK으로 시작.
 */
class MockConfigActivity : AppCompatActivity() {

    private lateinit var hrMinIn: EditText
    private lateinit var hrMaxIn: EditText
    private lateinit var spdMinIn: EditText
    private lateinit var spdMaxIn: EditText
    private lateinit var preview: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView((buildUi()).withSystemBarInsets())
        updatePreview()
    }

    private fun buildUi(): View {
        val c = MockConfigStore.load(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.BG)
            setPadding(dpi(18), dpi(24), dpi(18), dpi(28))
        }
        col.addView(title("가짜 라이브 러닝"))
        col.addView(subtitle("워치 없이 심박/속도 범위를 정해 실시간 러닝을 흉내냅니다 (테스트/시연용)"))

        hrMinIn = numField(c.hrMin.toString(), false)
        hrMaxIn = numField(c.hrMax.toString(), false)
        spdMinIn = numField(fmt1(c.speedMinKmh), true)
        spdMaxIn = numField(fmt1(c.speedMaxKmh), true)

        col.addView(card("심박 범위 (bpm)", row("최소", hrMinIn, "최대", hrMaxIn)))
        col.addView(card("속도 범위 (km/h)", row("최소", spdMinIn, "최대", spdMaxIn)))

        // 프리셋
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, cfg) in MockConfig.PRESETS) {
            presetRow.addView(chip(name) { applyPreset(cfg) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        col.addView(card("프리셋", presetRow))

        preview = TextView(this).apply { textSize = 13f; setTextColor(Palette.ACCENT) }
        col.addView(card("미리보기", preview))

        col.addView(bigButton("이 설정으로 러닝 시작", Palette.ACCENT) { startMock() })
        col.addView(bigButton("취소", Palette.CARD) { finish() })
        return ScrollView(this).apply { setBackgroundColor(Palette.BG); addView(col) }
    }

    private fun applyPreset(cfg: MockConfig) {
        hrMinIn.setText(cfg.hrMin.toString()); hrMaxIn.setText(cfg.hrMax.toString())
        spdMinIn.setText(fmt1(cfg.speedMinKmh)); spdMaxIn.setText(fmt1(cfg.speedMaxKmh))
        updatePreview()
    }

    private fun current(): MockConfig {
        val hMin = hrMinIn.text.toString().toIntOrNull() ?: 120
        val hMax = hrMaxIn.text.toString().toIntOrNull() ?: 155
        val sMin = spdMinIn.text.toString().toDoubleOrNull() ?: 8.0
        val sMax = spdMaxIn.text.toString().toDoubleOrNull() ?: 11.0
        return MockConfig(
            hrMin = hMin.coerceIn(40, 220),
            hrMax = hMax.coerceIn(hMin.coerceIn(40, 220) + 1, 230),
            speedMinKmh = sMin.coerceIn(1.0, 25.0),
            speedMaxKmh = sMax.coerceIn(sMin.coerceIn(1.0, 25.0) + 0.5, 30.0),
        )
    }

    private fun updatePreview() {
        val c = current()
        preview.text = "심박 ${c.hrMin}~${c.hrMax} bpm, 속도 ${fmt1(c.speedMinKmh)}~${fmt1(c.speedMaxKmh)} km/h\n" +
            "페이스 약 ${pace(c.speedMaxKmh)}~${pace(c.speedMinKmh)} /km (빠름~느림)"
    }

    private fun startMock() {
        val c = current()
        if (c.hrMin >= c.hrMax || c.speedMinKmh >= c.speedMaxKmh) {
            Toast.makeText(this, "최소값이 최대값보다 작아야 해요", Toast.LENGTH_SHORT).show(); return
        }
        MockConfigStore.save(this, c)
        startActivity(Intent(this, RunActivity::class.java).putExtra(RunActivity.EXTRA_MODE, RunActivity.MODE_MOCK))
        finish()
    }

    // ---- UI helpers ----
    private fun numField(value: String, decimal: Boolean) = EditText(this).apply {
        inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
        setText(value); setTextColor(Palette.TEXT); textSize = 18f; gravity = Gravity.CENTER
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = updatePreview()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun row(l1: String, f1: EditText, l2: String, f2: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MockConfigActivity).apply { text = l1; setTextColor(Palette.MUTED); textSize = 13f })
        addView(f1, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dpi(8); marginEnd = dpi(16) })
        addView(TextView(this@MockConfigActivity).apply { text = l2; setTextColor(Palette.MUTED); textSize = 13f })
        addView(f2, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dpi(8) })
    }

    private fun chip(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(Palette.TEXT); gravity = Gravity.CENTER
        setPadding(dpi(6), dpi(10), dpi(6), dpi(10))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Palette.BG); cornerRadius = dpi(10).toFloat(); setStroke(dpi(1), Palette.STROKE)
        }
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun fmt1(v: Double) = "%.1f".format(v)
    private fun pace(kmh: Double): String {
        val p = 60.0 / kmh; return "%d'%02d\"".format(p.toInt(), ((p % 1) * 60).toInt())
    }
}
