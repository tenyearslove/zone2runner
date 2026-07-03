package com.zone2runner.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zone2runner.app.data.SessionStore
import com.zone2runner.app.ui.Palette
import com.zone2runner.app.ui.ReportHolder
import com.zone2runner.app.ui.card
import com.zone2runner.app.ui.dpi
import com.zone2runner.app.ui.statTile
import com.zone2runner.app.ui.subtitle
import com.zone2runner.app.ui.title
import com.zone2runner.app.ui.withSystemBarInsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 기록 — 저장된 세션 목록. 탭하면 리포트, 길게 누르면 삭제. */
class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView((buildUi()).withSystemBarInsets())
    }

    override fun onResume() { super.onResume(); setContentView((buildUi()).withSystemBarInsets()) }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.BG)
            setPadding(dpi(18), dpi(24), dpi(18), dpi(28))
        }
        col.addView(title("러닝 기록"))

        val sessions = SessionStore.listSummaries(this)
        col.addView(subtitle("총 ${sessions.size}개 세션 · 탭하여 상세, 길게 눌러 삭제"))

        if (sessions.isEmpty()) {
            col.addView(card(null, TextView(this).apply {
                text = "저장된 러닝이 없어요. 홈에서 러닝을 시작하면 여기에 쌓입니다."
                textSize = 13f; setTextColor(Palette.MUTED)
            }))
        } else {
            for (s in sessions) col.addView(sessionCard(s))
        }
        return ScrollView(this).apply { setBackgroundColor(Palette.BG); addView(col) }
    }

    private fun sessionCard(s: SessionStore.Summary): View {
        val df = SimpleDateFormat("yyyy.MM.dd (E) HH:mm", Locale.KOREAN)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@HistoryActivity).apply {
                text = df.format(Date(s.startedAtEpochMs)); textSize = 12f; setTextColor(Palette.MUTED)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(this@HistoryActivity).apply {
                text = if (s.usedModel) "MLP" else "규칙"; textSize = 10f
                setTextColor(if (s.usedModel) Palette.ACCENT else Palette.MUTED)
            })
        })
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(4), 0, 0)
            addView(statTile(fmtDist(s.distanceM), "거리"), cell())
            addView(statTile("%02d:%02d".format(s.durationSec / 60, s.durationSec % 60), "시간"), cell())
            addView(statTile("${s.avgHr}", "평균 HR"), cell())
            addView(statTile("${s.zone2Pct}%", "Zone 2", Palette.ACCENT), cell())
        })
        val c = card(null, box)
        c.isClickable = true
        c.setOnClickListener {
            SessionStore.load(this, s.id)?.let {
                ReportHolder.last = it
                startActivity(Intent(this, ReportActivity::class.java))
            } ?: Toast.makeText(this, "세션을 열 수 없어요", Toast.LENGTH_SHORT).show()
        }
        c.setOnLongClickListener {
            SessionStore.delete(this, s.id)
            Toast.makeText(this, "삭제됨", Toast.LENGTH_SHORT).show()
            setContentView((buildUi()).withSystemBarInsets()); true
        }
        return c
    }

    private fun cell() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
    private fun fmtDist(m: Double) = if (m < 1000) "${m.toInt()}m" else "%.2fkm".format(m / 1000)
}
