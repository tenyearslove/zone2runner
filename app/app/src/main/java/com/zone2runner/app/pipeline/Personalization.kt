package com.zone2runner.app.pipeline

import com.zone2runner.app.domain.Profile
import com.zone2runner.app.domain.Zone2Boundary
import com.zone2runner.app.domain.Zone2Prior

/**
 * 개인화 Zone2 상한 추정 — 켤레 가우시안(Bayesian) 적응 (adr-004, spec-004, personalization.py 포팅).
 * 사전분포는 프로필 factor 기반 prior(adr-012/spec-013: 체형/러닝수준/빈도 → uFrac0, σ0).
 * factor 미입력이면 공식(%HRmax 0.70)/σ 8bpm과 동일 — 하위 호환.
 * 세션마다 decoupling에서 뽑은 관측 z(bpm)로 갱신. 신경망 아님(float 산술). 누적될수록 개인 경계로 수렴(QA3).
 */
class Personalization(private val profile: Profile, priorUFrac: Double? = null) {
    private val prior = Zone2Prior.of(profile)
    // priorUFrac이 주어지면(세션 누적 학습값, LearnedZone) 그것을 사전 상한으로 사용, 없으면 공식 prior
    private val mu0 = profile.restingHr + (priorUFrac ?: prior.uFrac0) * profile.hrr // 사전 상한(bpm)
    var muUpper = mu0            // 상한 추정(bpm)
        private set
    var variance = prior.sigma0Bpm * prior.sigma0Bpm // 불확실성 σ² (극단 프로필일수록 넓게 시작)
        private set
    private val band = Zone2Prior.BAND // Zone2 폭(상한-하한, HRR 비율)

    /** 관측 z(bpm)로 갱신. obsSd: 관측 표준편차. */
    fun update(zBpm: Double, obsSd: Double = 10.0) {
        val z = zBpm.coerceIn(profile.restingHr + 0.4 * profile.hrr, profile.restingHr + 0.95 * profile.hrr)
        val p0 = 1.0 / variance
        val p1 = 1.0 / (obsSd * obsSd)
        muUpper = (muUpper * p0 + z * p1) / (p0 + p1)
        variance = 1.0 / (p0 + p1)
        // 안전 가드(규칙 우선): 세션 내 이동 ±10bpm 제한. ±15는 임계추출 편향(Conconi)과 결합 시
        // 상한이 158bpm까지 부풀어 "HR 160인데 Zone2 유지"가 나올 수 있음(2026-07-03 실기기 관찰).
        muUpper = muUpper.coerceIn(mu0 - 10, mu0 + 10)
    }

    fun boundary(): Zone2Boundary {
        // 생리 가드: %HRmax 기준 재보정(2026-07-04)으로 하한 완화 — 유산소 Zone2는 %HRmax 60~72%이고
        // 실측 maxHr/저RHR 사용자는 HRR 비율이 낮게 나오므로 0.30까지 허용(토크테스트로 더 내려갈 수 있게).
        val uFrac = ((muUpper - profile.restingHr) / profile.hrr).coerceIn(Zone2Prior.U_FRAC_MIN, Zone2Prior.U_FRAC_MAX)
        return Zone2Boundary(uFrac, uFrac - band)
    }

    val sigma: Double get() = Math.sqrt(variance)

    /**
     * 토크 테스트 자가관측 → 개인 경계 관측 (arch/zone2-physiology-and-estimation §6, spec-016 Tier1).
     * 근거: "편하게 말할 수 있는 마지막 강도"의 HR ≈ VT1(1차 환기역치) ≈ LT1 부근.
     * 디커플링 관측의 임계추출 편향을 보완하는 무비용 독립 채널. 5단계 척도(강도 오름차순 z 단조 감소):
     *   BORDERLINE(말이 끊기기 시작 ≈ VT1) → 현재 지속 HR을 상한 직접 관측(가장 좁은 σ, 최고 확신).
     *   COMFORTABLE/VERY_COMFORTABLE      → 상한이 현재보다 위(단측 증거). 멀수록 σ 확대(거리 불확실).
     *   HARD/VERY_HARD                     → 상한이 현재보다 아래(단측 증거). 멀수록 σ 확대.
     */
    var talkCount = 0
        private set

    fun observeTalkTest(currentHr: Int, state: TalkState) {
        if (currentHr <= 0) return
        // 단측(one-sided) 관측: 토크테스트는 "현재 심박 대비 임계 위치"의 부등식 정보다.
        //   편함  = 이 심박에도 대화 편함 → 임계 ≥ 현재 → 경계를 '올리는 방향'만 반영(하한).
        //   벅참  = 대화 벅참        → 임계 ≤ 현재 → 경계를 '내리는 방향'만 반영(상한).
        //   보통  = 말이 끊기기 시작 ≈ 임계 → 현재 심박을 직접 관측(양방향, 점).
        // 이렇게 안 하면 '편한데 미달'(현재 < 경계)에서 편함이 경계를 끌어내리는 오류가 난다(사용자 지적).
        val (z, sd, side) = when (state) {
            TalkState.VERY_COMFORTABLE -> Triple(currentHr + 10.0, 16.0, +1)
            TalkState.COMFORTABLE -> Triple(currentHr + 5.0, 14.0, +1)
            TalkState.BORDERLINE -> Triple(currentHr.toDouble(), 6.0, 0)
            TalkState.HARD -> Triple(currentHr - 5.0, 14.0, -1)
            TalkState.VERY_HARD -> Triple(currentHr - 10.0, 16.0, -1)
        }
        talkCount++
        // 올려야 하는데 이미 경계가 위 / 내려야 하는데 이미 아래 → 새 정보 없음, 무변화
        if (side > 0 && z <= muUpper) return
        if (side < 0 && z >= muUpper) return
        update(z, sd)
    }
}

/** 토크 테스트 응답(5단계 척도, spec-016). 강도 오름차순으로 나열. */
enum class TalkState { VERY_COMFORTABLE, COMFORTABLE, BORDERLINE, HARD, VERY_HARD }
