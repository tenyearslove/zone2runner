package com.zone2runner.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zone2runner.app.data.AppSettings
import com.zone2runner.app.data.SettingsStore
import com.zone2runner.app.ui.Palette
import com.zone2runner.app.ui.bigButton
import com.zone2runner.app.ui.card
import com.zone2runner.app.ui.dpi
import com.zone2runner.app.ui.withSystemBarInsets

/**
 * 앱 설정 화면(spec-021) — 코칭 빈도 5단계 + 음성/선제/더위/화면 환경설정.
 * spec-024: 목소리(TTS 보이스) 선택+미리듣기, 음 높낮이, 코칭 말투(페르소나).
 * 변경 즉시 SharedPreferences에 저장(다음 러닝부터 반영). 프로필과 분리된 앱 전역 설정.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var s: AppSettings
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = SettingsStore.load(this)
        // 보이스 열거/미리듣기용 로컬 TTS(spec-024 FR1). 실패해도 화면은 동작(항목만 기본 표시)
        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.KOREAN
                ttsReady = true
                runOnUiThread { refreshVoiceValue() }
            }
        }
        setContentView(buildUi().withSystemBarInsets())
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown(); tts = null
        super.onDestroy()
    }

    private fun persist() = SettingsStore.save(this, s)

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dpi(16), dpi(14), dpi(16), dpi(24))
        }

        col.addView(TextView(this).apply {
            text = "설정"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.TEXT)
        })

        // 코칭 빈도 (5단계)
        col.addView(card(
            "코칭 빈도",
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(segmented(AppSettings.FREQ_LABELS, s.coachFrequency) { i ->
                    s = s.copy(coachFrequency = i); persist(); refreshFreqHint()
                })
                addView(freqHint())
            },
        ))

        // 음성 코칭 (spec-021 + spec-024 보이스/높낮이/말투)
        col.addView(card(
            "음성 코칭",
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(switchRow("음성(TTS)으로 코칭 읽어주기", s.voiceEnabled) { on ->
                    s = s.copy(voiceEnabled = on); persist(); refreshVoiceDim()
                })
                voiceDetail = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(sectionLabel("음성 속도"))
                    addView(segmented(AppSettings.RATE_LABELS, s.voiceRate) { i ->
                        s = s.copy(voiceRate = i); persist(); preview()
                    })
                    addView(sectionLabel("음 높낮이"))
                    addView(segmented(AppSettings.PITCH_LABELS, s.voicePitch) { i ->
                        s = s.copy(voicePitch = i); persist(); preview()
                    })
                    addView(sectionLabel("목소리"))
                    addView(voiceRow())
                }
                addView(voiceDetail)
                addView(sectionLabel("코칭 말투"))
                addView(segmented(AppSettings.PERSONA_LABELS, s.coachPersona) { i ->
                    s = s.copy(coachPersona = i); persist(); refreshPersonaHint()
                })
                addView(personaHint())
            },
        ))
        refreshVoiceDim()

        // 코칭 동작
        col.addView(card(
            "코칭 동작",
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(switchRow("더위 코칭 (기온 반영)", s.heatCoachingEnabled) { on ->
                    s = s.copy(heatCoachingEnabled = on); persist()
                })
            },
        ))

        // 화면
        col.addView(card(
            "화면",
            switchRow("러닝 중 화면 항상 켜기", s.keepScreenOn) { on ->
                s = s.copy(keepScreenOn = on); persist()
            },
        ))

        col.addView(TextView(this).apply {
            text = "설정은 자동 저장되며 다음 러닝부터 적용됩니다."
            textSize = 11f; setTextColor(Palette.MUTED); setPadding(dpi(2), dpi(12), 0, 0)
        })
        col.addView(bigButton("닫기", Palette.CARD) { finish() })

        return ScrollView(this).apply { setBackgroundColor(Palette.BG); addView(col) }
    }

    // ---- 음성(spec-024) ----

    private lateinit var voiceDetail: LinearLayout
    private lateinit var voiceValueView: TextView
    private lateinit var personaHintView: TextView
    private val previewSample = "지금 페이스 좋아요. 이대로 유지하세요."

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(Palette.MUTED)
        setPadding(0, dpi(10), 0, dpi(6))
    }

    /** 러닝 중 오프라인 동작 보장(C01): 네트워크 전용 보이스 제외, 한국어만. */
    private fun koreanVoices(): List<android.speech.tts.Voice> = runCatching {
        tts?.voices.orEmpty()
            .filter { it.locale.language == "ko" && !it.isNetworkConnectionRequired }
            .sortedBy { it.name }
    }.getOrDefault(emptyList())

    /** 현재 설정으로 샘플 문장 미리듣기(속도/높낮이/보이스 반영). */
    private fun preview(voice: android.speech.tts.Voice? = null) {
        val t = tts ?: return
        if (!ttsReady) return
        runCatching {
            t.setSpeechRate(s.ttsRate); t.setPitch(s.ttsPitch)
            val v = voice ?: s.voiceName?.let { name -> koreanVoices().firstOrNull { it.name == name } }
            if (v != null) t.voice = v else t.voice = t.defaultVoice
            t.speak(previewSample, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "preview")
        }
    }

    /** 목소리 선택 행: 현재 값 + 탭하면 목록 다이얼로그(항목 탭=미리듣기, 선택=적용). */
    private fun voiceRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dpi(4), 0, dpi(4)); isClickable = true
        addView(TextView(this@SettingsActivity).apply {
            text = "코칭 목소리"; textSize = 14f; setTextColor(Palette.TEXT)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        voiceValueView = TextView(this@SettingsActivity).apply {
            textSize = 13f; setTextColor(Palette.ACCENT); text = "기기 기본"
        }
        addView(voiceValueView)
        setOnClickListener { showVoicePicker() }
    }

    private fun refreshVoiceValue() {
        if (!::voiceValueView.isInitialized) return
        val voices = koreanVoices()
        val idx = voices.indexOfFirst { it.name == s.voiceName }
        voiceValueView.text = when {
            s.voiceName == null -> "기기 기본"
            idx >= 0 -> "보이스 ${idx + 1}"
            else -> "기기 기본(선택 보이스 없음)" // 소실 시 폴백 표시(spec-024 예외)
        }
    }

    private fun showVoicePicker() {
        val voices = koreanVoices()
        if (!ttsReady || voices.isEmpty()) {
            android.widget.Toast.makeText(this, "사용 가능한 한국어 보이스가 없어 기기 기본을 사용해요", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val labels = arrayOf("기기 기본") + voices.mapIndexed { i, v -> "보이스 ${i + 1} (${v.name})" }
        var checked = voices.indexOfFirst { it.name == s.voiceName } + 1 // -1+1=0 → 기기 기본
        android.app.AlertDialog.Builder(this)
            .setTitle("코칭 목소리 (탭하면 미리듣기)")
            .setSingleChoiceItems(labels, checked) { _, which ->
                checked = which
                preview(if (which == 0) null else voices[which - 1])
            }
            .setPositiveButton("선택") { _, _ ->
                s = s.copy(voiceName = if (checked == 0) null else voices[checked - 1].name)
                persist(); refreshVoiceValue()
            }
            .setNegativeButton("취소") { _, _ -> tts?.stop() }
            .show()
    }

    /** 음성 off 시 속도/높낮이/목소리 항목 흐리게(비활성 표시 — 말투는 카드 문구에도 쓰여 유지). */
    private fun refreshVoiceDim() {
        if (::voiceDetail.isInitialized) voiceDetail.alpha = if (s.voiceEnabled) 1f else 0.35f
    }

    private fun personaHint(): TextView {
        personaHintView = TextView(this).apply {
            textSize = 11f; setTextColor(Palette.MUTED); setPadding(dpi(2), dpi(8), 0, 0)
        }
        refreshPersonaHint()
        return personaHintView
    }

    private fun refreshPersonaHint() {
        if (!::personaHintView.isInitialized) return
        val hint = AppSettings.PERSONA_HINTS.getOrElse(s.coachPersona) { "" }
        personaHintView.text = "$hint 코칭 방향과 수치는 말투와 무관하게 동일해요."
    }

    // ---- 컴포넌트 ----

    private lateinit var freqHintView: TextView
    private fun freqHint(): TextView {
        freqHintView = TextView(this).apply {
            textSize = 11f; setTextColor(Palette.MUTED); setPadding(dpi(2), dpi(8), 0, 0)
        }
        refreshFreqHint()
        return freqHintView
    }

    private fun refreshFreqHint() {
        if (!::freqHintView.isInitialized) return
        val c = s.cadence
        freqHintView.text = "존을 벗어나 머물면 약 ${c.overdueSec}초마다 재안내, 코칭 간 최소 ${c.minGapSec}초 간격."
    }

    /** 세그먼트(칩 행) — 선택 시 onSelect(index). */
    private fun segmented(labels: List<String>, initial: Int, onSelect: (Int) -> Unit): LinearLayout {
        val chips = ArrayList<TextView>()
        var selected = initial.coerceIn(0, labels.size - 1)
        fun restyle() = chips.forEachIndexed { i, c ->
            val on = i == selected
            c.setTextColor(if (on) Color.WHITE else Palette.MUTED)
            c.background = GradientDrawable().apply {
                setColor(if (on) Palette.ACCENT else Palette.BG)
                cornerRadius = dpi(9).toFloat()
                if (!on) setStroke(dpi(1), Palette.STROKE)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            labels.forEachIndexed { i, label ->
                val chip = TextView(this@SettingsActivity).apply {
                    text = label; textSize = 11f; gravity = Gravity.CENTER
                    setPadding(0, dpi(9), 0, dpi(9)); isClickable = true
                    setOnClickListener { selected = i; restyle(); onSelect(i) }
                }
                chips += chip
                addView(chip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dpi(5) })
            }
            restyle()
        }
    }

    /** 라벨 + 스위치 한 줄. */
    private fun switchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(6), 0, dpi(6))
            addView(TextView(this@SettingsActivity).apply {
                text = label; textSize = 14f; setTextColor(Palette.TEXT)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Switch(this@SettingsActivity).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, on -> onChange(on) }
            })
        }

}
