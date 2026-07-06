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

        // 인증 과제 타이틀(사용자 요청): 프로그램명 + 앱명
        col.addView(TextView(this).apply {
            text = "AI Specialist"; textSize = 12f; setTextColor(Palette.ACCENT)
            letterSpacing = 0.12f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
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
        val B = com.zone2runner.app.domain.Zone2Prior.BAND
        val prior = com.zone2runner.app.domain.Zone2Prior.of(p) // 공식(factor) prior, %HRmax 기준(spec-013)
        // 세션 누적 개인화 학습값(LearnedZone = 온라인 Bayesian 최종 경계). 없으면 공식 prior.
        val learned = com.zone2runner.app.data.LearnedZone.uFrac(this)
        val uFrac = learned ?: prior.uFrac0
        val nSess = com.zone2runner.app.data.LearnedZone.sessionCount(this)
        val lo = (p.restingHr + (uFrac - B) * p.hrr).toInt()
        val hi = (p.restingHr + uFrac * p.hrr).toInt()
        // 공식 고정 기준(학습 전 값)
        val fLo = (p.restingHr + (prior.uFrac0 - B) * p.hrr).toInt()
        val fHi = (p.restingHr + prior.uFrac0 * p.hrr).toInt()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 프로필 선택기(러닝 시작 전 어떤 프로필로 뛸지 고름, spec-020). 여러 개면 탭해서 전환.
        grid.addView(profileSelectorRow())
        grid.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(statTile("${p.age}세", "나이"), cell())
            addView(statTile("${p.restingHr}", "안정 심박"), cell())
            addView(statTile("${p.maxHr}", "최대 심박"), cell())
        })
        grid.addView(TextView(this).apply {
            text = "Zone 2 목표 심박: $lo ~ $hi bpm" + if (learned != null) "  (${nSess}회 러닝 학습 반영)" else ""
            textSize = 13f; setTextColor(Palette.ACCENT); setPadding(0, dpi(8), 0, 0)
        })
        // 공식 기준 대비 조정폭 + 근거
        if (learned != null) {
            val dHi = hi - fHi
            val dir = when { dHi > 0 -> "상단 +$dHi bpm 상향"; dHi < 0 -> "상단 ${dHi} bpm 하향"; else -> "변동 없음" }
            grid.addView(TextView(this).apply {
                text = "프로필 공식 기준: $fLo ~ $fHi bpm  →  학습 후 $dir\n" +
                    "근거: 실주행 관측 누적(말하기 테스트 + 심박·속도 드리프트)을 Bayesian으로 갱신.\n" +
                    "편한데 미달로 나오면 러닝 중 '편함'을 누르면 다음 세션부터 내려갑니다."
                textSize = 11f; setTextColor(Palette.MUTED); setPadding(0, dpi(4), 0, 0)
            })
        } else {
            grid.addView(TextView(this).apply {
                text = "프로필 기반 초기값(공식) — 러닝하며 관측이 쌓이면 개인에 맞게 보정됩니다."
                textSize = 11f; setTextColor(Palette.MUTED); setPadding(0, dpi(4), 0, 0)
            })
        }
        return grid
    }

    /** 러닝 프로필 선택기 — 활성 프로필명 표시 + 탭하면 전환(여러 개일 때) 또는 관리 화면. */
    private fun profileSelectorRow(): View {
        val profiles = com.zone2runner.app.data.Profiles.list(this)
        val activeName = com.zone2runner.app.data.Profiles.activeName(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpi(8))
            addView(TextView(this@HomeActivity).apply {
                text = "러닝 프로필"; textSize = 12f; setTextColor(Palette.MUTED)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(this@HomeActivity).apply {
                text = "$activeName ▾"; textSize = 13f; setTextColor(Palette.ACCENT)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dpi(12), dpi(6), dpi(12), dpi(6))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Palette.BG); cornerRadius = dpi(14).toFloat()
                    setStroke(dpi(1), Palette.STROKE)
                }
                isClickable = true
                setOnClickListener {
                    if (profiles.size <= 1) startActivity(Intent(this@HomeActivity, ProfileActivity::class.java))
                    else showProfilePicker(profiles)
                }
            })
        }
    }

    private fun showProfilePicker(profiles: List<com.zone2runner.app.data.Profiles.Entry>) {
        val activeId = com.zone2runner.app.data.Profiles.activeId(this)
        val names = profiles.map { (if (it.id == activeId) "● " else "○ ") + it.name }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("이 프로필로 러닝")
            .setItems(names) { _, which ->
                com.zone2runner.app.data.Profiles.setActive(this, profiles[which].id)
                recreate() // 홈 갱신(목표 심박/개인화 반영) + onResume에서 ZoneSync 재푸시
            }
            .setNeutralButton("프로필 관리") { _, _ -> startActivity(Intent(this, ProfileActivity::class.java)) }
            .setNegativeButton("닫기", null).show()
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
