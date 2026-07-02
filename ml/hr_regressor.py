"""
접근 B: HR 회귀 + 잔차 개인화 (성시원 제안, adr-006).

아이디어:
  1) 맥락(페이스/경사/케이던스/나이/안정심박/HRR)으로 예상 심박을 회귀 예측 (모집단 모델)
     - 정답 = 실측 심박 (측정 가능 → 실데이터 학습 가능. 직접분류(A)의 라벨 문제 해소)
  2) 개인별로 예측-실측 잔차(δ)로 보정 (개인 HR곡선 개인화)
  3) Zone2 경계 도출: (i) 잔차 오프셋 이동(제안 원안) vs (ii) aerobic decoupling 임계(리서치)
  4) 실측 HR을 개인 경계와 비교해 3분류 → 코칭 방향 정확성 평가 (A와 동일 지표)

기존 분류 방식(train_mlp.py)은 보존. 같은 시뮬레이터/같은 test 러너로 공정 비교.
"""
import json
import os

import numpy as np
from sklearn.neural_network import MLPRegressor
from sklearn.metrics import mean_absolute_error

from simulator import make_runner, generate_session, WARMUP_S, STRIDE, FORMULA_UPPER_FRAC

SEED = 42
ART = os.path.join(os.path.dirname(__file__), "artifacts")
os.makedirs(ART, exist_ok=True)
BAND_FRAC = 0.10  # Zone2 폭(HRR 비율) 가정


def build_runner_data(n_runners=60, sessions=6, seed=SEED, u_frac_sigma=0.045):
    """러너별 (윈도우 특징 + 실측HR + decoupling + 참라벨 + 참경계)."""
    rng = np.random.default_rng(seed)
    data = []
    for rid in range(n_runners):
        runner = make_runner(rng, u_frac_sigma=u_frac_sigma)
        resting, hrr = runner["resting"], runner["hrr"]
        u_abs = resting + runner["u_frac"] * hrr
        l_abs = resting + runner["l_frac"] * hrr
        rows = []
        for _ in range(sessions):
            s = generate_session(runner, rng, 30)
            hr, pace, spm, slope = s["hr_obs"], s["pace"], s["spm"], s["slope"]
            base = np.mean(hr[WARMUP_S - 60:WARMUP_S] / pace[WARMUP_S - 60:WARMUP_S])
            for t in range(WARMUP_S, len(hr), STRIDE):
                hr_w = float(np.mean(hr[t - 60:t]))
                pace_w = float(np.mean(pace[t - 60:t]))
                decoup = float(np.mean(hr[t - 30:t] / pace[t - 30:t]) / base - 1)
                rows.append((pace_w, slope[t], spm[t], runner["age"], resting, hrr,
                             hr_w, decoup, int(s["label"][t])))
        data.append({"rid": rid, "rows": np.array(rows, dtype=float),
                     "u_abs": u_abs, "l_abs": l_abs, "resting": resting, "hrr": hrr})
    return data


def group_split(n, seed=SEED, val=0.15, test=0.15):
    rng = np.random.default_rng(seed)
    r = np.arange(n); rng.shuffle(r)
    nt, nv = int(n * test), int(n * val)
    return set(r[nt + nv:]), set(r[nt:nt + nv]), set(r[:nt])  # train, val, test


# 컬럼 인덱스
PACE, SLOPE, SPM, AGE, REST, HRR, HR, DEC, LAB = range(9)


def evaluate_B(u_frac_sigma=0.045, seed=SEED, verbose=True):
    data = build_runner_data(seed=seed, u_frac_sigma=u_frac_sigma)
    train_r, _, test_r = group_split(len(data), seed)

    # ---- 1) 모집단 HR 회귀 모델 (정답 = 실측 HR) ----
    tr = np.vstack([d["rows"] for d in data if d["rid"] in train_r])
    Xcols = [PACE, SLOPE, SPM, AGE, REST, HRR]
    reg = MLPRegressor(hidden_layer_sizes=(32, 16), max_iter=400, random_state=SEED)
    reg.fit(tr[:, Xcols], tr[:, HR])

    # ---- test 러너 평가 ----
    pop_mae, pers_mae = [], []
    err_formula, err_offset, err_decoup = [], [], []   # Zone2 상한 추정 오차
    dir_acc_all, dir_acc_formula = [], []
    for d in data:
        if d["rid"] not in test_r:
            continue
        R = d["rows"]
        pred = reg.predict(R[:, Xcols])
        pop_mae.append(mean_absolute_error(R[:, HR], pred))

        # 2) 개인화: 캘리브레이션에서 선형보정(actual ~ a*pred + b) 적합, 뒤에 적용
        k = len(R) // 3
        a, b = np.polyfit(pred[:k], R[:k, HR], 1)
        pred_pers = a * pred + b
        delta = float(np.mean(R[:k, HR] - pred[:k]))
        pers_mae.append(mean_absolute_error(R[k:, HR], pred_pers[k:]))

        # 3) Zone2 상한(U) 추정 3가지
        formula_U = d["resting"] + FORMULA_UPPER_FRAC * d["hrr"]         # (a) 공식(개인화 X)
        offset_U = formula_U + delta                                     # (b) 제안 원안: 잔차 오프셋 이동
        # (c) 리서치: decoupling 상승점(HR-binning) = 유산소 임계
        order = np.argsort(R[:, HR]); hr_s, dec_s = R[order, HR], R[order, DEC]
        decoup_U = formula_U
        wbin = 60
        for i in range(wbin, len(hr_s)):
            if np.mean(dec_s[i - wbin:i]) > 0.05:
                decoup_U = float(hr_s[i - wbin // 2]); break
        err_formula.append(abs(formula_U - d["u_abs"]))
        err_offset.append(abs(offset_U - d["u_abs"]))
        err_decoup.append(abs(decoup_U - d["u_abs"]))

        # 4) 경계로 3분류 → 코칭 방향 정확성 (A와 동일 지표). 두 경계로 비교
        def direction_acc(U):
            L = U - BAND_FRAC * d["hrr"]
            pz = np.ones(len(R), dtype=int)
            pz[R[:, HR] < L] = 0; pz[R[:, HR] > U] = 2
            return 1 - np.mean(np.abs(pz - R[:, LAB]) == 2)
        dir_acc_all.append(direction_acc(decoup_U))
        dir_acc_formula.append(direction_acc(formula_U))

    res = {
        "hr_mae_population_bpm": round(float(np.mean(pop_mae)), 2),
        "hr_mae_personalized_bpm": round(float(np.mean(pers_mae)), 2),
        "zone2_upper_err_formula_bpm": round(float(np.mean(err_formula)), 2),
        "zone2_upper_err_offset_bpm": round(float(np.mean(err_offset)), 2),
        "zone2_upper_err_decoupling_bpm": round(float(np.mean(err_decoup)), 2),
        "coaching_direction_accuracy_B_decoupling": round(float(np.mean(dir_acc_all)), 4),
        "coaching_direction_accuracy_B_formula_boundary": round(float(np.mean(dir_acc_formula)), 4),
    }
    if verbose:
        print("=" * 56)
        print("접근 B: HR 회귀 + 잔차 개인화")
        print("=" * 56)
        print(f"HR 예측 오차(MAE): 모집단 {res['hr_mae_population_bpm']} → 개인화 {res['hr_mae_personalized_bpm']} bpm")
        print("  (개인화가 HR 예측 오차를 줄임 = 잔차 보정 유효. 정답=실측HR로 측정 가능)")
        print("\nZone2 상한 추정 오차(참값 대비, bpm):")
        print(f"  (a) 공식(개인화X)        : {res['zone2_upper_err_formula_bpm']}")
        print(f"  (b) 제안 원안(잔차 오프셋): {res['zone2_upper_err_offset_bpm']}")
        print(f"  (c) 리서치(decoupling)   : {res['zone2_upper_err_decoupling_bpm']}")
        print(f"\n코칭 방향 정확성(B): decoupling경계 {res['coaching_direction_accuracy_B_decoupling']}"
              f" / 공식경계 {res['coaching_direction_accuracy_B_formula_boundary']}")
        print("  (공식경계가 훨씬 높으면, 문제는 판정이 아니라 임계 추정에 있음)")
        with open(os.path.join(ART, "metrics_B.json"), "w") as f:
            json.dump(res, f, ensure_ascii=False, indent=2)
        print("\n저장: artifacts/metrics_B.json")
    return res


if __name__ == "__main__":
    evaluate_B()
