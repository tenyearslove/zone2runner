package com.zone2runner.app.pipeline

import com.zone2runner.app.domain.Profile
import com.zone2runner.app.domain.Zone2Boundary
import com.zone2runner.app.domain.Zone2Prior

/**
 * 개인화 Zone2 상한 추정 — 켤레 가우시안(Bayesian) 적응 (adr-004, spec-004, personalization.py 포팅).
 * 사전분포는 프로필 factor 기반 prior(adr-012/spec-013: 체형/러닝수준/빈도 → uFrac0, σ0).
 * factor 미입력이면 공식(HRR 0.70)/σ 8bpm과 동일 — 하위 호환.
 * 세션마다 decoupling에서 뽑은 관측 z(bpm)로 갱신. 신경망 아님(float 산술). 누적될수록 개인 경계로 수렴(QA3).
 */
class Personalization(private val profile: Profile) {
    private val prior = Zone2Prior.of(profile)
    private val mu0 = profile.restingHr + prior.uFrac0 * profile.hrr // 사전 상한(bpm)
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
        // 생리적 상한 가드: Zone2 상한은 어떤 갱신에도 HRR 80%를 넘지 않는다(LT1 문헌 상단).
        val uFrac = ((muUpper - profile.restingHr) / profile.hrr).coerceIn(0.55, 0.80)
        return Zone2Boundary(uFrac, uFrac - band)
    }

    val sigma: Double get() = Math.sqrt(variance)
}
