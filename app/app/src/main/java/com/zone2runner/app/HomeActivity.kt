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
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.launch

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

    private companion object {
        var onboardingShown = false
        var nanoAutoStarted = false // 프로세스당 자동 다운로드 1회(onResume 재빌드 시 중복 시작 방지, adr-027)
    }

    override fun onResume() {
        super.onResume()
        setContentView((buildUi()).withSystemBarInsets()) // 프로필/기록 변경 반영
        // 구 /zones prior 동기화(ZoneSync)는 adr-023으로 삭제 — 존은 러닝 중 /run/live로만 전달
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
        col.addView(title("Zone2Runner"))
        col.addView(subtitle("개인화 유산소(Zone 2) 러닝 코칭 · 온디바이스 AI"))

        // AI 모델 준비 배너(adr-027): 미다운로드면 자동 다운로드 + 진행률. 준비 전에도 규칙 코칭은 무중단.
        col.addView(nanoModelBanner())

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
        // 가짜 라이브(Mock)는 수동 러너 시뮬(spec-022)이 상위호환이라 제거(2026-07-08 사용자 결정)
        col.addView(bigButton("기록 보기", Palette.CARD) {
            startActivity(Intent(this, HistoryActivity::class.java))
        })
        col.addView(bigButton("프로필 설정", Palette.CARD) {
            startActivity(Intent(this, ProfileActivity::class.java))
        })
        col.addView(bigButton("설정", Palette.CARD) {
            startActivity(Intent(this, SettingsActivity::class.java))
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
                    "느낌과 판정이 다르면 러닝 중 솔직히 답하세요 — 벅찬데 아직 Zone 2로 나오면 '벅참'이 상한을 낮추고, 편한데 초과로 나오면 '편함'이 상한을 올립니다."
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
                recreate() // 홈 갱신(목표 심박/개인화 반영)
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

    // ---- AI 모델 준비 배너 (adr-027) ----

    /**
     * Nano 기능 모델 상태를 확인하고, 다운로드가 필요하면 자동으로 시작해 진행률을 보여준다.
     * 전부 사용 가능이면 배너를 아예 숨긴다. 다운로드는 홈(세션 밖)에서만 트리거 —
     * 러닝 중에는 기존 정책(AVAILABLE만 사용, 아니면 규칙 폴백) 그대로.
     */
    private fun nanoModelBanner(): View {
        val statusView = TextView(this).apply {
            textSize = 12f; setTextColor(Palette.TEXT)
        }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; max = 1000; visibility = View.GONE
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView)
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(8) })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(card("AI 코칭 모델 (Gemini Nano)", inner))
        }

        lifecycleScope.launch {
            val mgr = com.zone2runner.app.coaching.NanoModelManager(this@HomeActivity)
            val states = mgr.statusAll()
            if (states.values.all { it == com.zone2runner.app.coaching.NanoModelManager.State.AVAILABLE }) return@launch
            box.visibility = View.VISIBLE

            val downloadable = states.filterValues {
                it == com.zone2runner.app.coaching.NanoModelManager.State.DOWNLOADABLE
            }.keys.toList()
            if (downloadable.isEmpty() || nanoAutoStarted) {
                // 이미 다른 화면에서 진행 중이거나 미지원 — 상태만 요약 표시
                statusView.text = summaryOf(states) + "\n미준비 상태에서도 코칭은 규칙 문구로 정상 동작해요."
                if (states.values.any { it == com.zone2runner.app.coaching.NanoModelManager.State.DOWNLOADING }) {
                    bar.visibility = View.VISIBLE; bar.isIndeterminate = true
                }
                return@launch
            }

            nanoAutoStarted = true
            bar.visibility = View.VISIBLE
            var failed = 0
            for (f in downloadable) {
                statusView.text = "${f.label} 모델 다운로드 중… (준비 전에도 코칭은 규칙으로 동작해요)"
                bar.isIndeterminate = true
                val ok = mgr.download(f) { done, total ->
                    runOnUiThread {
                        if (total > 0) {
                            bar.isIndeterminate = false
                            bar.progress = (done * 1000 / total).toInt().coerceIn(0, 1000)
                            statusView.text = "${f.label} 모델 다운로드 중… %d / %d MB".format(
                                done / (1024 * 1024), total / (1024 * 1024))
                        }
                    }
                }
                if (!ok) failed++
            }
            val after = mgr.statusAll()
            bar.visibility = View.GONE
            statusView.text = if (after.values.all { it == com.zone2runner.app.coaching.NanoModelManager.State.AVAILABLE }) {
                "AI 코칭 모델 준비 완료 — 다음 러닝부터 Nano 표현이 적용됩니다."
            } else {
                (if (failed > 0) "일부 모델 다운로드에 실패했어요. 설정에서 다시 시도할 수 있어요.\n" else "") +
                    summaryOf(after) + "\n미준비 상태에서도 코칭은 규칙 문구로 정상 동작해요."
            }
        }
        return box
    }

    private fun summaryOf(states: Map<com.zone2runner.app.coaching.NanoModelManager.Feature, com.zone2runner.app.coaching.NanoModelManager.State>): String =
        states.entries.joinToString(" / ") {
            "${it.key.label}: ${com.zone2runner.app.coaching.NanoModelManager.stateLabel(it.value)}"
        }
}
