"""
드리프트(decoupling) 특징 ablation (adr-013/spec-014 옵션1).

질문: 심박 동역학 모델이 decoupling(feat[7])을 실제로 필요로 하는가?
방법: 같은 데이터/분할/하이퍼파라미터로 (a) 8특징 전체 (b) decoupling 제거 7특징 학습,
      테스트 러너 RMSE(bpm) 비교. 차이가 미미하면 특징에서 제거해 모델을 단순/정직하게.
"""
import numpy as np
import torch
import torch.nn as nn
from sklearn.preprocessing import StandardScaler

from train_hr_dynamics import generate, group_split, DynMLP, rmse_bpm, HORIZONS, FEATURES

SEED = 42


def train_and_eval(X, Y, g, hrr, drop_idx=None, seed=SEED):
    if drop_idx is not None:
        X = np.delete(X, drop_idx, axis=1)
    tr, va, te = group_split(g, seed)
    scaler = StandardScaler().fit(X[tr])
    Xtr = torch.tensor(scaler.transform(X[tr]), dtype=torch.float32)
    Xva = torch.tensor(scaler.transform(X[va]), dtype=torch.float32)
    Xte = torch.tensor(scaler.transform(X[te]), dtype=torch.float32)
    Ytr = torch.tensor(Y[tr], dtype=torch.float32)

    torch.manual_seed(seed); np.random.seed(seed)
    model = DynMLP(d_in=X.shape[1])
    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)
    lossf = nn.MSELoss()
    best_val, best_state, bad = float("inf"), None, 0
    bs, n = 512, len(Xtr)
    for _ in range(300):
        model.train(); perm = torch.randperm(n)
        for i in range(0, n, bs):
            b = perm[i:i + bs]
            opt.zero_grad(); lossf(model(Xtr[b]), Ytr[b]).backward(); opt.step()
        model.eval()
        with torch.no_grad():
            val = lossf(model(Xva), torch.tensor(Y[va], dtype=torch.float32)).item()
        if val < best_val:
            best_val, best_state, bad = val, {k: v.clone() for k, v in model.state_dict().items()}, 0
        else:
            bad += 1
            if bad >= 15:
                break
    model.load_state_dict(best_state); model.eval()
    with torch.no_grad():
        pred = model(Xte).numpy()
    return rmse_bpm(pred, Y[te], hrr[te])


def main():
    print("데이터 생성...")
    X, Y, g, hrr = generate()
    print(f"특징: {FEATURES}")
    dec_idx = FEATURES.index("decoupling")

    rm_full = train_and_eval(X, Y, g, hrr)
    rm_drop = train_and_eval(X, Y, g, hrr, drop_idx=dec_idx)

    print("\n" + "=" * 60)
    print("decoupling(드리프트) 특징 ablation — 테스트 RMSE(bpm)")
    print("=" * 60)
    for i, H in enumerate(HORIZONS):
        delta = rm_drop[i] - rm_full[i]
        print(f"  t+{H:2d}s : 8특징(전체) {rm_full[i]:5.2f} | 7특징(제거) {rm_drop[i]:5.2f}"
              f"   Δ {delta:+.2f} bpm")
    worst = max(rm_drop[i] - rm_full[i] for i in range(len(HORIZONS)))
    print("-" * 60)
    if worst < 0.5:
        print(f"판정: 제거해도 최대 악화 {worst:+.2f}bpm (< 0.5) → 제거 권장(단순/정직)")
    else:
        print(f"판정: 제거 시 최대 {worst:+.2f}bpm 악화 → 특징 유지 근거 있음")


if __name__ == "__main__":
    main()
