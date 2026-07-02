package com.zone2runner.app.pipeline

import com.zone2runner.app.domain.Profile
import com.zone2runner.app.domain.Zone2Boundary

/**
 * 개인화 Zone2 상한 추정 — 켤레 가우시안(Bayesian) 적응 (adr-004, spec-004, personalization.py 포팅).
 * 공식(HRR 0.70)을 사전분포로, 세션마다 decoupling에서 뽑은 관측 z(bpm)로 갱신.
 * 신경망 아님(float 산술). 세션이 쌓일수록 개인 경계로 수렴(QA3).
 */
class Personalization(private val profile: Profile) {
    private val mu0 = profile.restingHr + Zone2Boundary.FORMULA.uFrac * profile.hrr // 사전(공식 상한 bpm)
    var muUpper = mu0            // 상한 추정(bpm)
        private set
    var variance = 8.0 * 8.0     // 불확실성 σ²
        private set
    private val band = 0.10      // Zone2 폭(상한-하한, HRR 비율)

    /** 관측 z(bpm)로 갱신. obsSd: 관측 표준편차. */
    fun update(zBpm: Double, obsSd: Double = 10.0) {
        val z = zBpm.coerceIn(profile.restingHr + 0.4 * profile.hrr, profile.restingHr + 0.95 * profile.hrr)
        val p0 = 1.0 / variance
        val p1 = 1.0 / (obsSd * obsSd)
        muUpper = (muUpper * p0 + z * p1) / (p0 + p1)
        variance = 1.0 / (p0 + p1)
        muUpper = muUpper.coerceIn(mu0 - 15, mu0 + 15) // 안전 가드(규칙 우선)
    }

    fun boundary(): Zone2Boundary {
        val uFrac = ((muUpper - profile.restingHr) / profile.hrr).coerceIn(0.5, 0.85)
        return Zone2Boundary(uFrac, uFrac - band)
    }

    val sigma: Double get() = Math.sqrt(variance)
}
