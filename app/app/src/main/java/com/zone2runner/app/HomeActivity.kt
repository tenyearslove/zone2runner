package com.zone2runner.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zone2runner.app.data.ProfileStore
import com.zone2runner.app.data.SessionStore
import com.zone2runner.app.domain.Profile
import com.zone2runner.app.ui.Palette
import com.zone2runner.app.ui.bigButton
import com.zone2runner.app.ui.card
import com.zone2runner.app.ui.dpi
import com.zone2runner.app.ui.statTile
import com.zone2runner.app.ui.subtitle
import com.zone2runner.app.ui.title
import com.zone2runner.app.ui.withSystemBarInsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 홈 — 앱 진입점. 프로필 요약 + Zone2 목표심박 + 최근 세션 + 시작/기록/프로필 이동.
 * 전체 플로우: 홈 → 러닝(RunActivity) → 리포트(ReportActivity), 홈 ↔ 기록/프로필.
 */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView((buildUi()).withSystemBarInsets())
        // 첫 실행 온보딩: 프로필 미설정이면 프로세스당 1회 프로필 화면 안내
        if (!ProfileStore.isConfigured(this) && !onboardingShown) {
            onboardingShown = true
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }

    private companion object { var onboardingShown = false }

    override fun onResume() {
        super.onResume()
        setContentView((buildUi()).withSystemBarInsets()) // 프로필/기록 변경 반영
        com.zone2runner.app.data.ZoneSync.push(this) // 워치 존 기준 동기화(프로필 변경 반영, fire-and-forget)
    }

    private fun buildUi(): View {
        val profile = ProfileStore.load(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.BG)
            setPadding(dpi(18), dpi(24), dpi(18), dpi(28))
        }

        col.addView(title("Zone2 Runner"))
        col.addView(subtitle("개인화 유산소(Zone 2) 러닝 코칭 · 온디바이스 AI"))

        // 프로필 + 목표 심박 카드
        col.addView(card("내 프로필 / Zone 2 목표", profileCard(profile)))

        // 최근 세션
        val recent = SessionStore.listSummaries(this).firstOrNull()
        val recentContent = if (recent == null) {
            TextView(this).apply {
                text = "아직 기록이 없어요. 첫 러닝을 시작해 보세요."
                textSize = 13f; setTextColor(Palette.MUTED)
            }
        } else recentCard(recent)
        col.addView(card("최근 러닝", recentContent))

        // 액션 버튼
        col.addView(bigButton("러닝 시작 (시뮬레이션)", Palette.ACCENT) {
            startActivity(Intent(this, RunActivity::class.java).putExtra(RunActivity.EXTRA_MODE, RunActivity.MODE_SIM))
        })
        col.addView(bigButton("실센서 러닝 (GPS+워치)", Palette.CARD) {
            startActivity(Intent(this, RunActivity::class.java).putExtra(RunActivity.EXTRA_MODE, RunActivity.MODE_LIVE))
        })
        col.addView(bigButton("가짜 라이브 러닝 (테스트)", Palette.CARD) {
            startActivity(Intent(this, MockConfigActivity::class.java))
        })
        col.addView(bigButton("기록 보기", Palette.CARD) {
            startActivity(Intent(this, HistoryActivity::class.java))
        })
        col.addView(bigButton("프로필 설정", Palette.CARD) {
            startActivity(Intent(this, ProfileActivity::class.java))
        })

        if (!ProfileStore.isConfigured(this)) {
            col.addView(TextView(this).apply {
                text = "먼저 프로필을 설정하면 개인 심박 기준으로 더 정확히 판정해요."
                textSize = 11f; setTextColor(Palette.AMBER); setPadding(dpi(4), dpi(12), 0, 0)
            })
        }

        return ScrollView(this).apply { setBackgroundColor(Palette.BG); addView(col) }
    }

    private fun profileCard(p: Profile): View {
        val prior = com.zone2runner.app.domain.Zone2Prior.of(p) // factor 반영 prior(spec-013) — 프로필 화면과 일치
        val lo = (p.restingHr + (prior.uFrac0 - com.zone2runner.app.domain.Zone2Prior.BAND) * p.hrr).toInt()
        val hi = (p.restingHr + prior.uFrac0 * p.hrr).toInt()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(statTile("${p.age}세", "나이"), cell())
            addView(statTile("${p.restingHr}", "안정 심박"), cell())
            addView(statTile("${p.maxHr}", "최대 심박"), cell())
        })
        grid.addView(TextView(this).apply {
            text = "Zone 2 목표 심박: $lo ~ $hi bpm (프로필 기반 초기값, 러닝마다 보정)"
            textSize = 13f; setTextColor(Palette.ACCENT); setPadding(0, dpi(8), 0, 0)
        })
        return grid
    }

    private fun recentCard(s: SessionStore.Summary): View {
        val df = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREAN)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = df.format(Date(s.startedAtEpochMs)); textSize = 12f; setTextColor(Palette.MUTED)
        })
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(4), 0, 0)
            addView(statTile(fmtDist(s.distanceM), "거리"), cell())
            addView(statTile("%02d:%02d".format(s.durationSec / 60, s.durationSec % 60), "시간"), cell())
            addView(statTile("${s.zone2Pct}%", "Zone 2", Palette.ACCENT), cell())
        })
        box.isClickable = true
        box.setOnClickListener {
            SessionStore.load(this, s.id)?.let {
                com.zone2runner.app.ui.ReportHolder.last = it
                startActivity(Intent(this, ReportActivity::class.java))
            }
        }
        return box
    }

    private fun cell() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
    private fun fmtDist(m: Double) = if (m < 1000) "${m.toInt()}m" else "%.2fkm".format(m / 1000)
}
