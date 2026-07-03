package com.zone2runner.app.pipeline

import com.zone2runner.app.coaching.Coach
import com.zone2runner.app.coaching.CoachContext
import com.zone2runner.app.domain.LiveState
import com.zone2runner.app.domain.Profile
import com.zone2runner.app.domain.RunReport
import com.zone2runner.app.domain.Sample
import com.zone2runner.app.domain.SeriesPoint
import com.zone2runner.app.domain.TrackPoint
import com.zone2runner.app.domain.ZoneJudgment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 전체 러닝 파이프라인 오케스트레이터 (arch/architecture-overview 데이터 흐름).
 *   샘플 → 이상치 가드 → 특징추출 → (MLP 판정 | 규칙 폴백) → 개인화 갱신 → 코칭 → 세션 누적
 * 시뮬레이터/실기기 공통. onSample을 1Hz로 호출.
 *
 * coachScope를 주면 코칭 생성(LLM ~2초)을 샘플 루프와 분리해 비동기로 돌린다 —
 * 코칭을 await하면 그동안 샘플 처리/렌더가 통째로 멈춰 화면이 끊긴다.
 * null이면 동기 await(단위 테스트의 결정성용).
 */
class RunEngine(
    private val profile: Profile,
    private val classifier: Zone2Classifier?, // null이면 규칙 폴백
    private val coach: Coach,
    private val coachScope: CoroutineScope? = null,
) {
    private val extractor = FeatureExtractor()
    private val personalization = Personalization(profile)
    private val uEstStart = personalization.boundary().uFrac

    private var lastValidHr: Int? = null
    private var judgment: ZoneJudgment? = null

    // 누적
    private var elapsed = 0
    private var distanceM = 0.0
    private var hrSum = 0L
    private var hrCount = 0
    private var maxHr = 0
    private var belowSec = 0
    private var inSec = 0
    private var aboveSec = 0
    private val track = ArrayList<TrackPoint>()
    private val series = ArrayList<SeriesPoint>()
    private val coachingLines = ArrayList<String>()
    var coachSource: String = "rule"

    // 코칭/개인화 타이밍
    private var lastCoachSec = -999
    private var lastJudgmentForCoach: ZoneJudgment? = null
    private var lastPersonalizeSec = 0
    private val obsCandidates = ArrayList<Double>() // decoupling 임계 부근 지속HR(bpm)

    val usingModel: Boolean get() = classifier != null

    /** 1Hz 샘플 처리. 필요 시 coach.say 호출(suspend). LiveState 반환. */
    suspend fun onSample(s: Sample): LiveState {
        elapsed = s.tSec + 1
        val clean = OutlierGuard.clean(s.hr, lastValidHr)
        if (clean == null) return liveState(s) // 아직 유효 HR 없음
        lastValidHr = clean

        // 누적 지표
        hrSum += clean; hrCount++; if (clean > maxHr) maxHr = clean
        val mps = 16.667 / s.paceMinKm.coerceAtLeast(0.1)
        distanceM += mps
        extractor.add(clean.toDouble(), s.paceMinKm, s.spm, s.slopePct)

        val b = personalization.boundary()
        val feat = extractor.extractAt(s.tSec, profile, b.uFrac, b.lFrac)
        if (feat != null) {
            lastFeat = feat // 대시보드 표시용(드리프트/심박 추세)
            judgment = classifier?.classify(feat)?.judgment ?: Zone2Classifier.ruleClassify(feat)
            // 개인화 관측 후보: decoupling(=feat[5]) 임계 부근의 지속 HR
            val hrFrac = feat[0] + b.uFrac
            val hrRecent = profile.restingHr + hrFrac * profile.hrr
            if (feat[5] in 0.03..0.10) obsCandidates += hrRecent
            // 코칭: 판정이 바뀌고 최소 간격 지난 경우
            maybeCoach(s)
        }
        // 존 체류 시간(초 단위 누적)
        when (judgment) {
            ZoneJudgment.BELOW -> belowSec++
            ZoneJudgment.ABOVE -> aboveSec++
            ZoneJudgment.IN -> inSec++
            null -> {}
        }
        // 경로 + 시계열(3초마다 다운샘플)
        if (s.tSec % 3 == 0) {
            track += TrackPoint(s.lat, s.lon, judgment)
            series += SeriesPoint(s.tSec, clean, s.paceMinKm, judgment?.index ?: -1)
        }

        // 개인화 갱신(5분마다)
        if (s.tSec - lastPersonalizeSec >= 300 && obsCandidates.isNotEmpty()) {
            personalization.update(median(obsCandidates))
            obsCandidates.clear()
            lastPersonalizeSec = s.tSec
        }
        return liveState(s)
    }

    private suspend fun maybeCoach(s: Sample) {
        val j = judgment ?: return
        val changed = j != lastJudgmentForCoach
        if (!changed || s.tSec - lastCoachSec < 20) return
        val scope = coachScope
        if (scope != null && coachJob?.isActive == true) return // 이전 생성이 아직 진행 중이면 이번 트리거는 건너뜀
        lastCoachSec = s.tSec
        lastJudgmentForCoach = j
        val ctx = CoachContext(j, s.slopePct, s.paceMinKm, s.tSec)
        if (scope == null) {
            recordCoaching(s.tSec, coach.say(ctx), 0L)
        } else {
            // coachScope는 메인 디스패처(lifecycleScope) 가정 — recordCoaching이 onSample과 같은 스레드에서 실행됨
            coachJob = scope.launch {
                val t0 = System.currentTimeMillis()
                val line = coach.say(ctx)
                recordCoaching(ctx.elapsedSec, line, System.currentTimeMillis() - t0)
            }
        }
    }

    /** 코칭 라인 확정 시 호출(비동기 생성 완료 시점). 필드 로그(spec-012)용. */
    var onCoachingRecorded: ((tSec: Int, line: String, tookMs: Long) -> Unit)? = null

    private fun recordCoaching(tSec: Int, line: String, tookMs: Long) {
        coachingLines += "[%02d:%02d] %s".format(tSec / 60, tSec % 60, line)
        lastCoachText = line
        onCoachingRecorded?.invoke(tSec, line, tookMs)
    }

    private var coachJob: Job? = null
    private var lastCoachText = ""
    private var lastFeat: DoubleArray? = null

    private fun liveState(s: Sample) = LiveState(
        elapsedSec = elapsed,
        hr = lastValidHr ?: -1,
        judgment = judgment,
        paceMinKm = s.paceMinKm,
        speedKmh = if (s.paceMinKm > 0.1) 60.0 / s.paceMinKm else 0.0,
        distanceM = distanceM,
        coaching = lastCoachText,
        uEstFrac = personalization.boundary().uFrac,
        slopePct = s.slopePct,
        spm = s.spm,
        decoupling = lastFeat?.get(5), // 특징 벡터 규약(spec-006 §1): [.., dHR(2), .., decoupling(5), ..]
        dHrPerSec = lastFeat?.get(2),
    )

    fun report(): RunReport = RunReport(
        durationSec = elapsed,
        distanceM = distanceM,
        avgHr = if (hrCount > 0) (hrSum / hrCount).toInt() else 0,
        maxHr = maxHr,
        belowSec = belowSec, inSec = inSec, aboveSec = aboveSec,
        avgPaceMinKm = if (distanceM > 1) (elapsed / 60.0) / (distanceM / 1000.0) else 0.0,
        coachingLines = coachingLines.toList(),
        track = track.toList(),
        uEstStartFrac = uEstStart,
        uEstEndFrac = personalization.boundary().uFrac,
        restingHr = profile.restingHr,
        maxHrProfile = profile.maxHr,
        series = series.toList(),
        usedModel = usingModel,
        coachSource = coachSource,
    )

    private fun median(a: List<Double>): Double {
        val s = a.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
}
