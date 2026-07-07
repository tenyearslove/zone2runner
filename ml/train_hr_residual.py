"""
심박 예측 잔차 학습 (gray-box / PINN, adr-020 후속, report-005).

구조: 최종예측 = 생리 ODE(뼈대) + 작은 NN(잔차). NN은 ODE가 못 담는 나머지(주로 개인/드리프트
비선형)만 배운다. 물리정보(PINN) 요소 = (1) 타깃이 'HR'이 아니라 'ODE 대비 잔차'라 학습이 물리
근방으로 구속됨, (2) weight_decay = "잔차는 작다(ODE를 신뢰)"는 사전편향, (3) 추론 시 잔차를 물리
경계로 clamp. 오프라인 학습 → 가중치만 폰에 실어 추론(실시간 재학습 안 함, report-005 §7 한계).

산출: ml/artifacts/hr_residual.json + app/.../assets/hr_residual.json (Kotlin 순전파).
채점(정직): ODE 단독 RMSE vs ODE+잔차 RMSE (홀드아웃 러너, bpm).
"""
import json
import os
import sys

import numpy as np
import torch
import torch.nn as nn

sys.path.insert(0, os.path.dirname(__file__))
from simulator import make_runner, generate_session, WARMUP_S

SEED = 42
TAU0 = 30.0          # 앱 콜드스타트 τ (HrOdeModel.TAU0)
HORIZONS = [30, 60]
STRIDE = 5
CLAMP_FRAC = 0.08    # 잔차 물리 경계(HRR 비율)
FEATURES = ["hr_now_frac", "dHR_bpm_s", "slope", "elapsed_min"]

ART = os.path.join(os.path.dirname(__file__), "artifacts")
ASSETS = os.path.join(os.path.dirname(__file__), "..", "app", "app", "src", "main", "assets")
os.makedirs(ART, exist_ok=True)


def ode_pred_frac(h_now, dhr_bpm_s, hrr, h, tau=TAU0):
    """앱 HrOdeModel의 콜드스타트 ODE 예측(drift=0 모집단). 현재 기울기로 정상상태 역추정 + mono-exp."""
    dh = dhr_bpm_s / hrr
    h_ss = np.clip(h_now + tau * dh, 0.20, 1.15)
    return np.clip(h_ss + (h_now - h_ss) * np.exp(-h / tau), 0.20, 1.15)


PACE_TOL = 0.6  # 페이스 유지 허용(min/km) — 앱 HrOdeModel과 동일 조건("이 페이스 유지 시" 예측)


def extract(session):
    r = session["runner"]; resting = r["resting"]; hrr = r["hrr"]
    # 앱 파이프라인 동등: OutlierGuard + 생리 상한(최대심박). 시뮬은 max_hr를 안 넘게 클램프 안 하므로 여기서 맞춘다.
    hr = np.clip(session["hr_obs"], 40.0, r["max_hr"])
    slope = session["slope"]; pace = session["pace"]
    n = len(hr); hmax = max(HORIZONS)
    X, Yres, H = [], [], []
    for t in range(WARMUP_S, n - hmax - 5, STRIDE):
        # 페이스 유지 게이팅: 앞으로 60초 페이스가 유지된 표본만(ODE의 조건부 가정과 일치)
        if np.max(np.abs(pace[t:t + hmax] - pace[t])) > PACE_TOL:
            continue
        h_now = (np.mean(hr[t - 10:t]) - resting) / hrr
        dhr = (hr[t] - hr[t - 30]) / 30.0
        X.append([h_now, dhr, slope[t], t / 60.0])
        res = []
        for h in HORIZONS:
            target = (np.mean(hr[t + h - 5:t + h + 5]) - resting) / hrr
            res.append(target - ode_pred_frac(h_now, dhr, hrr, h))
        Yres.append(res); H.append(hrr)
    return np.array(X), np.array(Yres), np.array(H)


def generate(n_runners=150, spr=8, dur=30, seed=SEED):
    rng = np.random.default_rng(seed)
    Xs, Ys, Hs, gs = [], [], [], []
    for rid in range(n_runners):
        runner = make_runner(rng)
        for _ in range(spr):
            X, Y, H = extract(generate_session(runner, rng, dur))
            Xs.append(X); Ys.append(Y); Hs.append(H); gs.append(np.full(len(Y), rid))
    return np.vstack(Xs), np.vstack(Ys), np.concatenate(Hs), np.concatenate(gs)


def group_split(g, seed=SEED, val=0.15, test=0.15):
    rng = np.random.default_rng(seed)
    runners = np.unique(g); rng.shuffle(runners)
    n = len(runners); nte = int(n * test); nva = int(n * val)
    te = set(runners[:nte]); va = set(runners[nte:nte + nva]); tr = set(runners[nte + nva:])
    idx = lambda S: np.array([i for i in range(len(g)) if g[i] in S])
    return idx(tr), idx(va), idx(te)


class Residual(nn.Module):
    def __init__(self, d_in=len(FEATURES), d_out=len(HORIZONS)):
        super().__init__()
        self.net = nn.Sequential(nn.Linear(d_in, 8), nn.ReLU(), nn.Linear(8, d_out))

    def forward(self, x):
        return self.net(x)


def rmse_bpm(err_frac, hrr):
    return np.sqrt(np.mean((err_frac * hrr[:, None]) ** 2, axis=0))


def main():
    print("데이터 생성(잔차, 러너 150 x 세션 8)...")
    X, Y, H, g = generate()
    print(f"샘플 {len(Y)}  특징 {X.shape[1]}  잔차출력 {Y.shape[1]}")
    tr, va, te = group_split(g)

    mean = X[tr].mean(0); scale = X[tr].std(0); scale[scale == 0] = 1.0
    def norm(a): return (a - mean) / scale
    Xtr = torch.tensor(norm(X[tr]), dtype=torch.float32)
    Ytr = torch.tensor(Y[tr], dtype=torch.float32)
    Xte = torch.tensor(norm(X[te]), dtype=torch.float32)

    torch.manual_seed(SEED); np.random.seed(SEED)
    model = Residual()
    # weight_decay = "잔차는 작다(ODE 신뢰)" 물리정보 사전편향
    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=3e-3)
    lossf = nn.MSELoss()
    best, best_state, bad = 1e9, None, 0
    Xva = torch.tensor(norm(X[va]), dtype=torch.float32); Yva = torch.tensor(Y[va], dtype=torch.float32)
    print("학습(잔차 MLP 4->8->2, MSE + weight_decay)...")
    for epoch in range(300):
        model.train(); perm = torch.randperm(len(Xtr))
        for i in range(0, len(Xtr), 512):
            b = perm[i:i + 512]
            opt.zero_grad(); loss = lossf(model(Xtr[b]), Ytr[b]); loss.backward(); opt.step()
        model.eval()
        with torch.no_grad():
            vl = lossf(model(Xva), Yva).item()
        if vl < best - 1e-7:
            best, best_state, bad = vl, {k: v.clone() for k, v in model.state_dict().items()}, 0
        else:
            bad += 1
            if bad >= 20:
                break
    model.load_state_dict(best_state); model.eval()

    with torch.no_grad():
        pred_te = model(Xte).numpy()
    hrr_te = H[te]
    ode_rmse = rmse_bpm(Y[te], hrr_te)                 # ODE 단독 오차 = 잔차 그 자체
    gray_rmse = rmse_bpm(Y[te] - pred_te, hrr_te)      # ODE + 잔차NN 오차
    print("\n=== 홀드아웃 채점 (bpm) ===")
    for i, h in enumerate(HORIZONS):
        imp = (1 - gray_rmse[i] / ode_rmse[i]) * 100 if ode_rmse[i] > 0 else 0
        print(f"  {h:>2}초  ODE단독 {ode_rmse[i]:.2f}  ODE+잔차 {gray_rmse[i]:.2f}  개선 {imp:+.1f}%")

    # export
    lin = [m for m in model.net if isinstance(m, nn.Linear)]
    layers = [{"w": l.weight.detach().numpy().tolist(), "b": l.bias.detach().numpy().tolist()} for l in lin]
    out = {
        "features": FEATURES, "horizons_sec": HORIZONS,
        "scaler_mean": mean.tolist(), "scaler_scale": scale.tolist(),
        "layers": layers, "clamp_frac": CLAMP_FRAC,
        "metrics": {f"ode_rmse_{h}": float(ode_rmse[i]) for i, h in enumerate(HORIZONS)}
                 | {f"gray_rmse_{h}": float(gray_rmse[i]) for i, h in enumerate(HORIZONS)},
    }
    for path in [os.path.join(ART, "hr_residual.json"), os.path.join(ASSETS, "hr_residual.json")]:
        with open(path, "w") as f:
            json.dump(out, f)
        print("wrote", path)


if __name__ == "__main__":
    main()
