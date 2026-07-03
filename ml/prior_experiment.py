"""③ 콜드스타트 prior 정교화 실험 (adr-012, spec-013).

질문: "프로필 factor(체형/러닝 수준/빈도)로 prior를 개인 근사치에서 시작하면
      고정 공식(HRR 70%) 대비 초기 오차와 수렴이 얼마나 개선되는가?"

방법: 참 경계 u_frac ~ N(0.70, σ)인 러너 모집단에서,
  - fixed   : prior = 0.70 (전원 동일, 현행)
  - informed: prior = 0.70 + factor 매핑 추정치
      factor 매핑은 참 편차의 일부(capture)만 설명하고 매핑 오차(map_sd)를 갖는 것으로 모델링
      (문헌 기반 오프셋 표가 완벽할 수 없음을 정직하게 반영), 앱과 동일한 clamp [-0.08,+0.06].
  두 prior 모두 동일한 Bayesian 갱신(ideal 관측: 참값+노이즈, 세션당 1회)로 적응.
  → 비교 지표: 세션 0(관측 전) 오차, |오차|<3bpm 도달까지 세션 수, 5세션 후 오차.

실행: ./.venv/bin/python ml/prior_experiment.py
"""
import json
import os

import numpy as np

from simulator import make_runner
from personalization import bayes_update

ART = os.path.join(os.path.dirname(__file__), "artifacts")
os.makedirs(ART, exist_ok=True)

CLAMP_LO, CLAMP_HI = -0.08, 0.06  # 앱 Zone2Prior와 동일


def informed_offset(rng, true_dev, capture=0.6, map_sd=0.02):
    """factor 매핑이 주는 오프셋: 참 편차의 capture 비율 + 매핑 오차, 앱과 동일 clamp."""
    est = capture * true_dev + rng.normal(0, map_sd)
    return float(np.clip(est, CLAMP_LO, CLAMP_HI))


def run(n_runners=300, sessions=10, seed=11, u_frac_sigma=0.045,
        capture=0.6, map_sd=0.02, obs_sd=5.0, prior_sd=8.0):
    rng = np.random.default_rng(seed)
    out = {}
    for kind in ("fixed", "informed"):
        init_err, conv_sessions, err5 = [], [], []
        rng_k = np.random.default_rng(seed)  # 동일 모집단으로 공정 비교
        for _ in range(n_runners):
            r = make_runner(rng_k, u_frac_sigma=u_frac_sigma)
            U_true = r["resting"] + r["u_frac"] * r["hrr"]
            dev = r["u_frac"] - 0.70
            off = 0.0 if kind == "fixed" else informed_offset(rng_k, dev, capture, map_sd)
            mu = r["resting"] + (0.70 + off) * r["hrr"]
            var = prior_sd ** 2
            init_err.append(abs(mu - U_true))
            conv = None
            for i in range(sessions):
                z = U_true + rng_k.normal(0, obs_sd)  # ideal 관측(메커니즘 비교 — 임계추출 편향 배제)
                mu, var = bayes_update(mu, var, z, obs_sd ** 2)
                if conv is None and abs(mu - U_true) < 3.0:
                    conv = i + 1
                if i == 4:
                    err5.append(abs(mu - U_true))
            conv_sessions.append(conv if conv is not None else sessions + 1)
        out[kind] = {
            "init_err_mean": float(np.mean(init_err)),
            "init_err_p90": float(np.percentile(init_err, 90)),
            "sessions_to_3bpm_median": float(np.median(conv_sessions)),
            "err_after_5_sessions": float(np.mean(err5)),
        }
    return out


if __name__ == "__main__":
    res = run()
    f, i = res["fixed"], res["informed"]
    print("=== 콜드스타트 prior 비교 (러너 300명, ideal 관측, obs_sd=5bpm) ===")
    print(f"{'지표':38s} {'고정(0.70)':>12s} {'factor prior':>12s}")
    print(f"{'세션 0 오차 평균 (bpm)':38s} {f['init_err_mean']:12.2f} {i['init_err_mean']:12.2f}")
    print(f"{'세션 0 오차 p90 (bpm)':38s} {f['init_err_p90']:12.2f} {i['init_err_p90']:12.2f}")
    print(f"{'|오차|<3bpm 도달 세션 수 (중앙값)':38s} {f['sessions_to_3bpm_median']:12.1f} {i['sessions_to_3bpm_median']:12.1f}")
    print(f"{'5세션 후 오차 평균 (bpm)':38s} {f['err_after_5_sessions']:12.2f} {i['err_after_5_sessions']:12.2f}")

    # 민감도: 매핑 품질(capture)이 낮아도 해가 되지 않는가?
    print("\n=== 민감도: factor 매핑 품질(capture)별 세션 0 오차 평균 ===")
    for cap in (0.0, 0.3, 0.6, 0.9):
        r = run(capture=cap)
        print(f"capture={cap:.1f}: 고정 {r['fixed']['init_err_mean']:.2f} vs factor {r['informed']['init_err_mean']:.2f} bpm")

    with open(os.path.join(ART, "prior_experiment.json"), "w") as fp:
        json.dump(res, fp, indent=2, ensure_ascii=False)
    print(f"\n저장: {os.path.join(ART, 'prior_experiment.json')}")
