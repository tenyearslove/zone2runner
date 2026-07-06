package com.zone2runner.app.pipeline

import com.zone2runner.app.coaching.Coach
import com.zone2runner.app.coaching.CoachContext
import com.zone2runner.app.domain.LiveState
import com.zone2runner.app.domain.MPS_PER_MIN_KM
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
 * 전체 러닝 파이프라인 오케스트레이터 (arch/architecture-overview, adr-013 역할 재분리).
 *   샘플 → 이상치 가드 → [판정 = 규칙(지속 심박 vs 개인화 경계 + 히스테리시스)]
 *        → 특징추출 → 개인화 갱신 → [동역학 NN: 60초 예측 + 목표 페이스 역질의 + 선제 코칭]
 *        → 코칭 → 세션 누적
 * 판정과 밴드가 같은 개인화 경계를 참조하므로 모순이 구조적으로 불가능(spec-014 FR1).
 * 시뮬레이터/실기기 공통. onSample을 1Hz로 호출.
 *
 * coachScope를 주면 코칭 생성(LLM ~2초)을 샘플 루프와 분리해 비동기로 돌린다 —
 * 코칭을 await하면 그동안 샘플 처리/렌더가 통째로 멈춰 화면이 끊긴다.
 * null이면 동기 await(단위 테스트의 결정성용).
 */
class RunEngine(
    private val profile: Profile,
    private val dynamics: HrDynamics?, // 심박 동역학 모델(spec-014). null이면 예측/페이스 제안 없음
    private val coach: Coach,
    private val coachScope: CoroutineScope? = null,
    priorUFrac: Double? = null,        // 세션 누적 학습값(LearnedZone). 없으면 공식 prior
    priorPredWeights: DoubleArray? = null, // 예측 개인 보정 누적 가중치(LearnedDynamics, spec-018)
    private val cadence: CoachCadence = CoachCadence.DEFAULT, // 코칭 빈도(spec-021)
    private val preemptiveEnabled: Boolean = true,            // 선제 코칭 on/off(spec-021)
) {
    private val extractor = FeatureExtractor()
    private val personalization = Personalization(profile, priorUFrac)
    private val judge = ZoneJudge()
    // 예측 온라인 개인 보정(spec-018): 기본 NN 위에 개인 잔차를 실주행에서 학습
    private val predLearner = HrPredictionLearner(
        priorPredWeights?.copyOfRange(0, 4), priorPredWeights?.copyOfRange(4, 8)
    )
    private val uEstStart = personalization.boundary().uFrac

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
    private var lastPreemptiveIntent: ZoneJudgment? = null // 선제 코칭 중복 방지(같은 예측이 연속되면 1회만)
    private var lastPersonalizeSec = 0
    private val obsCandidates = ArrayList<Double>() // decoupling 임계 부근 지속HR(bpm)

    // 동역학 모델 출력(표시용)
    private var predictedHr60 = -1
    private var recommendedPace = 0.0
    private var sustainedHr = -1       // 최근 지속 심박(코칭 컨텍스트용)
    private var coachLoBpm = 0
    private var coachHiBpm = 0

    val usingModel: Boolean get() = dynamics != null

    /** 토크 테스트 자가관측을 개인화 경계에 반영(arch/zone2-physiology §6). 현재 유효 HR이 있을 때만. */
    fun observeTalkTest(state: com.zone2runner.app.pipeline.TalkState) {
        val hr = lastValidHr ?: return
        personalization.observeTalkTest(hr, state)
    }

    /** 현재 개인화 경계 uFrac — 세션 누적 저장(LearnedZone)용. 토크테스트+디커플링이 반영됨. */
    fun currentUFrac(): Double = personalization.boundary().uFrac

    /** 예측 개인 보정 가중치(8개) — 세션 누적 저장(LearnedDynamics)용. */
    fun predWeights(): DoubleArray = predLearner.weights()

    /** 예측 온라인 보정 성과(base vs corrected RMSE, bpm) — 표시/검증용. */
    fun predRmse(): HrPredictionLearner.Rmse = predLearner.rmseBpm(profile.hrr)
    fun predUpdates(): Int = predLearner.updates

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

        // 누적 지표
        hrSum += clean; hrCount++; if (clean > maxHr) maxHr = clean
        if (s.spm > 0) { spmSum += s.spm; spmCount++ }
        val mps = MPS_PER_MIN_KM / s.paceMinKm.coerceAtLeast(0.1)
        distanceM += mps
        extractor.add(clean.toDouble(), s.paceMinKm, s.spm, s.slopePct)

        val b = personalization.boundary()
        val loBpm = profile.restingHr + b.lFrac * profile.hrr
        val hiBpm = profile.restingHr + b.uFrac * profile.hrr

        // 판정 = 규칙(adr-013 FR1): 지속 심박 vs 개인화 경계 + 히스테리시스. 밴드와 같은 경계 → 모순 불가
        val sus = extractor.smoothedHrAt(s.tSec)
        if (sus != null) { judgment = judge.judge(sus.toDouble(), loBpm, hiBpm); sustainedHr = sus }
        coachLoBpm = loBpm.toInt(); coachHiBpm = hiBpm.toInt()

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

            // 동역학 NN(spec-014): 현재 페이스 유지 시 60초 뒤 예측 + Zone2 목표 페이스 역질의
            val dyn = dynamics
            if (dyn != null) {
                val df = extractor.dynFeaturesAt(s.tSec, profile, s.paceMinKm, s.slopePct, s.spm)
                if (df != null) {
                    // 지금 도착한 실제 심박(df[0]=hr_now_frac)으로 만기된 과거 예측을 온라인 학습(페이스 유지분만)
                    predLearner.observe(s.tSec, df[0], s.paceMinKm)
                    val base = dyn.predictFrac(df)
                    predLearner.record(s.tSec, df, base, s.paceMinKm) // 이번 예측을 60초 뒤 검증용으로 버퍼
                    val corr = predLearner.correct(df, base)          // 개인 보정 적용
                    predictedHr60 = (profile.restingHr + corr.last() * profile.hrr).toInt()
                    // 추천 페이스: 보정량만큼 목표 밴드를 내려 base 스윕(보정 후 밴드 중심을 겨냥)
                    val c60 = predLearner.correction60(df)
                    val rec = dyn.recommendPace(df, b.lFrac - c60, b.uFrac - c60)
                    // 표시 안정화: 직전 추천과 한 스텝(0.25) 이내면 유지
                    if (recommendedPace <= 0.0 || Math.abs(rec - recommendedPace) > HrDynamics.PACE_STEP + 1e-9)
                        recommendedPace = rec
                    maybePreemptiveCoach(s, loBpm, hiBpm)
                }
            }
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
        recordCoaching(s.tSec, line, 0L)
    }

    private suspend fun maybeCoach(s: Sample) {
        val j = judgment ?: return
        val changed = j != lastJudgmentForCoach
        // 존 밖(미달/초과)에 계속 머물면 60초마다 재코칭 — 판정 변화만 기다리면
        // 초과가 지속될 때 코칭이 영영 침묵한다(실기기 시뮬 관찰)
        val overdue = j != ZoneJudgment.IN && s.tSec - lastCoachSec >= cadence.overdueSec
        if ((!changed && !overdue) || s.tSec - lastCoachSec < cadence.minGapSec) return
        lastPreemptiveIntent = null // 실제 판정 코칭이 나가면 선제 상태 리셋
        fireCoach(s, j, preemptive = false)
    }

    /**
     * 선제 코칭(spec-014 FR4): 판정은 IN인데 현재 페이스 유지 시 60초 뒤 예측이 경계 밖이면
     * 이탈 전에 미리 방향 코칭. 같은 예측 방향이 이어지면 1회만.
     */
    private suspend fun maybePreemptiveCoach(s: Sample, loBpm: Double, hiBpm: Double) {
        if (!preemptiveEnabled) return
        if (judgment != ZoneJudgment.IN || predictedHr60 <= 0) return
        val intent = when {
            predictedHr60 > hiBpm + 2 -> ZoneJudgment.ABOVE
            predictedHr60 < loBpm - 2 -> ZoneJudgment.BELOW
            else -> { lastPreemptiveIntent = null; return }
        }
        if (intent == lastPreemptiveIntent || s.tSec - lastCoachSec < cadence.minGapSec) return
        lastPreemptiveIntent = intent
        fireCoach(s, intent, preemptive = true)
    }

    private suspend fun fireCoach(s: Sample, j: ZoneJudgment, preemptive: Boolean) {
        val scope = coachScope
        if (scope != null && coachJob?.isActive == true) return // 이전 생성이 아직 진행 중이면 건너뜀
        lastCoachSec = s.tSec
        if (!preemptive) lastJudgmentForCoach = j
        val ctx = CoachContext(
            j, s.slopePct, s.paceMinKm, s.tSec, spm = s.spm, preemptive = preemptive,
            currentHr = sustainedHr, loBpm = coachLoBpm, hiBpm = coachHiBpm, predictedHr60 = predictedHr60,
            tempC = ambientTempC,
        )
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
        predictedHr60 = predictedHr60,
        recommendedPaceMinKm = recommendedPace,
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
        avgSpm = if (spmCount > 0) (spmSum / spmCount).toInt() else 0,
    )

    private fun median(a: List<Double>): Double {
        val s = a.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
}
