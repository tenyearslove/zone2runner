"""
개인 심박 동역학 모델 학습/평가/export (spec-014, adr-013).

무엇을 배우나: "현재 상태 + 앞으로 유지할 페이스" -> 30초/60초 뒤 심박(HRR 비율) 회귀.
라벨 원칙: 정답 = 시뮬레이터가 '관측'한 실제 미래 심박. 시뮬레이터가 라벨을 정의하지 않는다
(spec-006 판정 MLP의 라벨 순환 결함 재발 방지 - adr-013 맥락 참조).

평가(정직한 채점): baseline 대비 RMSE(bpm)
  B0 persistence : hr(t+H) = hr_now
  B1 linear      : hr_now + dHR x H (생리 범위 clamp)
추가: 페이스 응답 방향성(AC3) - pace_plan 증가(느려짐) 시 예측 HR 단조 감소 위반율.

산출:
  ml/artifacts/hr_dynamics.json + app/app/src/main/assets/hr_dynamics.json (Kotlin 순전파용)
  ml/artifacts/hr_dynamics_metrics.json, hr_dynamics_results.png
"""
import json
import os

import numpy as np
import torch
import torch.nn as nn
from sklearn.preprocessing import StandardScaler

from simulator import make_runner, generate_session, WARMUP_S

SEED = 42
ART = os.path.join(os.path.dirname(__file__), "artifacts")
ASSETS = os.path.join(os.path.dirname(__file__), "..", "app", "app", "src", "main", "assets")
os.makedirs(ART, exist_ok=True)

# decoupling(드리프트) 특징은 ablation(ml/ablation_decoupling.py)에서 도움이 없어(30초 예측 오히려
# 악화) 제거함 — adr-013 옵션1. 드리프트는 표시/리포트 전용 지표로만 남긴다.
FEATURES = ["hr_now_frac", "hr_sus_frac", "dHR", "pace_plan", "slope", "spm", "elapsed_min"]
HORIZONS = [30, 60]  # 예측 지평(초)
STRIDE = 5


def extract_dynamics(session):
    """세션 시계열 -> (X, y, hrr). y = [hr_frac(t+30), hr_frac(t+60)] (각 ±5초 평균, 관측값).

    pace_plan/slope 는 미래 60초 실제 평균 - "이 페이스를 유지하면"의 what-if 조건화와 의미 일치
    (spec-014 §입력특징). 추론 시엔 후보 페이스/현재 경사를 공급한다.
    """
    r = session["runner"]
    hr = session["hr_obs"]; pace = session["pace"]; spm = session["spm"]; slope = session["slope"]
    resting, hrr = r["resting"], r["hrr"]
    n = len(hr)
    Hmax = max(HORIZONS)

    X, Y = [], []
    for t in range(WARMUP_S, n - Hmax - 5, STRIDE):
        hr_now = np.mean(hr[t - 10:t])
        hr_sus = np.mean(hr[t - 60:t])
        dHR = (hr[t] - hr[t - 30]) / 30.0
        pace_plan = np.mean(pace[t:t + 60])
        slope_fut = np.mean(slope[t:t + 60])
        X.append([
            (hr_now - resting) / hrr,
            (hr_sus - resting) / hrr,
            dHR,
            pace_plan,
            slope_fut,
            spm[t],
            t / 60.0,
        ])
        Y.append([(np.mean(hr[t + H - 5:t + H + 5]) - resting) / hrr for H in HORIZONS])
    return np.array(X), np.array(Y), hrr


def generate(n_runners=150, sessions_per_runner=8, duration_min=30, seed=SEED):
    rng = np.random.default_rng(seed)
    Xs, Ys, gs, hrrs = [], [], [], []
    for rid in range(n_runners):
        runner = make_runner(rng)
        for _ in range(sessions_per_runner):
            sess = generate_session(runner, rng, duration_min)
            X, Y, hrr = extract_dynamics(sess)
            Xs.append(X); Ys.append(Y)
            gs.append(np.full(len(Y), rid)); hrrs.append(np.full(len(Y), hrr))
    return np.vstack(Xs), np.vstack(Ys), np.concatenate(gs), np.concatenate(hrrs)


def group_split(g, seed=SEED, val=0.15, test=0.15):
    rng = np.random.default_rng(seed)
    runners = np.unique(g); rng.shuffle(runners)
    n = len(runners); n_test = int(n * test); n_val = int(n * val)
    test_r = set(runners[:n_test]); val_r = set(runners[n_test:n_test + n_val])
    train_r = set(runners[n_test + n_val:])
    idx = lambda S: np.array([i for i in range(len(g)) if g[i] in S])
    return idx(train_r), idx(val_r), idx(test_r)


class DynMLP(nn.Module):
    def __init__(self, d_in=len(FEATURES), d_out=2):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(d_in, 32), nn.ReLU(), nn.Dropout(0.1),
            nn.Linear(32, 16), nn.ReLU(),
            nn.Linear(16, d_out),
        )

    def forward(self, x):
        return self.net(x)


def rmse_bpm(pred_frac, true_frac, hrr):
    """HRR 비율 오차 -> bpm RMSE (러너별 HRR 반영)."""
    err = (pred_frac - true_frac) * hrr[:, None]
    return np.sqrt(np.mean(err ** 2, axis=0))


def main():
    print("데이터 생성(동역학, 러너 150 x 세션 8)...")
    X, Y, g, hrr = generate()
    print(f"샘플 {len(Y)}  특징 {X.shape[1]}  출력 {Y.shape[1]} (t+30, t+60)")
    tr, va, te = group_split(g)

    scaler = StandardScaler().fit(X[tr])
    Xtr = torch.tensor(scaler.transform(X[tr]), dtype=torch.float32)
    Xva = torch.tensor(scaler.transform(X[va]), dtype=torch.float32)
    Xte = torch.tensor(scaler.transform(X[te]), dtype=torch.float32)
    Ytr = torch.tensor(Y[tr], dtype=torch.float32)

    torch.manual_seed(SEED); np.random.seed(SEED)
    model = DynMLP()
    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)
    lossf = nn.MSELoss()
    best_val, best_state, patience, bad = float("inf"), None, 15, 0
    bs, n = 512, len(Xtr)
    # 페이스 단조성 강제(spec-014 FR3/AC3): 느린 페이스(값↑)는 예측 심박을 낮춰야 역질의가 성립.
    # 학습 중 pace 열을 +ε(표준화 단위) 흔들어 예측이 오르면 벌점 — decoupling 없이도 단조 보장.
    pace_col = FEATURES.index("pace_plan")
    MONO_EPS, MONO_W = 0.5, 6.0
    print(f"학습(MLP {len(FEATURES)}->32->16->2, MSE + 페이스 단조 페널티)...")
    for epoch in range(300):
        model.train(); perm = torch.randperm(n)
        for i in range(0, n, bs):
            b = perm[i:i + bs]
            opt.zero_grad()
            xb = Xtr[b]
            pred_b = model(xb)
            xb2 = xb.clone(); xb2[:, pace_col] = xb2[:, pace_col] + MONO_EPS
            pred_slow = model(xb2)
            mono = torch.relu(pred_slow - pred_b).mean() # 느려졌는데 심박 예측이 오르면 벌점
            (lossf(pred_b, Ytr[b]) + MONO_W * mono).backward(); opt.step()
        model.eval()
        with torch.no_grad():
            val = lossf(model(Xva), torch.tensor(Y[va], dtype=torch.float32)).item()
        if val < best_val:
            best_val, best_state, bad = val, {k: v.clone() for k, v in model.state_dict().items()}, 0
        else:
            bad += 1
            if bad >= patience:
                break
    model.load_state_dict(best_state); model.eval()
    with torch.no_grad():
        pred = model(Xte).numpy()

    # ---- 평가: baseline 대비 RMSE(bpm) ----
    hr_now = X[te][:, 0]; dHR_frac = X[te][:, 2] / hrr[te]  # dHR(bpm/s) -> frac/s
    rm_model = rmse_bpm(pred, Y[te], hrr[te])
    b0 = np.stack([hr_now, hr_now], axis=1)                                   # persistence
    b1 = np.stack([np.clip(hr_now + dHR_frac * H, 0.0, 1.1) for H in HORIZONS], axis=1)  # 선형 외삽
    rm_b0 = rmse_bpm(b0, Y[te], hrr[te])
    rm_b1 = rmse_bpm(b1, Y[te], hrr[te])

    print("\n" + "=" * 56)
    print("심박 동역학 모델 - baseline 대비 RMSE(bpm, 테스트 러너)")
    print("=" * 56)
    for i, H in enumerate(HORIZONS):
        ok = rm_model[i] < min(rm_b0[i], rm_b1[i])
        print(f"  t+{H:2d}s : model {rm_model[i]:5.2f} | persistence {rm_b0[i]:5.2f} | linear {rm_b1[i]:5.2f}"
              f"   (AC2 {'O' if ok else 'X'})")

    # ---- AC3: 페이스 응답 방향성 (스윕 grid에서 단조 감소 위반율) ----
    rng = np.random.default_rng(7)
    probe_idx = rng.choice(len(te), size=min(2000, len(te)), replace=False)
    paces = np.arange(4.0, 12.01, 0.5)
    viol, total = 0, 0
    Xprobe = X[te][probe_idx]
    for pi in range(len(paces) - 1):
        Xa = Xprobe.copy(); Xa[:, 3] = paces[pi]
        Xb = Xprobe.copy(); Xb[:, 3] = paces[pi + 1]
        with torch.no_grad():
            pa = model(torch.tensor(scaler.transform(Xa), dtype=torch.float32)).numpy()[:, 1]
            pb = model(torch.tensor(scaler.transform(Xb), dtype=torch.float32)).numpy()[:, 1]
        viol += int((pb > pa + 1e-6).sum()); total += len(pa)
    viol_rate = viol / total
    print(f"  [AC3] 페이스 단조성 위반율: {viol_rate:.3%} (목표 <5%: {'O' if viol_rate < 0.05 else 'X'})")

    # ---- export (Kotlin 순전파용 JSON, adr-011 규약) ----
    layers = []
    lin = [m for m in model.net if isinstance(m, nn.Linear)]
    for m in lin:
        layers.append({"w": m.weight.detach().numpy().tolist(), "b": m.bias.detach().numpy().tolist()})
    out = {
        "features": FEATURES,
        "horizons_sec": HORIZONS,
        "output": "hr_frac",  # (hr-RHR)/HRR - 회귀(softmax 없음)
        "scaler_mean": scaler.mean_.tolist(),
        "scaler_scale": scaler.scale_.tolist(),
        "layers": layers,
        "hidden_activation": "relu",
        "metrics": {
            "rmse_bpm_30": round(float(rm_model[0]), 3),
            "rmse_bpm_60": round(float(rm_model[1]), 3),
            "persistence_rmse_30": round(float(rm_b0[0]), 3),
            "persistence_rmse_60": round(float(rm_b0[1]), 3),
            "linear_rmse_30": round(float(rm_b1[0]), 3),
            "linear_rmse_60": round(float(rm_b1[1]), 3),
            "pace_monotonicity_violation": round(float(viol_rate), 5),
        },
    }
    os.makedirs(ASSETS, exist_ok=True)
    for path in (os.path.join(ART, "hr_dynamics.json"), os.path.join(ASSETS, "hr_dynamics.json")):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False)
        print("저장:", path)

    with open(os.path.join(ART, "hr_dynamics_metrics.json"), "w", encoding="utf-8") as f:
        json.dump(out["metrics"], f, ensure_ascii=False, indent=2)

    # ---- 플롯: 예측 vs 실제(t+60), 페이스 스윕 곡선 예시 ----
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(1, 2, figsize=(10, 4))
    s = rng.choice(len(te), size=min(3000, len(te)), replace=False)
    ax[0].scatter(Y[te][s, 1] * hrr[te][s], pred[s, 1] * hrr[te][s], s=2, alpha=0.2)
    lim = [40, 200]
    ax[0].plot(lim, lim, "r--", lw=1)
    ax[0].set_xlabel("actual HR-RHR x HRR scale (bpm-ish)"); ax[0].set_ylabel("predicted")
    ax[0].set_title(f"t+60s prediction (RMSE {rm_model[1]:.2f} bpm)")
    probe = Xprobe[0].copy()
    curve = []
    for p in paces:
        z = probe.copy(); z[3] = p
        with torch.no_grad():
            curve.append(model(torch.tensor(scaler.transform(z[None]), dtype=torch.float32)).numpy()[0, 1])
    ax[1].plot(paces, curve, "-o", ms=3)
    ax[1].set_xlabel("pace_plan (min/km)"); ax[1].set_ylabel("predicted hr_frac(t+60)")
    ax[1].set_title("pace sweep (inverse-query basis)")
    fig.tight_layout(); fig.savefig(os.path.join(ART, "hr_dynamics_results.png"), dpi=120)
    print("플롯 저장: artifacts/hr_dynamics_results.png")


if __name__ == "__main__":
    main()
