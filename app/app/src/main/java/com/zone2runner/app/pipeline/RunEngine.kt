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
 *   샘플 → 이상치 가드 → 안전 가드 → [판정 = 규칙(지속 심박 vs 개인화 경계 + 히스테리시스)]
 *        → 관측 분석 엔진(FR3) → 코칭(반응형/격려/예방) → 세션 누적
 * 판정과 밴드가 같은 개인화 경계를 참조하므로 모순이 구조적으로 불가능(adr-013).
 * 개인화 경계는 말하기 테스트로만 갱신(디커플링 자동갱신은 Conconi 편향으로 제거, spec-004/FR4).
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
    private val priorUphillTendency: Double? = null,         // 오르막 초과 경향 학습값(패턴 학습 예방, spec-025)
    private val useGpsDistance: Boolean = false,             // 실기기(GPS)=점간 직선거리로 총거리(페이스 적분 오차 방지). 시뮬/무GPS=false→페이스 적분.
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
    private var prevLat = Double.NaN   // GPS 점간거리용 직전 유효 좌표
    private var prevLon = Double.NaN
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

    private var sustainedHr = -1       // 최근 지속 심박(코칭 컨텍스트용)
    private var coachLoBpm = 0
    private var coachHiBpm = 0

    /** 토크 테스트 자가관측을 개인화 경계에 반영(arch/zone2-physiology §6). 현재 유효 HR이 있을 때만. */
    fun observeTalkTest(state: com.zone2runner.app.pipeline.TalkState) {
        val hr = lastValidHr ?: return
        personalization.observeTalkTest(hr, state)
    }

    /** 현재 개인화 경계 uFrac — 세션 누적 저장(LearnedZone)용. 말하기 테스트만 반영(디커플링 자동갱신 제거, 2026-07-13). */
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
        if (firstHr < 0) firstHr = clean // 워밍업 상승폭 기준

        // 안전 가드(spec-008): 위험 심박 지속 → 규칙 권고 즉시(LLM 우회, 코칭보다 우선)
        safetyAlert = safety.check(s.tSec, clean, profile.maxHr)
        safetyAlert?.let { recordCoaching(s.tSec, it, 0L, "안전") }

        // 누적 지표
        hrSum += clean; hrCount++; if (clean > maxHr) maxHr = clean
        if (s.spm > 0) { spmSum += s.spm; spmCount++ }
        // 총거리: 실기기(GPS)는 점간 직선거리(Haversine — 페이스 적분의 누적 오차 방지), 시뮬/무GPS는 페이스 적분.
        val paceStep = MPS_PER_MIN_KM / s.paceMinKm.coerceAtLeast(0.1)
        val stepM = if (useGpsDistance && s.lat.isFinite() && s.lon.isFinite() && prevLat.isFinite() && prevLon.isFinite()) {
            val d = haversineM(prevLat, prevLon, s.lat, s.lon)
            when {                       // RunService와 동일 게이트: 지터<0.5m=0, 튐>40m=페이스로 폴백
                d < 0.5 -> 0.0
                d > 40.0 -> paceStep
                else -> d
            }
        } else paceStep
        distanceM += stepM
        if (s.lat.isFinite() && s.lon.isFinite()) { prevLat = s.lat; prevLon = s.lon }
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
        // 반응형 코칭(FR5): 관측된 드리프트 상승에 선제 대응(예측 대체)
        maybeReactiveCoach(s)

        // 내리막 관절 보호(spec-026): 안전 다음, Zone2 앞 우선. 관절 위험군 내리막 → 아래 maybeCoach의 미달 코칭 억제.
        downhillSec = if (s.slopePct <= DOWNHILL_GRADE) downhillSec + 1 else 0
        jointDownhillActive = jointRisk && downhillSec >= DOWNHILL_HOLD_SEC
        if (jointDownhillActive) maybeJointCoach(s)

        // 코칭: 판정만 있으면 동작(120초 동역학 워밍업과 무관 — HR 들어오면 수십 초부터 코칭 시작).
        // 이전엔 feat(워밍업 필요) 블록 안에 있어 2분 지나야 코칭이 시작되던 문제(실기기).
        maybeCoach(s)

        // 심박 추세(bpm/s) — 대시보드 표시 + 회복 감지용(도출값). 판정엔 사용 안 함.
        lastDHr = extractor.dHrPerSecAt(s.tSec)
        // 존 체류 시간(초 단위 누적) + Zone 2 연속 유지 스트릭(마일스톤 격려용)
        when (judgment) {
            ZoneJudgment.BELOW -> { belowSec++; zone2StreakSec = 0 }
            ZoneJudgment.ABOVE -> { aboveSec++; zone2StreakSec = 0 }
            ZoneJudgment.IN -> { inSec++; zone2StreakSec++ }
            null -> {}
        }
        maybeMilestoneCoach(s)
        maybeWarmupCoach(s)   // 초반 급상승 → 천천히 올리기(FR5)
        maybeRecoveryCoach(s) // 심박 급강하 → 회복 인지(FR5)
        // 오르막 초과 추적 + 구간당 사전 예방(패턴 학습, FR5)
        val isUphill = s.slopePct > 2
        if (isUphill) {
            uphillSec++; if (judgment == ZoneJudgment.ABOVE) uphillAboveSec++
            if (!wasUphill) uphillWarnedSeg = false // 새 오르막 구간 진입 → 예방 재무장
            maybeUphillWarn(s)
        }
        wasUphill = isUphill
        // 경로 + 시계열(3초마다 다운샘플). GPS 미확보(NaN) 좌표는 경로에 넣지 않음
        if (s.tSec % 3 == 0) {
            if (s.lat.isFinite() && s.lon.isFinite()) track += TrackPoint(s.lat, s.lon, judgment)
            series += SeriesPoint(s.tSec, clean, s.paceMinKm, judgment?.index ?: -1, s.slopePct)
        }

        // 경계 갱신 = 말하기 테스트만(FR4/adr-025). 디커플링 자동 관측은 Conconi 편향으로 경계를 왜곡해
        // 제거함(spec-004 PoC, FR4 "디커플링은 표시용으로만") — 표시 드리프트는 displayDriftAt로 별개 유지.
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

    /** 두 위경도 사이 대원 거리(m) — Haversine. */
    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)))
    }

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
        if (jointDownhillActive && j == ZoneJudgment.BELOW) return // spec-026: 관절 보호 내리막에서 미달 "속도 올려" 억제(관절 큐가 대체)
        val changed = j != lastJudgmentForCoach
        // 존 밖(미달/초과)에 계속 머물면 60초마다 재코칭 — 판정 변화만 기다리면
        // 초과가 지속될 때 코칭이 영영 침묵한다(실기기 시뮬 관찰)
        val overdue = j != ZoneJudgment.IN && s.tSec - lastCoachSec >= cadence.overdueSec
        if ((!changed && !overdue) || s.tSec - lastCoachSec < cadence.minGapSec) return
        fireCoach(s, j)
    }

    private var zone2StreakSec = 0
    private var lastMilestoneMin = 0
    /** Zone 2 연속 유지 5분 단위 마일스톤 격려(FR5) — 누적 데이터 활용, 동기부여. */
    private suspend fun maybeMilestoneCoach(s: Sample) {
        if (zone2StreakSec == 0 || zone2StreakSec % 300 != 0) return // 정확히 5/10/15…분에
        val min = zone2StreakSec / 60
        if (min <= lastMilestoneMin || s.tSec - lastCoachSec < cadence.minGapSec) return
        lastMilestoneMin = min
        fireCoach(s, ZoneJudgment.IN, milestoneMin = min)
    }

    private var lastReactiveSec = -9999
    /**
     * 반응형 드리프트 코칭(FR5, spec-025): 판정은 IN인데 정속에서 심박이 유의하게 오르는 중이면
     * 이탈 전에 미리 여유를 안내. 예측 선제 코칭의 대체 — "관측된 추세"에 반응(짜고치기 없음).
     */
    private suspend fun maybeReactiveCoach(s: Sample) {
        if (!driftRising || judgment != ZoneJudgment.IN) return
        if (s.tSec - lastCoachSec < cadence.minGapSec) return
        if (s.tSec - lastReactiveSec < cadence.overdueSec) return // 같은 드리프트 연속 억제
        lastReactiveSec = s.tSec
        // 후반(경과 LATE_SEC 초과)이면 후반 페이싱 문구로(끝까지 유지). 種類C 상수.
        fireCoach(s, ZoneJudgment.IN, reactive = true, latePacing = s.tSec > LATE_SEC)
    }

    private val LATE_SEC = 1500 // 세션 후반 판정 경계(초, 種類C — 약 25분)
    private var firstHr = -1
    private var warmupCued = false
    private var lastRecoverySec = -9999

    // 패턴 학습 예방(오르막 초과) — 이번 세션 오르막 통계 + 진입 감지
    private var uphillSec = 0
    private var uphillAboveSec = 0
    private var wasUphill = false
    private var uphillWarnedSeg = false

    // 내리막 관절 보호(spec-026) — 관절 위험군 = 프로필 토글 또는 과체중/비만(BMI≥25 = bodyType≥4)
    private val jointRisk = profile.jointCaution || profile.bodyType >= 4
    private var downhillSec = 0
    private var jointDownhillActive = false
    private var lastJointSec = -9999
    private val DOWNHILL_GRADE = -4.0 // 확실한 내리막(±2% 데드밴드 밖, 種類C — spec-026)
    private val DOWNHILL_HOLD_SEC = 8  // 지속 내리막 최소(種類C)

    /** 이번 세션 오르막 구간에서 초과한 비율(0~1). 오르막이 충분치 않으면 -1(저장 스킵). */
    fun uphillExitRatio(): Double = if (uphillSec >= 30) uphillAboveSec.toDouble() / uphillSec else -1.0

    /** 오르막 구간당 1회, 학습된 경향이 높으면 사전 예방 큐(패턴 학습). 스로틀 풀리면 발화. */
    private suspend fun maybeUphillWarn(s: Sample) {
        if (uphillWarnedSeg) return
        val tend = priorUphillTendency ?: return
        if (tend < 0.4) return // 種類C: 오르막서 40% 이상 터지던 경향일 때만
        if (s.tSec - lastCoachSec < cadence.minGapSec) return
        uphillWarnedSeg = true
        fireCoach(s, judgment ?: ZoneJudgment.IN, uphillWarn = true)
    }

    /** 워밍업 큐(FR5): 초반 45~90초에 심박이 급상승(+25bpm 이상)하면 1회 "천천히 올리기" 안내. */
    private suspend fun maybeWarmupCoach(s: Sample) {
        if (warmupCued || firstHr <= 0 || s.tSec !in 45..90) return
        val hr = lastValidHr ?: return
        if (hr - firstHr < 25 || s.tSec - lastCoachSec < cadence.minGapSec) return
        warmupCued = true
        fireCoach(s, judgment ?: ZoneJudgment.IN, warmup = true)
    }

    /** 내리막 관절 보호 큐(spec-026): 관절 위험군 내리막에서 Zone2보다 우선, 미달 오코칭 대체. 코칭 간격/재발 억제 준수. */
    private suspend fun maybeJointCoach(s: Sample) {
        if (s.tSec - lastCoachSec < cadence.minGapSec) return
        if (s.tSec - lastJointSec < cadence.overdueSec) return // 같은 내리막 반복 억제
        lastJointSec = s.tSec
        fireCoach(s, judgment ?: ZoneJudgment.IN, jointProtect = true)
    }

    /** 회복 인지(FR5): 심박이 빠르게 하강(bpm/s < -0.3) 중이면 "잘 회복 중" 인지. 90초 스로틀. */
    private suspend fun maybeRecoveryCoach(s: Sample) {
        val d = lastDHr ?: return // bpm/s(음수=하강)
        if (d > -0.3) return
        if (s.tSec - lastRecoverySec < 90 || s.tSec - lastCoachSec < cadence.minGapSec) return
        lastRecoverySec = s.tSec
        fireCoach(s, judgment ?: ZoneJudgment.IN, recovering = true)
    }

    private suspend fun fireCoach(
        s: Sample, j: ZoneJudgment, reactive: Boolean = false, milestoneMin: Int = 0,
        warmup: Boolean = false, latePacing: Boolean = false, recovering: Boolean = false,
        uphillWarn: Boolean = false, jointProtect: Boolean = false,
    ) {
        val scope = coachScope
        if (scope != null && coachJob?.isActive == true) return // 이전 생성이 아직 진행 중이면 건너뜀
        lastCoachSec = s.tSec
        val special = milestoneMin > 0 || warmup || recovering || uphillWarn || jointProtect
        if (!special) lastJudgmentForCoach = j
        val reason = when {
            milestoneMin > 0 -> "마일스톤"; recovering -> "회복"; warmup -> "워밍업"
            uphillWarn -> "오르막예방"; jointProtect -> "관절보호"; latePacing -> "후반"; reactive -> "드리프트↑"; else -> reasonOf(j)
        }
        val ctx = CoachContext(
            j, s.slopePct, s.paceMinKm, s.tSec, spm = s.spm,
            currentHr = sustainedHr, loBpm = coachLoBpm, hiBpm = coachHiBpm,
            tempC = ambientTempC,
            driftRising = reactive, gapPaceMinKm = gapPace, milestoneMin = milestoneMin,
            warmup = warmup, latePacing = latePacing, recovering = recovering, uphillWarn = uphillWarn,
            jointProtect = jointProtect,
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
    private var lastDHr: Double? = null

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
        dHrPerSec = lastDHr,
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
}
