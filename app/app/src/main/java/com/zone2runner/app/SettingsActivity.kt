package com.zone2runner.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
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
 * 변경 즉시 SharedPreferences에 저장(다음 러닝부터 반영). 프로필과 분리된 앱 전역 설정.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var s: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = SettingsStore.load(this)
        setContentView(buildUi().withSystemBarInsets())
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

        // 음성 코칭
        col.addView(card(
            "음성 코칭",
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(switchRow("음성(TTS)으로 코칭 읽어주기", s.voiceEnabled) { on ->
                    s = s.copy(voiceEnabled = on); persist()
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = "음성 속도"; textSize = 12f; setTextColor(Palette.MUTED)
                    setPadding(0, dpi(10), 0, dpi(6))
                })
                addView(segmented(AppSettings.RATE_LABELS, s.voiceRate) { i ->
                    s = s.copy(voiceRate = i); persist()
                })
            },
        ))

        // 코칭 동작
        col.addView(card(
            "코칭 동작",
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(switchRow("선제 코칭 (60초 뒤 예측으로 미리 알림)", s.preemptiveEnabled) { on ->
                    s = s.copy(preemptiveEnabled = on); persist()
                })
                addView(divider())
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

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Palette.STROKE)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dpi(1)).apply { topMargin = dpi(6); bottomMargin = dpi(6) }
    }
}
