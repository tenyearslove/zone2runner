package com.zone2runner.app

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zone2runner.app.data.ProfileStore
import com.zone2runner.app.domain.Profile
import com.zone2runner.app.ui.Palette
import com.zone2runner.app.ui.bigButton
import com.zone2runner.app.ui.card
import com.zone2runner.app.ui.dpi
import com.zone2runner.app.ui.subtitle
import com.zone2runner.app.ui.title

/**
 * 프로필 설정 (spec-009). 나이/안정심박/최대심박(0=나이기반 Tanaka 자동)을 입력받아 저장.
 * 개인 Zone2 경계(공식 사전값)를 개인 신체값으로 시작하기 위한 화면.
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var ageIn: EditText
    private lateinit var rhrIn: EditText
    private lateinit var maxIn: EditText
    private lateinit var preview: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        updatePreview()
    }

    private fun buildUi(): View {
        val p = ProfileStore.load(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.BG)
            setPadding(dpi(18), dpi(24), dpi(18), dpi(28))
        }
        col.addView(title("프로필 설정"))
        col.addView(subtitle("개인 심박 기준으로 Zone 2를 더 정확히 판정합니다"))

        ageIn = numField("나이 (세)", p.age.toString())
        rhrIn = numField("안정 심박 (bpm, 아침 기상 직후)", p.restingHr.toString())
        val overrideVal = ProfileStore.maxHrOverride(this)
        maxIn = numField("최대 심박 (bpm, 0 = 나이 기반 자동)", overrideVal.toString())

        col.addView(card("신체 정보", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(labeled("나이", ageIn))
            addView(labeled("안정 심박(RHR)", rhrIn))
            addView(labeled("최대 심박(0=자동)", maxIn))
        }))

        preview = TextView(this).apply { textSize = 13f; setTextColor(Palette.ACCENT) }
        col.addView(card("Zone 2 목표 미리보기", preview))

        col.addView(bigButton("저장", Palette.ACCENT) { save() })
        col.addView(bigButton("취소", Palette.CARD) { finish() })
        return ScrollView(this).apply { setBackgroundColor(Palette.BG); addView(col) }
    }

    private fun numField(hint: String, value: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value); this.hint = hint
        setTextColor(Palette.TEXT); setHintTextColor(Palette.MUTED); textSize = 18f
        gravity = Gravity.END
        setOnFocusChangeListener { _, _ -> updatePreview() }
        addTextChangedListenerCompat { updatePreview() }
    }

    private fun labeled(label: String, field: EditText): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dpi(6), 0, dpi(6))
        addView(TextView(this@ProfileActivity).apply {
            text = label; textSize = 14f; setTextColor(Palette.TEXT)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(field, LinearLayout.LayoutParams(dpi(90), WRAP_CONTENT))
    }

    private fun currentProfile(): Profile {
        val age = ageIn.text.toString().toIntOrNull()?.coerceIn(10, 100) ?: 35
        val rhr = rhrIn.text.toString().toIntOrNull()?.coerceIn(30, 120) ?: 58
        val override = maxIn.text.toString().toIntOrNull() ?: 0
        return if (override > 0) Profile(age, rhr, override.coerceIn(120, 230)) else Profile.default(age, rhr)
    }

    private fun updatePreview() {
        val p = currentProfile()
        val lo = (p.restingHr + 0.60 * p.hrr).toInt()
        val hi = (p.restingHr + 0.70 * p.hrr).toInt()
        preview.text = "최대 심박 ${p.maxHr} bpm · Zone 2 목표 $lo ~ $hi bpm\n" +
            "(HRR = 최대 - 안정 = ${p.hrr.toInt()}, Zone2 = 안정 + 60~70% HRR)"
    }

    private fun save() {
        val age = ageIn.text.toString().toIntOrNull()?.coerceIn(10, 100)
        val rhr = rhrIn.text.toString().toIntOrNull()?.coerceIn(30, 120)
        if (age == null || rhr == null) {
            Toast.makeText(this, "나이와 안정 심박을 입력하세요", Toast.LENGTH_SHORT).show(); return
        }
        val override = (maxIn.text.toString().toIntOrNull() ?: 0).let { if (it > 0) it.coerceIn(120, 230) else 0 }
        ProfileStore.save(this, age, rhr, override)
        Toast.makeText(this, "저장되었습니다", Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** EditText 텍스트 변경 리스너 간이 헬퍼. */
private fun EditText.addTextChangedListenerCompat(cb: () -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) = cb()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
}
