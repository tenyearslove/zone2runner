"""
개인 젖산역치(Zone2 상단 uFrac) 추정 MLP 학습/평가/export (spec-015, adr-014).

문헌(Etxegarai 2018, Zhu 2025)의 "다신호 -> 신경망 -> 무랩 역치 추정" 접근을 1인 과제 규모로 경량화.
세션 특징(심박-속도 반응 곡선/드리프트/체력 대리) -> 참 uFrac 회귀. 라벨=시뮬 참 역치.
평가: 참 대비 MAE(bpm)를 공식(고정 0.70) baseline과 비교.

산출: ml/artifacts/threshold_mlp.json + app/app/src/main/assets/threshold_mlp.json
"""
import json
import os

import numpy as np
from sklearn.neural_network import MLPRegressor
from sklearn.preprocessing import StandardScaler

from simulator import make_runner, generate_session, WARMUP_S

SEED = 42
HERE = os.path.dirname(__file__)
ART = os.path.join(HERE, "artifacts")
ASSETS = os.path.join(HERE, "..", "app", "app", "src", "main", "assets")
os.makedirs(ART, exist_ok=True)

FEATURES = ["hr_frac_slow", "hr_frac_mid", "hr_frac_fast", "hr_speed_slope",
            "drift", "cadence_n", "rhr_frac", "age_n"]


def session_features(sess):
    """세션 시계열 -> 특징 8종(관측 가능한 것만, 참 경계 미포함). 워밍업 이후 사용."""
    r = sess["runner"]
    hr = sess["hr_obs"][WARMUP_S:]
    pace = sess["pace"][WARMUP_S:]
    spm = sess["spm"][WARMUP_S:]
    resting, hrr, max_hr = r["resting"], r["hrr"], r["max_hr"]
    hr_frac = (hr - resting) / hrr
    speed = 16.667 / np.clip(pace, 3.0, 12.0)  # m/s (16.667/pace(min/km))

    # 속도 삼분위로 느림/중간/빠름 구간의 HR 비율 평균
    q1, q2 = np.quantile(speed, [1 / 3, 2 / 3])
    slow = hr_frac[speed <= q1]; mid = hr_frac[(speed > q1) & (speed <= q2)]; fast = hr_frac[speed > q2]
    f_slow = float(slow.mean()) if len(slow) else float(hr_frac.mean())
    f_mid = float(mid.mean()) if len(mid) else float(hr_frac.mean())
    f_fast = float(fast.mean()) if len(fast) else float(hr_frac.mean())

    # HR(비율) vs 속도 선형 기울기(심박 반응성)
    slope = float(np.polyfit(speed, hr_frac, 1)[0]) if np.std(speed) > 1e-3 else 0.0

    # 드리프트: 후반/전반 HR/속도 비율 상승률
    half = len(hr) // 2
    ratio = hr / np.clip(speed, 0.5, None)
    r1, r2 = ratio[:half].mean(), ratio[half:].mean()
    drift = float(r2 / r1 - 1.0) if r1 > 0 else 0.0

    return [f_slow, f_mid, f_fast, slope, drift,
            float(spm.mean()) / 200.0, resting / max_hr, r["age"] / 100.0]


def generate(n_runners=400, sessions_per_runner=3, seed=SEED):
    rng = np.random.default_rng(seed)
    X, Y, meta = [], [], []
    for _ in range(n_runners):
        runner = make_runner(rng, u_frac_sigma=0.06)  # 개인차 폭 확대(추정 여지)
        for _ in range(sessions_per_runner):
            sess = generate_session(runner, rng, duration_min=25)
            X.append(session_features(sess))
            Y.append(runner["u_frac"])  # 참 역치(라벨)
            meta.append((runner["resting"], runner["hrr"]))
    return np.array(X), np.array(Y), meta


def main():
    print("데이터 생성(세션 특징 -> 참 uFrac)...")
    X, Y, meta = generate()
    n = len(Y)
    rng = np.random.default_rng(SEED)
    idx = rng.permutation(n); nte = n // 5
    te, tr = idx[:nte], idx[nte:]
    print(f"샘플 {n}  특징 {X.shape[1]}  (train {len(tr)} / test {len(te)})")

    scaler = StandardScaler().fit(X[tr])
    Xtr, Xte = scaler.transform(X[tr]), scaler.transform(X[te])
    clf = MLPRegressor(hidden_layer_sizes=(16, 8), activation="relu", alpha=1e-3,
                       max_iter=1000, early_stopping=True, n_iter_no_change=20, random_state=SEED)
    clf.fit(Xtr, Y[tr])
    pred = clf.predict(Xte)

    # MAE(bpm): uFrac 오차 x HRR
    hrr_te = np.array([meta[i][1] for i in te])
    mae_nn = float(np.mean(np.abs(pred - Y[te]) * hrr_te))
    mae_formula = float(np.mean(np.abs(0.70 - Y[te]) * hrr_te))  # 공식 고정 0.70

    print("\n" + "=" * 56)
    print("개인 역치(uFrac) 추정 — 참 대비 MAE(bpm)")
    print("=" * 56)
    print(f"  공식 고정(0.70)   : {mae_formula:5.2f} bpm")
    print(f"  MLP 추정          : {mae_nn:5.2f} bpm   ({100*(mae_formula-mae_nn)/mae_formula:+.0f}%)")
    print(f"  (AC2 {'O' if mae_nn < mae_formula else 'X'}: 공식보다 낮은 오차)")

    # export (Kotlin 순전파용)
    layers = [{"w": w.T.tolist(), "b": b.tolist()} for w, b in zip(clf.coefs_, clf.intercepts_)]
    out = {
        "features": FEATURES,
        "output": "u_frac",
        "scaler_mean": scaler.mean_.tolist(),
        "scaler_scale": scaler.scale_.tolist(),
        "layers": layers,
        "hidden_activation": "relu",
        "metrics": {"mae_bpm_nn": round(mae_nn, 3), "mae_bpm_formula": round(mae_formula, 3)},
    }
    os.makedirs(ASSETS, exist_ok=True)
    for p in (os.path.join(ART, "threshold_mlp.json"), os.path.join(ASSETS, "threshold_mlp.json")):
        with open(p, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False)
        print("저장:", p)


if __name__ == "__main__":
    main()
