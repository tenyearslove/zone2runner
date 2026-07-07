"""
실험: 심박 예측 드리프트를 '상수' vs '상황조건 온라인 퍼셉트론(ADALINE)'으로 비교 (구현 전 검증).

전부 온라인(사전학습 0). τ는 양쪽 동일 고정(=30)해 '드리프트 모델'만 격리 비교한다.
공정 조건은 앱과 동일: 페이스 유지 샘플만, max_hr 클램프. 60초 예측 RMSE(bpm)로 채점.

모델(드리프트 rate = 분당 frac):
  Ref0  drift = 0                       (드리프트 학습 없음, 순수 ODE)
  A     drift = w0                      (현재 앱: 상수 스칼라, LMS)
  B     drift = w·[1, excess, elapsed]  (퍼셉트론: 상황조건, LMS)
  C     drift = w·[1, excess, elapsed, excess*elapsed, elapsed^2]  (비선형 basis, 여전히 선형-가중치)

excess = hr_sus_frac − u_frac(개인 임계).  elapsed = elapsed_min/30 (정규화).
갱신(모든 모델 동일): w += LR × (실제−예측) × (h/60) × x,  drift 출력은 ±0.12로 clamp.
가중치는 세션 간 이월(앱 LearnedDynamics와 동일). h=60 지평만 채점(주 사용).
"""
import sys, os
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
from simulator import make_runner, generate_session, WARMUP_S

TAU = 30.0
H = 60                # 채점 지평(초)
STRIDE = 1
PACE_TOL = 0.6
LR = 0.02
DRIFT_CLAMP = 0.12
SS_MIN, SS_MAX = 0.20, 1.15


def ode_pred(h_now, dh_per_sec, drift_rate, tau, h=H):
    # τ는 오라클(참값) 사용 → τ 오차 confound 제거, '드리프트 모델'만 순수 격리 비교.
    h_ss = np.clip(h_now + tau * dh_per_sec, SS_MIN, SS_MAX)
    d = np.clip(drift_rate, -DRIFT_CLAMP, DRIFT_CLAMP)
    return np.clip(h_ss + (h_now - h_ss) * np.exp(-h / tau) + d * (h / 60.0), SS_MIN, SS_MAX)


def feats(model, rex, elapsed, thermal):
    # rex = max(0, 지속HR − 임계)  (rectified/hinge — 드리프트는 임계 위에서만 생김, 생리 리서치)
    if model == "A":
        return np.array([1.0])
    if model == "B":
        return np.array([1.0, rex])                    # 생리 1순위 특징만
    if model == "C":
        return np.array([1.0, rex, elapsed])           # + 경과시간
    if model == "D":
        return np.array([1.0, rex, thermal])           # + 열/체액 부하 적분(∫rex dt)
    return np.array([])  # Ref0


def run_session(session, model, w):
    """한 세션 온라인 처리. w를 갱신(이월). 반환: (se_sum, n, time_above_frac)."""
    r = session["runner"]; resting, hrr, u_frac, tau = r["resting"], r["hrr"], r["u_frac"], r["hr_tau"]
    hr = np.clip(session["hr_obs"], 40.0, r["max_hr"])
    pace = session["pace"]
    n = len(hr)
    pending = {}  # target_t -> (pred_frac, x, settled)
    se, cnt, above = 0.0, 0, 0
    thermal = 0.0  # ∫ rex dt 근사(열/체액 부하). 정규화 위해 /100.
    SETTLED = 0.0012  # |dHR/dt| 이보다 작으면 정상상태(on-kinetics 아님) → 드리프트 학습 허용
    for t in range(WARMUP_S, n - H - 5):
        h_now = (np.mean(hr[t - 10:t]) - resting) / hrr
        dh = ((hr[t] - hr[t - 30]) / 30.0) / hrr
        h_sus = (np.mean(hr[t - 60:t]) - resting) / hrr
        rex = max(0.0, h_sus - u_frac)          # rectified: 드리프트는 임계 위에서만(생리)
        thermal += rex; thermal_n = thermal / 100.0
        if rex > 0: above += 1
        elapsed = t / 60.0 / 30.0
        x = feats(model, rex, elapsed, thermal_n)
        drift = 0.0 if model == "Ref0" else float(np.clip(w @ x, -DRIFT_CLAMP, DRIFT_CLAMP))
        settled = abs(dh) < SETTLED               # on-kinetics 게이팅
        if np.max(np.abs(pace[t:t + H] - pace[t])) <= PACE_TOL:
            pending[t + H] = (ode_pred(h_now, dh, drift, tau), x, settled)
        if t in pending:
            pred, xp, was_settled = pending.pop(t)
            actual = (np.mean(hr[t - 5:t + 5]) - resting) / hrr
            err = actual - pred
            se += (err * hrr) ** 2; cnt += 1
            # 드리프트 학습은 '예측 시점이 정상상태였던' 표본만(상승분을 드리프트로 오학습 방지)
            if model != "Ref0" and was_settled:
                w += LR * err * (H / 60.0) * xp
                np.clip(w, -5, 5, out=w)
    return se, cnt, above / max(1, n - H - WARMUP_S)


def eval_model(runners_sessions, model, dim):
    """러너별 K세션 이월. 반환: 러너별 (전체RMSE, 마지막세션RMSE, above)."""
    per_runner = []
    for sessions in runners_sessions:
        w = np.zeros(dim)
        se_tot, n_tot, above = 0.0, 0, 0.0
        last_se, last_n = 0.0, 0
        for i, s in enumerate(sessions):
            se, n, ab = run_session(s, model, w)
            se_tot += se; n_tot += n; above = ab
            if i == len(sessions) - 1:
                last_se, last_n = se, n
        rmse = np.sqrt(se_tot / n_tot) if n_tot else 0.0
        last = np.sqrt(last_se / last_n) if last_n else 0.0
        per_runner.append((rmse, last, above))
    return per_runner


def main():
    rng = np.random.default_rng(7)
    N_RUN, K = 60, 5
    print(f"러너 {N_RUN} x 세션 {K}, τ 고정 {TAU}, 온라인 학습(사전학습 0)...")
    runners_sessions = []
    for _ in range(N_RUN):
        runner = make_runner(rng)
        runners_sessions.append([generate_session(runner, rng, 30) for _ in range(K)])

    dims = {"Ref0": 0, "A": 1, "B": 2, "C": 3, "D": 3}
    results = {m: eval_model(runners_sessions, m, d) for m, d in dims.items()}

    def col(m, idx): return np.array([row[idx] for row in results[m]])

    print("\n=== 60초 예측 RMSE (bpm), 러너 평균 (τ 오라클) ===")
    print("  모델   전체RMSE  vs A     마지막세션RMSE  vs A     설명")
    baseT, baseL = col("A", 0).mean(), col("A", 1).mean()
    desc = {"Ref0": "드리프트 학습 없음(순수 ODE)", "A": "상수 드리프트(현재 앱)",
            "B": "퍼셉트론[1,rex]", "C": "[1,rex,elapsed]", "D": "[1,rex,열적분]"}
    for m in ["Ref0", "A", "B", "C", "D"]:
        t, l = col(m, 0).mean(), col(m, 1).mean()
        print(f"  {m:5s}  {t:7.3f}  {(1-t/baseT)*100:+5.1f}%   {l:9.3f}     {(1-l/baseL)*100:+5.1f}%   {desc[m]}")

    # 임계 초과에 오래 머문 러너(드리프트가 큰 곳)에서 이득 나야 (마지막 세션 기준)
    aboves = col("A", 2); hi = aboves > np.median(aboves)
    print("\n=== 마지막세션 RMSE 개선(vs A), 초과체류 구간별 ===")
    for m in ["Ref0", "B", "C", "D"]:
        rmA, rmM = col("A", 1), col(m, 1)
        print(f"  {m:5s}  초과많음군 {(1-rmM[hi].mean()/rmA[hi].mean())*100:+5.1f}%   "
              f"초과적음군 {(1-rmM[~hi].mean()/rmA[~hi].mean())*100:+5.1f}%")

    a, b = col("A", 1), col("B", 1)
    print(f"\n[마지막세션] B가 A보다 나은 러너: {int((b < a - 1e-6).sum())}/{N_RUN}  "
          f"(평균 {(1 - b.mean()/a.mean())*100:+.1f}%)")


if __name__ == "__main__":
    main()
