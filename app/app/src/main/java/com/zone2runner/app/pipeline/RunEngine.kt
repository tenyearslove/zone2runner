package com.zone2runner.app.pipeline

import com.zone2runner.app.analysis.AnalysisConfig
import com.zone2runner.app.analysis.AnalysisEngine
import com.zone2runner.app.analysis.AnalysisInput
import com.zone2runner.app.analysis.CadenceStabilityMetric
import com.zone2runner.app.analysis.DriftSlopeMetric
import com.zone2runner.app.analysis.GapMinettiMetric
import com.zone2runner.app.analysis.HrrMetric
import com.zone2runner.app.analysis.NoiseFloor
import com.zone2runner.app.analysis.SignalBuffer
import com.zone2runner.app.analysis.SubmaxHrMetric
import com.zone2runner.app.analysis.Trend
import com.zone2runner.app.coaching.Coach
import com.zone2runner.app.coaching.CoachContext
import com.zone2runner.app.domain.LiveState
import com.zone2runner.app.domain.MPS_PER_MIN_KM
import com.zone2runner.app.domain.Profile
import com.zone2runner.app.domain.RunReport
import com.zone2runner.app.domain.Sample
import com.zone2runner.app.domain.SeriesPoint
import com.zone2runner.app.domain.TrackPoint
import com.zone2runner.app.domain.Zone2Boundary
import com.zone2runner.app.domain.ZoneJudgment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 전체 러닝 파이프라인 오케스트레이터 (arch/architecture-overview, adr-013 역할 재분리).
 *   샘플 → 이상치 가드 → [판정 = 규칙(지속 심박 vs 개인화 경계 + 히스테리시스)]
 *        → 특징추출 → 개인화 갱신 → 코칭 → 세션 누적
 * 판정과 밴드가 같은 개인화 경계를 참조하므로 모순이 구조적으로 불가능(spec-014 FR1).
 * 시뮬레이터/실기기 공통. onSample을 1Hz로 호출.
 *
 * coachScope를 주면 코칭 생성(LLM ~2초)을 샘플 루프와 분리해 비동기로 돌린다 —
 * 코칭을 await하면 그동안 샘플 처리/렌더가 통째로 멈춰 화면이 끊긴다.
 * null이면 동기 await(단위 테스트의 결정성용).
 */
class RunEngine(
    private val profile: Profile,
    private val coach: Coach,
    private val coachScope: CoroutineScope? = null,
    priorUFrac: Double? = null,        // 세션 누적 학습값(LearnedZone). 없으면 공식 prior
    private val cadence: CoachCadence = CoachCadence.DEFAULT, // 코칭 빈도(spec-021)
    priorDriftFloor: Triple<Double, Double, Int>? = null,    // 드리프트 개인 노이즈플로어 누적(LearnedZone, spec-025 §4)
) {
    private val extractor = FeatureExtractor()
    private val personalization = Personalization(profile, priorUFrac)
    private val judge = ZoneJudge()
    private val safety = SafetyGuard() // 안전 가드(spec-008) — 위험 심박 규칙 권고, LLM 우회
    private val uEstStart = personalization.boundary().uFrac

    // 관측 분석 엔진(FR3, spec-025) — 지표별 작은 모듈을 등록해 조립(OCP)
    private val signals = SignalBuffer()
    private val analysis = AnalysisEngine(listOf(
        DriftSlopeMetric(), GapMinettiMetric(), CadenceStabilityMetric(),
        SubmaxHrMetric(), HrrMetric(),
    ))
    // 드리프트 개인 노이즈플로어(種類B) — 반응형 코칭 판단선 m+k·σ̂. 세션 간 누적.
    private val driftFloor = NoiseFloor(
        seedMean = priorDriftFloor?.first ?: Double.NaN,
        seedVar = priorDriftFloor?.second ?: Double.NaN,
        seedCount = priorDriftFloor?.third ?: 0,
    )
    // 최근 실시간 분석값(대시보드/코칭용)
    private var driftSlope: Double? = null
    private var driftRising = false
    private var gapPace: Double? = null
    private var cadenceUnstable = false

    private var lastValidHr: Int? = null
    private var judgment: ZoneJudgment? = null

    // 누적
    private var elapsed = 0
    private var distanceM = 0.0
    private var hrSum = 0L
    private var hrCount = 0
    private var maxHr = 0
    private var spmSum = 0L
    private var spmCount = 0
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

    private var sustainedHr = -1       // 최근 지속 심박(코칭 컨텍스트용)
    private var coachLoBpm = 0
    private var coachHiBpm = 0

    /** 토크 테스트 자가관측을 개인화 경계에 반영(arch/zone2-physiology §6). 현재 유효 HR이 있을 때만. */
    fun observeTalkTest(state: com.zone2runner.app.pipeline.TalkState) {
        val hr = lastValidHr ?: return
        personalization.observeTalkTest(hr, state)
    }

    /** 현재 개인화 경계 uFrac — 세션 누적 저장(LearnedZone)용. 토크테스트+디커플링이 반영됨. */
    fun currentUFrac(): Double = personalization.boundary().uFrac

    /** 이번 세션에 토크테스트가 한 번이라도 반영됐는지(저장 판단/표시용). */
    /** 현재 기온(℃) — 코칭 맥락(더위)용. RunActivity가 날씨 조회/가상러너로 세팅. 방향엔 무관. */
    @Volatile var ambientTempC: Double? = null

    val talkObserved: Boolean get() = personalization.talkCount > 0
    /** 이번 세션 말하기 테스트 응답 횟수(개인화 관측 누적 저장용). */
    fun talkObservations(): Int = personalization.talkCount
    /** 현재 개인화 경계 불확실성 σ(bpm) — 작을수록 확신. 시각화 신뢰도용. */
    fun currentSigmaBpm(): Double = personalization.sigma

    /** 1Hz 샘플 처리. 필요 시 coach.say 호출(suspend). LiveState 반환. */
    suspend fun onSample(s: Sample): LiveState {
        elapsed = s.tSec + 1
        // 정지 코칭(HR 유무와 무관): 사실상 멈춰 있으면(속도 <~3km/h) 가끔 독려한다.
        // 실센서에서 아직 안 뛰거나 뛰다가 멈춘 경우 — HR 파이프라인이 멈춰도 피드백을 준다.
        maybeIdleCoach(s)

        val clean = OutlierGuard.clean(s.hr, lastValidHr)
        if (clean == null) return liveState(s) // 아직 유효 HR 없음
        lastValidHr = clean

        // 안전 가드(spec-008): 위험 심박 지속 → 규칙 권고 즉시(LLM 우회, 코칭보다 우선)
        safetyAlert = safety.check(s.tSec, clean, profile.maxHr)
        safetyAlert?.let { recordCoaching(s.tSec, it, 0L, "안전") }

        // 누적 지표
        hrSum += clean; hrCount++; if (clean > maxHr) maxHr = clean
        if (s.spm > 0) { spmSum += s.spm; spmCount++ }
        val mps = MPS_PER_MIN_KM / s.paceMinKm.coerceAtLeast(0.1)
        distanceM += mps
        extractor.add(clean.toDouble(), s.paceMinKm, s.spm, s.slopePct)
        signals.add(s.tSec, clean.toDouble(), s.paceMinKm, s.spm, s.slopePct)

        val b = personalization.boundary()
        val loBpm = profile.restingHr + b.lFrac * profile.hrr
        val hiBpm = profile.restingHr + b.uFrac * profile.hrr

        // 판정 = 규칙(adr-013 FR1): 지속 심박 vs 개인화 경계 + 히스테리시스. 밴드와 같은 경계 → 모순 불가
        val sus = extractor.smoothedHrAt(s.tSec)
        if (sus != null) { judgment = judge.judge(sus.toDouble(), loBpm, hiBpm); sustainedHr = sus }
        coachLoBpm = loBpm.toInt(); coachHiBpm = hiBpm.toInt()

        // 관측 분석 엔진(FR3, spec-025): 실시간 파생지표 산출 + 드리프트 개인 노이즈플로어 갱신
        runAnalysis(s.tSec, b)

        // 코칭: 판정만 있으면 동작(120초 동역학 워밍업과 무관 — HR 들어오면 수십 초부터 코칭 시작).
        // 이전엔 feat(워밍업 필요) 블록 안에 있어 2분 지나야 코칭이 시작되던 문제(실기기).
        maybeCoach(s)

        // 특징(표시/개인화 관측용) — 판정에는 사용하지 않음
        val feat = extractor.extractAt(s.tSec, profile, b.uFrac, b.lFrac)
        if (feat != null) {
            lastFeat = feat // 대시보드 표시용(드리프트/심박 추세)
            // 개인화 관측 후보: decoupling(=feat[5]) 임계 부근의 지속 HR
            val hrFrac = feat[0] + b.uFrac
            val hrRecent = profile.restingHr + hrFrac * profile.hrr
            if (feat[5] in 0.03..0.10) obsCandidates += hrRecent
        }
        // 존 체류 시간(초 단위 누적)
        when (judgment) {
            ZoneJudgment.BELOW -> belowSec++
            ZoneJudgment.ABOVE -> aboveSec++
            ZoneJudgment.IN -> inSec++
            null -> {}
        }
        // 경로 + 시계열(3초마다 다운샘플). GPS 미확보(NaN) 좌표는 경로에 넣지 않음
        if (s.tSec % 3 == 0) {
            if (s.lat.isFinite() && s.lon.isFinite()) track += TrackPoint(s.lat, s.lon, judgment)
            series += SeriesPoint(s.tSec, clean, s.paceMinKm, judgment?.index ?: -1)
        }

        // 개인화 갱신(5분마다) — 디커플링(드리프트) 관측. 편향(Conconi)이 있어 약한 신호로만 반영(obsSd 큼).
        // 주 라벨은 토크테스트(사용자 입력, adr-016). 디커플링은 사용자 입력 없이도 경계를 미세 조정하는 보조.
        if (s.tSec - lastPersonalizeSec >= 300 && obsCandidates.isNotEmpty()) {
            personalization.update(median(obsCandidates), obsSd = 20.0) // 약한 관측(토크테스트 sd6~14보다 훨씬 약)
            obsCandidates.clear()
            lastPersonalizeSec = s.tSec
        }
        return liveState(s)
    }

    /** 관측 분석 엔진 실시간 산출 + 드리프트 개인 노이즈플로어 갱신(spec-025). */
    private fun runAnalysis(tSec: Int, b: Zone2Boundary) {
        val input = AnalysisInput(tSec, profile, b, signals)
        analysis.onTick(input)
        gapPace = analysis.latest(GapMinettiMetric.ID)?.value
        cadenceUnstable = analysis.latest(CadenceStabilityMetric.ID)?.trend == Trend.UP
        val drift = analysis.latest(DriftSlopeMetric.ID)
        if (drift != null && !drift.gated) {
            driftSlope = drift.value
            driftFloor.observe(drift.value)
            // 반응형 코칭 판단선: 통계적 상승(trend UP, within-window k·SE) AND 개인 이력 대비 이례적(m+k·σ̂)
            val personal = if (driftFloor.ready(AnalysisConfig.DRIFT_FLOOR_MIN_N))
                driftFloor.threshold(AnalysisConfig.K_SIGMA) else null
            driftRising = drift.trend == Trend.UP && (personal == null || drift.value > personal)
        }
    }

    /** 드리프트 개인 노이즈플로어 상태 [mean, var, count] — 세션 종료 저장(LearnedZone.setDriftFloor)용. */
    fun driftFloorState(): Triple<Double, Double, Int> =
        Triple(driftFloor.meanRaw(), driftFloor.varRaw(), driftFloor.count)

    private var stationarySec = 0
    private var wasStationary = false

    /** 정지 감지 + 독려 코칭. 판정/HR과 독립. 정지 진입 시 1회 + 오래 멈추면 가끔 재독려. */
    private fun maybeIdleCoach(s: Sample) {
        val stationary = s.paceMinKm >= 19.0 // 사실상 멈춤(LiveRunSource 정지 sentinel = 20min/km)
        if (!stationary) { stationarySec = 0; wasStationary = false; return }
        stationarySec++
        if (stationarySec < 8) return
        // 정지 진입 직후 1회, 이후엔 idleReSec마다. 다른 코칭과 최소 간격 공유.
        val due = !wasStationary || s.tSec - lastCoachSec >= cadence.idleReSec
        if (!due || s.tSec - lastCoachSec < 8) return
        wasStationary = true
        lastCoachSec = s.tSec
        lastJudgmentForCoach = null // 다시 움직이면 판정 코칭이 새로 나가게
        val line = if (distanceM > 30) "잠깐 멈췄네요. 준비되면 다시 뛰어볼까요." else "이제 가볍게 출발해 볼까요."
        recordCoaching(s.tSec, line, 0L, "정지")
    }

    /** 코칭 사유 태그(설명용이성, spec-023 FR3) — 각 코칭이 왜 나갔는지. */
    private fun reasonOf(j: ZoneJudgment): String = when {
        j == ZoneJudgment.ABOVE -> "초과"
        j == ZoneJudgment.BELOW -> "미달"
        else -> "유지"
    }

    private suspend fun maybeCoach(s: Sample) {
        val j = judgment ?: return
        val changed = j != lastJudgmentForCoach
        // 존 밖(미달/초과)에 계속 머물면 60초마다 재코칭 — 판정 변화만 기다리면
        // 초과가 지속될 때 코칭이 영영 침묵한다(실기기 시뮬 관찰)
        val overdue = j != ZoneJudgment.IN && s.tSec - lastCoachSec >= cadence.overdueSec
        if ((!changed && !overdue) || s.tSec - lastCoachSec < cadence.minGapSec) return
        fireCoach(s, j)
    }

    private suspend fun fireCoach(s: Sample, j: ZoneJudgment) {
        val scope = coachScope
        if (scope != null && coachJob?.isActive == true) return // 이전 생성이 아직 진행 중이면 건너뜀
        lastCoachSec = s.tSec
        lastJudgmentForCoach = j
        val reason = reasonOf(j)
        val ctx = CoachContext(
            j, s.slopePct, s.paceMinKm, s.tSec, spm = s.spm,
            currentHr = sustainedHr, loBpm = coachLoBpm, hiBpm = coachHiBpm,
            tempC = ambientTempC,
        )
        if (scope == null) {
            recordCoaching(s.tSec, coach.say(ctx), 0L, reason)
        } else {
            // coachScope는 메인 디스패처(lifecycleScope) 가정 — recordCoaching이 onSample과 같은 스레드에서 실행됨
            coachJob = scope.launch {
                val t0 = System.currentTimeMillis()
                val line = coach.say(ctx)
                recordCoaching(ctx.elapsedSec, line, System.currentTimeMillis() - t0, reason)
            }
        }
    }

    /** 코칭 라인 확정 시 호출(비동기 생성 완료 시점). 필드 로그(spec-012)용. */
    var onCoachingRecorded: ((tSec: Int, line: String, tookMs: Long) -> Unit)? = null

    private fun recordCoaching(tSec: Int, line: String, tookMs: Long, reason: String = "") {
        // 코칭 로그에 사유 태그를 붙여 스스로 설명되게 한다(spec-023 FR3). 예: "[03:12] (초과) 페이스를 낮춰볼까요".
        val tag = if (reason.isNotBlank()) "($reason) " else ""
        coachingLines += "[%02d:%02d] %s%s".format(tSec / 60, tSec % 60, tag, line)
        lastCoachText = line
        onCoachingRecorded?.invoke(tSec, line, tookMs)
    }

    private var coachJob: Job? = null
    private var lastCoachText = ""
    private var lastFeat: DoubleArray? = null

    private fun liveState(s: Sample) = LiveState(
        elapsedSec = elapsed,
        hr = lastValidHr ?: -1,
        smoothedHr = extractor.smoothedHrAt(s.tSec) ?: (lastValidHr ?: -1),
        judgment = judgment,
        paceMinKm = s.paceMinKm,
        speedKmh = if (s.paceMinKm > 0.1) 60.0 / s.paceMinKm else 0.0,
        distanceM = distanceM,
        coaching = lastCoachText,
        uEstFrac = personalization.boundary().uFrac,
        slopePct = s.slopePct,
        spm = s.spm,
        decoupling = extractor.displayDriftAt(s.tSec), // 표시용(HR/속도 기반) — 특징 feat[5]와 별개
        dHrPerSec = lastFeat?.get(2),
        driftSlope = driftSlope,
        driftRising = driftRising,
        gapPaceMinKm = gapPace,
        cadenceUnstable = cadenceUnstable,
        safetyAlert = safetyAlert,
    )

    @Volatile var safetyAlert: String? = null // 안전 가드(spec-008) — RunActivity/step7이 설정

    fun report(): RunReport {
        // 세션종료 분석(FR3): 전체 신호 버퍼로 SESSION_END/BOTH 지표 산출 → FR6 표시/FR4 추세
        val end = analysis.onSessionEnd(AnalysisInput(signals.tNow, profile, personalization.boundary(), signals))
        val submax = end.firstOrNull { it.id == SubmaxHrMetric.ID }?.value
        val lines = end.filter { it.note.isNotBlank() }.map { it.note }
        return RunReport(
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
            usedModel = true,
            coachSource = coachSource,
            avgSpm = if (spmCount > 0) (spmSum / spmCount).toInt() else 0,
            analysisLines = lines,
            submaxHr = submax,
        )
    }

    private fun median(a: List<Double>): Double {
        val s = a.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
}
