"""
온디바이스(Android) 추론용 MLP를 학습하고 zone2_mlp.json 으로 export.

train_mlp.py(torch, 논문/실험 canonical)와 동일한 설계(입력 7 → 32 → 16 → 3, ReLU)를
scikit-learn MLPClassifier로 재적합한다. 목적은 Android에서 무거운 TFLite 런타임 없이
작은 MLP를 순수 Kotlin 순전파로 돌리는 것(adr-011): 가중치/스케일러를 JSON으로 뽑는다.

산출:
  - ml/artifacts/zone2_mlp.json        (검토/보관)
  - app/src/main/assets/zone2_mlp.json (앱이 로드)
검증지표(규칙 vs MLP, QA1 코칭방향, QA2 이상치기각)를 함께 출력/기록한다.
"""
import json
import os

import numpy as np
from sklearn.neural_network import MLPClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import accuracy_score, confusion_matrix

from simulator import generate_dataset, FEATURE_NAMES, LABEL_NAMES

SEED = 42
HERE = os.path.dirname(__file__)
ART = os.path.join(HERE, "artifacts")
ASSETS = os.path.join(HERE, "..", "app", "app", "src", "main", "assets")
os.makedirs(ART, exist_ok=True)


def group_split(g, seed=SEED, val=0.15, test=0.15):
    rng = np.random.default_rng(seed)
    runners = np.unique(g); rng.shuffle(runners)
    n = len(runners); n_test = int(n * test); n_val = int(n * val)
    test_r = set(runners[:n_test]); val_r = set(runners[n_test:n_test + n_val])
    train_r = set(runners[n_test + n_val:])
    idx = lambda S: np.array([i for i in range(len(g)) if g[i] in S])
    return idx(train_r), idx(val_r), idx(test_r)


def rule_baseline(X):
    pred = np.ones(len(X), dtype=int)
    pred[X[:, 1] < 0.0] = 0
    pred[X[:, 0] > 0.0] = 2
    return pred


def main():
    NR, NS = 150, 8
    print("데이터 생성(personalized)...")
    Xp, yp, gp = generate_dataset(mode="personalized", est_sigma=0.025,
                                  n_runners=NR, sessions_per_runner=NS, seed=SEED)
    Xf, yf, gf = generate_dataset(mode="formula",
                                  n_runners=NR, sessions_per_runner=NS, seed=SEED)
    tr, va, te = group_split(gp)

    scaler = StandardScaler().fit(Xp[tr])
    Xtr = scaler.transform(Xp[tr]); Xte = scaler.transform(Xp[te])

    print("MLP 학습(32,16 ReLU)...")
    clf = MLPClassifier(hidden_layer_sizes=(32, 16), activation="relu",
                        alpha=1e-4, batch_size=512, learning_rate_init=1e-3,
                        max_iter=300, early_stopping=True, n_iter_no_change=15,
                        random_state=SEED)
    clf.fit(Xtr, yp[tr])

    pred_te = clf.predict(Xte)
    mlp_acc = accuracy_score(yp[te], pred_te)

    # 규칙 baseline (formula 조건)
    _, _, tef = group_split(gf)
    rule_acc = accuracy_score(yf[tef], rule_baseline(Xf[tef]))

    # QA1 코칭 방향(반대방향 오판 없음), QA2 이상치 기각
    gross = float(np.mean(np.abs(pred_te - yp[te]) == 2))
    qa1 = 1 - gross
    from simulator import make_runner, generate_session
    r2 = np.random.default_rng(3); runner2 = make_runner(r2); sess2 = generate_session(runner2, r2, 30)
    hr_raw = sess2["hr_obs"].copy(); inj = r2.random(len(hr_raw)) < 0.05
    hr_raw[inj] = r2.choice([25.0, 250.0, 300.0], int(inj.sum()))
    kept = (hr_raw >= 40) & (hr_raw <= 220)
    qa2 = float(1 - kept[inj].mean())

    cm = confusion_matrix(yp[te], pred_te)
    print("=" * 48)
    print(f"  규칙 baseline      : {rule_acc:.3f}")
    print(f"  + MLP(개인화)       : {mlp_acc:.3f}")
    print(f"  [QA1] 코칭방향     : {qa1:.3f} ({'O' if qa1 >= 0.95 else 'X'})")
    print(f"  [QA2] 이상치기각   : {qa2:.3f} ({'O' if qa2 >= 0.999 else 'X'})")
    print("  혼동행렬:\n", cm)

    # 레이어 export: coefs_[i] shape (n_in, n_out), intercepts_[i] shape (n_out,)
    layers = [{"w": w.T.tolist(), "b": b.tolist()}   # w를 (n_out, n_in)로 저장(행=뉴런)
              for w, b in zip(clf.coefs_, clf.intercepts_)]
    model = {
        "features": FEATURE_NAMES,
        "labels": LABEL_NAMES,
        "scaler_mean": scaler.mean_.tolist(),
        "scaler_scale": scaler.scale_.tolist(),
        "layers": layers,          # 마지막 레이어 출력 = logits (argmax)
        "hidden_activation": "relu",
        "metrics": {
            "rule_acc": round(float(rule_acc), 4),
            "mlp_acc": round(float(mlp_acc), 4),
            "qa1_coaching_direction": round(float(qa1), 4),
            "qa2_outlier_reject": round(float(qa2), 4),
        },
    }

    os.makedirs(ASSETS, exist_ok=True)
    for path in (os.path.join(ART, "zone2_mlp.json"), os.path.join(ASSETS, "zone2_mlp.json")):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(model, f, ensure_ascii=False)
        print("저장:", os.path.relpath(path, os.path.join(HERE, "..")))


if __name__ == "__main__":
    main()
