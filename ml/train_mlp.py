"""
Zone 2 판정 MLP 학습/평가 + 설계 결정 정량 검증 (spec-006 §2,4,5).

3단 사다리로 각 설계 결정의 기여를 정량화한다(보고서 구현·검증 근거):
  1) 규칙 baseline (공식 임계값)                  -- DP1 규칙만
  2) MLP (공식 정규화 특징)                        -- DP4 다변량 NN 추가
  3) MLP (개인화 정규화 특징, Bayesian 이후)        -- DP3 개인화 추가

- 데이터: simulator.generate_dataset (물리 시뮬레이터, 참 라벨)
- 분할: 러너 단위 train/val/test (누수 방지)
- 모델: MLP 7 -> 32 -> 16 -> 3 (spec-006 §2), Adam + CE + early stopping + 노이즈 증강
- 산출물: ml/artifacts/ 에 개인화 모델(.pt/onnx)/지표(json)/혼동행렬/사다리 플롯
"""
import json
import os

import numpy as np
import torch
import torch.nn as nn
from sklearn.metrics import accuracy_score, confusion_matrix, classification_report
from sklearn.preprocessing import StandardScaler

from simulator import generate_dataset, FEATURE_NAMES, LABEL_NAMES

SEED = 42
ART = os.path.join(os.path.dirname(__file__), "artifacts")
os.makedirs(ART, exist_ok=True)


def group_split(g, seed=SEED, val=0.15, test=0.15):
    rng = np.random.default_rng(seed)
    runners = np.unique(g); rng.shuffle(runners)
    n = len(runners); n_test = int(n * test); n_val = int(n * val)
    test_r = set(runners[:n_test]); val_r = set(runners[n_test:n_test + n_val])
    train_r = set(runners[n_test + n_val:])
    idx = lambda S: np.array([i for i in range(len(g)) if g[i] in S])
    return idx(train_r), idx(val_r), idx(test_r)


class MLP(nn.Module):
    def __init__(self, d_in=7, n_cls=3):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(d_in, 32), nn.ReLU(), nn.Dropout(0.1),
            nn.Linear(32, 16), nn.ReLU(),
            nn.Linear(16, n_cls),
        )

    def forward(self, x):
        return self.net(x)


def rule_baseline(X):
    """공식 임계값 규칙: hr_norm_u/l 로 3분류 (col0=상한대비, col1=하한대비)."""
    pred = np.ones(len(X), dtype=int)
    pred[X[:, 1] < 0.0] = 0   # 하한 미만 → below
    pred[X[:, 0] > 0.0] = 2   # 상한 초과 → above
    return pred


def train_eval(X, y, g, aug=0.05, seed=SEED):
    """MLP 학습 후 (test_acc, model, scaler, splits, preds) 반환."""
    torch.manual_seed(seed); np.random.seed(seed)
    tr, va, te = group_split(g, seed)
    scaler = StandardScaler().fit(X[tr])
    Xtr = torch.tensor(scaler.transform(X[tr]), dtype=torch.float32)
    Xva = torch.tensor(scaler.transform(X[va]), dtype=torch.float32)
    Xte = torch.tensor(scaler.transform(X[te]), dtype=torch.float32)
    ytr = torch.tensor(y[tr], dtype=torch.long)

    model = MLP()
    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)
    lossf = nn.CrossEntropyLoss()
    best_val, best_state, patience, bad = 0.0, None, 15, 0
    bs, n = 512, len(Xtr)
    for epoch in range(300):
        model.train(); perm = torch.randperm(n)
        for i in range(0, n, bs):
            b = perm[i:i + bs]
            xb = Xtr[b] + torch.randn_like(Xtr[b]) * aug  # 노이즈 증강(QA2)
            opt.zero_grad(); lossf(model(xb), ytr[b]).backward(); opt.step()
        model.eval()
        with torch.no_grad():
            val_acc = (model(Xva).argmax(1).numpy() == y[va]).mean()
        if val_acc > best_val:
            best_val, best_state, bad = val_acc, {k: v.clone() for k, v in model.state_dict().items()}, 0
        else:
            bad += 1
            if bad >= patience:
                break
    model.load_state_dict(best_state); model.eval()
    with torch.no_grad():
        pred_te = model(Xte).argmax(1).numpy()
    return accuracy_score(y[te], pred_te), model, scaler, (tr, va, te), pred_te


def main():
    print("데이터 생성 중 (formula / personalized 동일 세션)...")
    Xf, yf, gf = generate_dataset(mode="formula", seed=SEED)
    Xp, yp, gp = generate_dataset(mode="personalized", est_sigma=0.025, seed=SEED)
    _, _, te = group_split(gf)

    # 1) 규칙 baseline
    rule_acc = accuracy_score(yf[te], rule_baseline(Xf[te]))
    # 2) MLP (공식 정규화)
    acc_formula, *_ = train_eval(Xf, yf, gf)
    # 3) MLP (개인화 정규화) — 추정오차 민감도 확인
    Xp0, yp0, gp0 = generate_dataset(mode="personalized", est_sigma=0.0, seed=SEED)
    acc_pers0, *_ = train_eval(Xp0, yp0, gp0)
    print(f"[진단] 완벽 개인화(est_sigma=0) MLP 정확도: {acc_pers0:.3f}")
    acc_pers, model, scaler, (tr, va, tep), pred_te = train_eval(Xp, yp, gp)

    yte = yp[tep]
    cm = confusion_matrix(yte, pred_te)

    # 조건별 / 노이즈 스트레스 (개인화 모델 기준). 특징: 0=hr_norm_u,1=hr_norm_l,2=dHR,5=decoupling,6=slope
    slope_te = Xp[tep][:, 6]; dec_te = Xp[tep][:, 5]
    up = slope_te > 2; drift = dec_te > 0.3
    acc_up = accuracy_score(yte[up], pred_te[up]) if up.any() else float("nan")
    acc_drift = accuracy_score(yte[drift], pred_te[drift]) if drift.any() else float("nan")
    rng = np.random.default_rng(1)
    Xn = Xp[tep].copy()
    Xn[:, 0] += rng.normal(0, 0.05, len(tep)); Xn[:, 1] += rng.normal(0, 0.05, len(tep))
    Xn[:, 2] += rng.normal(0, 0.10, len(tep)); Xn[:, 5] += rng.normal(0, 0.08, len(tep))
    with torch.no_grad():
        pred_n = model(torch.tensor(scaler.transform(Xn), dtype=torch.float32)).argmax(1).numpy()
    acc_noisy = accuracy_score(yte, pred_n)

    print("\n" + "=" * 56)
    print("설계 결정 기여도 (Zone2 판정 정확도)")
    print("=" * 56)
    print(f"  1) 규칙 baseline (공식 임계값)        : {rule_acc:.3f}")
    print(f"  2) + 다변량 MLP (DP4)                 : {acc_formula:.3f}   (+{acc_formula-rule_acc:.3f})")
    print(f"  3) + 개인화 정규화 (DP3)              : {acc_pers:.3f}   (+{acc_pers-acc_formula:.3f})")
    # 이진 "Zone2 유지 여부" (in vs not) — 제품 실제 질문
    bin_true = (yte == 1).astype(int); bin_pred = (pred_te == 1).astype(int)
    acc_bin = accuracy_score(bin_true, bin_pred)
    rule_pred_te = rule_baseline(Xf[tep]); rule_bin = accuracy_score((yf[tep] == 1).astype(int), (rule_pred_te == 1).astype(int))
    print(f"     목표 0.85 달성(3분류): {'O' if acc_pers>=0.85 else 'X'}")
    print(f"  [이진] Zone2 유지 여부 정확도: MLP {acc_bin:.3f}  vs 규칙 {rule_bin:.3f}   목표 0.85: {'O' if acc_bin>=0.85 else 'X'}")
    # 방향 정확성(QA1): 반대 방향(below↔above) 오판율. 코칭 앱의 치명 오류.
    gross = np.mean(np.abs(pred_te - yte) == 2)
    print(f"  [방향] 반대방향 오판율: {gross:.4f}  → 방향 정확성 {1-gross:.3f}  (QA1 목표 0.95: {'O' if (1-gross)>=0.95 else 'X'})")
    print("-" * 56)
    print(f"  오르막 구간 정확도    : {acc_up:.3f}")
    print(f"  고드리프트 구간 정확도: {acc_drift:.3f}")
    print(f"  노이즈 스트레스 정확도: {acc_noisy:.3f}  (저하 {acc_pers-acc_noisy:+.3f})")
    print("\n혼동행렬 (행=참, 열=예측) [below, in, above]:")
    print(cm)
    print("\n" + classification_report(yte, pred_te, target_names=LABEL_NAMES, digits=3))

    torch.save({"state_dict": model.state_dict(),
                "scaler_mean": scaler.mean_.tolist(), "scaler_scale": scaler.scale_.tolist(),
                "features": FEATURE_NAMES}, os.path.join(ART, "zone2_mlp.pt"))
    try:
        torch.onnx.export(model, torch.zeros(1, 7), os.path.join(ART, "zone2_mlp.onnx"),
                          input_names=["features"], output_names=["logits"])
    except Exception as e:
        print("onnx export skip:", e)

    metrics = {
        "rule_baseline_accuracy": round(float(rule_acc), 4),
        "mlp_formula_accuracy": round(float(acc_formula), 4),
        "mlp_personalized_accuracy": round(float(acc_pers), 4),
        "uphill_accuracy": round(float(acc_up), 4),
        "high_drift_accuracy": round(float(acc_drift), 4),
        "noise_stress_accuracy": round(float(acc_noisy), 4),
        "binary_maintain_accuracy": round(float(acc_bin), 4),
        "directional_accuracy": round(float(1 - gross), 4),
        "confusion_matrix": cm.tolist(),
        "target_met_85_3class": bool(acc_pers >= 0.85),
        "target_met_95_directional": bool((1 - gross) >= 0.95),
    }
    with open(os.path.join(ART, "metrics.json"), "w") as f:
        json.dump(metrics, f, ensure_ascii=False, indent=2)

    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(1, 2, figsize=(9, 4))
    names = ["rule", "+MLP\n(DP4)", "+personalize\n(DP3)"]
    vals = [rule_acc, acc_formula, acc_pers]
    ax[0].bar(names, vals, color=["#bbb", "#69c", "#38a"])
    ax[0].axhline(0.85, color="r", ls="--", lw=1, label="target 0.85")
    ax[0].set_ylim(0, 1); ax[0].set_ylabel("Zone2 accuracy"); ax[0].set_title("design decision ladder"); ax[0].legend(fontsize=8)
    for i, v in enumerate(vals):
        ax[0].text(i, v + 0.02, f"{v:.2f}", ha="center", fontsize=9)
    im = ax[1].imshow(cm, cmap="Blues")
    ax[1].set_xticks(range(3)); ax[1].set_yticks(range(3))
    ax[1].set_xticklabels(LABEL_NAMES); ax[1].set_yticklabels(LABEL_NAMES)
    ax[1].set_xlabel("predicted"); ax[1].set_ylabel("true"); ax[1].set_title(f"confusion (acc={acc_pers:.3f})")
    for i in range(3):
        for j in range(3):
            ax[1].text(j, i, cm[i, j], ha="center", va="center",
                       color="white" if cm[i, j] > cm.max() / 2 else "black", fontsize=8)
    fig.tight_layout(); fig.savefig(os.path.join(ART, "results.png"), dpi=120)
    print("\n저장: artifacts/zone2_mlp.pt+onnx / metrics.json / results.png")


if __name__ == "__main__":
    main()
