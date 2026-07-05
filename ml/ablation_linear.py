"""
"꼭 NN이어야 하나?" ablation (심사 방어용) — 같은 특징/데이터/분할에서
선형 회귀(Ridge) vs MLP. 비선형 함수근사가 실제로 필요한지 정량 확인.

추가 baseline: 러너별 개인 선형(개인 기울기 허용 — "개인차는 선형으로도 되지 않나" 방어).
"""
import numpy as np
import torch
from sklearn.linear_model import Ridge
from sklearn.preprocessing import StandardScaler

from train_hr_dynamics import generate, group_split, DynMLP, rmse_bpm, HORIZONS, FEATURES

SEED = 42


def main():
    print("데이터 생성...")
    X, Y, g, hrr = generate()
    tr, va, te = group_split(g)
    scaler = StandardScaler().fit(X[tr])
    Xtr_s, Xte_s = scaler.transform(X[tr]), scaler.transform(X[te])

    # ---- B2: 전역 선형(Ridge) — 같은 7특징 ----
    lin = Ridge(alpha=1.0).fit(Xtr_s, Y[tr])
    rm_lin = rmse_bpm(lin.predict(Xte_s), Y[te], hrr[te])

    # ---- B2': 2차 상호작용 선형(pace x slope 등) — "특징공학한 선형이면 되지 않나" 방어 ----
    def poly2(A):
        n, d = A.shape
        cols = [A]
        for i in range(d):
            for j in range(i, d):
                cols.append((A[:, i] * A[:, j])[:, None])
        return np.hstack(cols)
    lin2 = Ridge(alpha=1.0).fit(poly2(Xtr_s), Y[tr])
    rm_lin2 = rmse_bpm(lin2.predict(poly2(Xte_s)), Y[te], hrr[te])

    # ---- MLP (train_hr_dynamics와 동일 설정, 단조 페널티 포함) ----
    torch.manual_seed(SEED); np.random.seed(SEED)
    Xtr_t = torch.tensor(Xtr_s, dtype=torch.float32)
    Xva_t = torch.tensor(scaler.transform(X[va]), dtype=torch.float32)
    Ytr_t = torch.tensor(Y[tr], dtype=torch.float32)
    model = DynMLP()
    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)
    lossf = torch.nn.MSELoss()
    pace_col = FEATURES.index("pace_plan")
    best_val, best_state, bad = float("inf"), None, 0
    bs, n = 512, len(Xtr_t)
    for _ in range(300):
        model.train(); perm = torch.randperm(n)
        for i in range(0, n, bs):
            b = perm[i:i + bs]
            opt.zero_grad()
            xb = Xtr_t[b]; pred_b = model(xb)
            xb2 = xb.clone(); xb2[:, pace_col] += 0.5
            mono = torch.relu(model(xb2) - pred_b).mean()
            (lossf(pred_b, Ytr_t[b]) + 6.0 * mono).backward(); opt.step()
        model.eval()
        with torch.no_grad():
            val = lossf(model(Xva_t), torch.tensor(Y[va], dtype=torch.float32)).item()
        if val < best_val:
            best_val, best_state, bad = val, {k: v.clone() for k, v in model.state_dict().items()}, 0
        else:
            bad += 1
            if bad >= 15: break
    model.load_state_dict(best_state); model.eval()
    with torch.no_grad():
        rm_mlp = rmse_bpm(model(torch.tensor(Xte_s, dtype=torch.float32)).numpy(), Y[te], hrr[te])

    print("\n" + "=" * 64)
    print("'꼭 NN인가' ablation — 같은 특징/분할, 테스트 RMSE(bpm)")
    print("=" * 64)
    for i, H in enumerate(HORIZONS):
        print(f"  t+{H:2d}s : 선형 {rm_lin[i]:5.2f} | 선형+2차상호작용 {rm_lin2[i]:5.2f} | MLP {rm_mlp[i]:5.2f}"
              f"   (MLP가 선형 대비 {100*(rm_lin[i]-rm_mlp[i])/rm_lin[i]:+.0f}%)")


if __name__ == "__main__":
    main()
