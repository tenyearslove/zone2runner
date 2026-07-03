package com.zone2runner.app

import android.graphics.drawable.GradientDrawable
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
import com.zone2runner.app.domain.Zone2Prior
import com.zone2runner.app.ui.Palette
import com.zone2runner.app.ui.bigButton
import com.zone2runner.app.ui.card
import com.zone2runner.app.ui.dpi
import com.zone2runner.app.ui.subtitle
import com.zone2runner.app.ui.title
import com.zone2runner.app.ui.withSystemBarInsets

/**
 * 프로필 설정 (spec-009 + spec-013).
 * 나이/안정심박/최대심박 + 키/몸무게 + 단계형 factor(체형/러닝 수준/주간 빈도)를 입력받아
 * 콜드스타트 Zone2 prior(adr-012)를 개인 근사치로 시작한다.
 * 하단 미리보기가 입력 변화 → 초기 Zone2 범위 변화를 실시간으로 보여준다.
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var ageIn: EditText
    private lateinit var rhrIn: EditText
    private lateinit var maxIn: EditText
    private lateinit var heightIn: EditText
    private lateinit var weightIn: EditText
    private lateinit var preview: TextView
    private lateinit var bmiHint: TextView

    private lateinit var bodyChips: ChipRow
    private lateinit var fitnessChips: ChipRow
    private lateinit var freqChips: ChipRow
    private var bodyTouched = false // 사용자가 체형을 직접 고르면 BMI 자동 제안이 덮어쓰지 않음

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 이미 저장된 프로필이 있으면 체형 자동 제안이 사용자의 선택을 덮어쓰지 않는다(spec-013)
        bodyTouched = ProfileStore.isConfigured(this)
        setContentView((buildUi()).withSystemBarInsets())
        updatePreview()
    }

    private fun buildUi(): View {
        val p = ProfileStore.load(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.BG)
            setPadding(dpi(18), dpi(24), dpi(18), dpi(28))
        }
        col.addView(title("프로필 설정"))
        col.addView(subtitle("입력할수록 첫 러닝부터 Zone 2 판정이 내 몸에 가까워집니다"))

        ageIn = numField(p.age.toString())
        rhrIn = numField(p.restingHr.toString())
        maxIn = numField(ProfileStore.maxHrOverride(this).toString())
        heightIn = numField(p.heightCm.toString())
        weightIn = numField(p.weightKg.toString())

        col.addView(card("심박 정보", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(labeled("나이 (세)", ageIn))
            addView(labeled("안정 심박 (0=자동)", rhrIn))
            addView(labeled("최대 심박 (0=자동)", maxIn))
            addView(TextView(this@ProfileActivity).apply {
                textSize = 10f; setTextColor(Palette.MUTED); setPadding(0, dpi(4), 0, 0)
                text = "안정 심박은 기상 직후 측정값이 가장 정확해요. 0이면 러닝 수준으로 자동 추정합니다"
            })
        }))

        bmiHint = TextView(this).apply { textSize = 11f; setTextColor(Palette.MUTED); setPadding(0, dpi(4), 0, 0) }
        col.addView(card("신체 정보", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(labeled("키 (cm)", heightIn))
            addView(labeled("몸무게 (kg)", weightIn))
            addView(bmiHint)
        }))

        bodyChips = ChipRow(Zone2Prior.BODY_LABELS, p.bodyType - 1) { bodyTouched = true; updatePreview() }
        fitnessChips = ChipRow(Zone2Prior.FITNESS_LABELS, p.fitnessLevel - 1) { updatePreview() }
        freqChips = ChipRow(Zone2Prior.FREQ_LABELS, p.weeklyFreq - 1) { updatePreview() }

        col.addView(card("체형", bodyChips.view))
        col.addView(card("러닝 수준", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(fitnessChips.view)
            addView(TextView(this@ProfileActivity).apply {
                textSize = 10f; setTextColor(Palette.MUTED); setPadding(0, dpi(6), 0, 0)
                text = Zone2Prior.FITNESS_LABELS.zip(Zone2Prior.FITNESS_HINTS)
                    .joinToString("  /  ") { "${it.first}=${it.second}" }
            })
        }))
        col.addView(card("주간 운동 빈도", freqChips.view))

        preview = TextView(this).apply { textSize = 13f; setTextColor(Palette.ACCENT) }
        col.addView(card("초기 Zone 2 미리보기", preview))

        col.addView(bigButton("저장", Palette.ACCENT) { save() })
        col.addView(bigButton("취소", Palette.CARD) { finish() })
        return ScrollView(this).apply { setBackgroundColor(Palette.BG); addView(col) }
    }

    // ---- 5단계 칩 행 ----

    private inner class ChipRow(labels: List<String>, initial: Int, val onSelect: () -> Unit) {
        var selected = initial.coerceIn(0, labels.size - 1)
            private set
        private val chips = ArrayList<TextView>()
        val view: LinearLayout = LinearLayout(this@ProfileActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            labels.forEachIndexed { i, label ->
                val chip = TextView(this@ProfileActivity).apply {
                    text = label; textSize = 11f; gravity = Gravity.CENTER
                    setPadding(0, dpi(9), 0, dpi(9))
                    isClickable = true
                    setOnClickListener { select(i); onSelect() }
                }
                chips += chip
                addView(chip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dpi(5)
                })
            }
            restyle()
        }

        fun select(i: Int) { selected = i.coerceIn(0, chips.size - 1); restyle() }

        private fun restyle() {
            chips.forEachIndexed { i, c ->
                val on = i == selected
                c.setTextColor(if (on) android.graphics.Color.WHITE else Palette.MUTED)
                c.background = GradientDrawable().apply {
                    setColor(if (on) Palette.ACCENT else Palette.BG)
                    cornerRadius = dpi(9).toFloat()
                    if (!on) setStroke(dpi(1), Palette.STROKE)
                }
            }
        }
    }

    // ---- 입력/미리보기 ----

    private fun numField(value: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value)
        setTextColor(Palette.TEXT); setHintTextColor(Palette.MUTED); textSize = 18f
        gravity = Gravity.END
        addTextChangedListenerCompat { updatePreview() }
    }

    private fun labeled(label: String, field: EditText): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dpi(4), 0, dpi(4))
        addView(TextView(this@ProfileActivity).apply {
            text = label; textSize = 14f; setTextColor(Palette.TEXT)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(field, LinearLayout.LayoutParams(dpi(90), WRAP_CONTENT))
    }

    private fun currentProfile(): Profile {
        val age = ageIn.text.toString().toIntOrNull()?.coerceIn(10, 100) ?: 35
        val fitness = fitnessChips.selected + 1
        val freq = freqChips.selected + 1
        val rhrRaw = rhrIn.text.toString().toIntOrNull() ?: 0
        val rhrUnknown = rhrRaw <= 0
        val rhr = if (rhrUnknown) Zone2Prior.estimateRhr(fitness, freq) else rhrRaw.coerceIn(30, 120)
        val override = maxIn.text.toString().toIntOrNull() ?: 0
        val maxHr = if (override > 0) override.coerceIn(120, 230) else (208 - 0.7 * age).toInt()
        return Profile(
            age, rhr, maxHr,
            heightCm = heightIn.text.toString().toIntOrNull()?.coerceIn(100, 230) ?: 170,
            weightKg = weightIn.text.toString().toIntOrNull()?.coerceIn(25, 250) ?: 70,
            bodyType = bodyChips.selected + 1,
            fitnessLevel = fitness,
            weeklyFreq = freq,
            rhrEstimated = rhrUnknown,
        )
    }

    private fun updatePreview() {
        val p = currentProfile()
        // BMI → 체형 제안 (사용자가 직접 고르기 전까지만 자동 반영)
        val bmi = Zone2Prior.bmi(p.heightCm, p.weightKg)
        val suggest = Zone2Prior.suggestBodyType(p.heightCm, p.weightKg)
        if (bmi != null && suggest != null) {
            bmiHint.text = "BMI %.1f → 체형 제안: %s".format(bmi, Zone2Prior.BODY_LABELS[suggest - 1])
            if (!bodyTouched && bodyChips.selected != suggest - 1) {
                bodyChips.select(suggest - 1)
            }
        } else bmiHint.text = "키/몸무게를 입력하면 체형을 제안합니다"

        val cur = currentProfile() // 체형 자동 반영 후 재계산
        val prior = Zone2Prior.of(cur)
        val hi = (cur.restingHr + prior.uFrac0 * cur.hrr).toInt()
        val lo = (cur.restingHr + (prior.uFrac0 - Zone2Prior.BAND) * cur.hrr).toInt()
        val rhrLine = if (cur.rhrEstimated) "안정 심박 자동 추정: ${cur.restingHr} bpm (러닝 수준 기반)\n" else ""
        preview.text = rhrLine +
            "초기 Zone 2: $lo ~ $hi bpm  (신뢰도 ±%.0f bpm)\n".format(prior.sigma0Bpm) +
            "최대 심박 ${cur.maxHr} · HRR ${cur.hrr.toInt()} · 상한 %.1f%% HRR (기본 70%% %+.1f%%p)\n".format(
                prior.uFrac0 * 100, prior.offset * 100) +
            "러닝을 반복할수록 실측 데이터로 이 범위가 보정됩니다"
    }

    private fun save() {
        val age = ageIn.text.toString().toIntOrNull()?.coerceIn(10, 100)
        if (age == null) {
            Toast.makeText(this, "나이를 입력하세요", Toast.LENGTH_SHORT).show(); return
        }
        // RHR 0/공백 = 모름 → 0으로 저장(로드 시 factor 기반 추정, spec-013)
        val rhr = (rhrIn.text.toString().toIntOrNull() ?: 0).let { if (it > 0) it.coerceIn(30, 120) else 0 }
        val override = (maxIn.text.toString().toIntOrNull() ?: 0).let { if (it > 0) it.coerceIn(120, 230) else 0 }
        val p = currentProfile()
        ProfileStore.save(
            this, age, rhr, override,
            heightCm = p.heightCm, weightKg = p.weightKg,
            bodyType = p.bodyType, fitnessLevel = p.fitnessLevel, weeklyFreq = p.weeklyFreq,
        )
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
