"""
② 개인화: Bayesian 개인 경계 추정 (spec-004, adr-004).

목적: "세션이 쌓일수록 개인 Zone2 상한 추정이 참값으로 수렴하는가"(QA3)를 검증.
방식: 공식(HRR)을 사전분포로, 세션마다 decoupling에서 뽑은 관측으로 켤레 가우시안 갱신.
(이 문서의 관심은 정확도가 아니라 '적응 루프가 수렴함'의 실증)
"""
import json
import os

import numpy as np

from simulator import make_runner, generate_session, WARMUP_S, STRIDE, FORMULA_UPPER_FRAC

ART = os.path.join(os.path.dirname(__file__), "artifacts")
os.makedirs(ART, exist_ok=True)


def session_windows(s):
    """세션 → (윈도우 지속HR, decoupling) 배열."""
    hr, pace = s["hr_obs"], s["pace"]
    base = np.mean(hr[WARMUP_S - 60:WARMUP_S] / pace[WARMUP_S - 60:WARMUP_S])
    HRs, DECs = [], []
    for t in range(WARMUP_S, len(hr), STRIDE):
        HRs.append(np.mean(hr[t - 60:t]))
        DECs.append(np.mean(hr[t - 30:t] / pace[t - 30:t]) / base - 1)
    return np.array(HRs), np.array(DECs)


def observe_upper(hrw, dec):
    """세션 관측 z = 유산소 임계 부근 HR (decoupling 상승점). 없으면 None."""
    near = (dec > 0.03) & (dec < 0.10)
    if near.sum() >= 5:
        return float(np.median(hrw[near]))
    low = dec < 0.05
    if low.sum() >= 5:
        return float(np.percentile(hrw[low], 85))
    return None


def bayes_update(mu, var, z, obs_var):
    """켤레 가우시안 갱신 (spec-004 §4)."""
    p0, p1 = 1.0 / var, 1.0 / obs_var
    return (mu * p0 + z * p1) / (p0 + p1), 1.0 / (p0 + p1)


def verify(n_runners=40, sessions=10, seed=7, u_frac_sigma=0.09, mode="ideal"):
    """mode='ideal': 관측=참값+노이즈(메커니즘 검증). mode='decoupling': 실제 관측(병목 노출)."""
    rng = np.random.default_rng(seed)
    err_by_session = [[] for _ in range(sessions)]
    err_formula, sigma_end = [], []
    for _ in range(n_runners):
        r = make_runner(rng, u_frac_sigma=u_frac_sigma)
        U_true = r["resting"] + r["u_frac"] * r["hrr"]
        mu0 = r["resting"] + FORMULA_UPPER_FRAC * r["hrr"]   # 사전(공식)
        mu, var = mu0, 8.0 ** 2
        err_formula.append(abs(mu0 - U_true))
        for i in range(sessions):
            s = generate_session(r, rng, 30)
            if mode == "ideal":
                z = float(U_true + rng.normal(0, 6.0))       # 관측이 편향없이 노이즈만 있다면
                obs_var = 6.0 ** 2
            else:
                hrw, dec = session_windows(s); z = observe_upper(hrw, dec)
                obs_var = 10.0 ** 2
            if z is not None:
                z = float(np.clip(z, r["resting"] + 0.4 * r["hrr"], r["resting"] + 0.95 * r["hrr"]))
                mu, var = bayes_update(mu, var, z, obs_var)
                mu = float(np.clip(mu, mu0 - 15, mu0 + 15))   # 안전 가드(규칙 우선)
            err_by_session[i].append(abs(mu - U_true))
        sigma_end.append(var ** 0.5)

    mean_err = [float(np.mean(e)) for e in err_by_session]
    res = {
        "formula_err_bpm": round(float(np.mean(err_formula)), 2),
        "err_session1_bpm": round(mean_err[0], 2),
        "err_sessionN_bpm": round(mean_err[-1], 2),
        "sigma_start_bpm": 8.0,
        "sigma_end_bpm": round(float(np.mean(sigma_end)), 2),
        "converged": bool(mean_err[-1] < mean_err[0]),
        "beats_formula": bool(mean_err[-1] < np.mean(err_formula)),
    }
    print("=" * 56)
    print(f"② Bayesian 개인 경계 추정 — 수렴 검증 (QA3)  [관측 모드: {mode}]")
    print("=" * 56)
    print(f"공식(개인화 없음) 오차 : {res['formula_err_bpm']} bpm")
    print(f"세션1 오차 → 세션{sessions} 오차: {res['err_session1_bpm']} → {res['err_sessionN_bpm']} bpm")
    print(f"불확실성 σ: {res['sigma_start_bpm']} → {res['sigma_end_bpm']} bpm (줄어들면 확신↑)")
    print(f"세션별 평균오차: {[round(e,1) for e in mean_err]}")
    print(f"수렴(오차 감소): {res['converged']}  | 공식보다 나음: {res['beats_formula']}")

    # 수렴 플롯
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.plot(range(1, sessions + 1), mean_err, "o-", label="Bayesian 추정 오차")
    ax.axhline(res["formula_err_bpm"], color="r", ls="--", label="formula (no personalization)")
    ax.set_xlabel("session #"); ax.set_ylabel("|mu - true upper| (bpm)")
    ax.set_title("Personal boundary convergence (QA3)"); ax.legend(); ax.grid(alpha=.3)
    fig.tight_layout(); fig.savefig(os.path.join(ART, f"personalization_convergence_{mode}.png"), dpi=120)
    with open(os.path.join(ART, f"metrics_personalization_{mode}.json"), "w") as f:
        json.dump({**res, "mean_err_by_session": [round(e, 2) for e in mean_err]}, f, ensure_ascii=False, indent=2)
    print("\n저장: artifacts/personalization_convergence.png / metrics_personalization.json")
    return res


if __name__ == "__main__":
    print(">>> 메커니즘 검증 (관측이 편향없이 노이즈만 있을 때)\n")
    verify(mode="ideal")
    print("\n\n>>> 실제 관측 (decoupling으로 임계 추출) — 병목 노출\n")
    verify(mode="decoupling")
