"""
물리 기반 Zone 2 러닝 세션 시뮬레이터.

목적 (spec-004 §8, spec-006 §3):
  - 참 Zone 2 경계 θ*를 아는 가상 러너의 세션을 생성.
  - 시점별 다변량 특징 + 참 Zone 라벨을 산출 → MLP 학습 데이터.
  - Bayesian 개인화 추정기 검증용 세션도 동일 엔진에서 생성.

설계 요지:
  - 러너마다 참 Zone2 상한 비율 u_frac 이 공식값(0.70)에서 개인차만큼 벗어남.
    → 공식 임계값만으로는 못 맞추는 부분을, 다신호(특히 디커플링)로 판별해야 함.
  - 관측 HR 은 목표 강도(effort)에 1차 지연으로 따라가고, 참 상한 초과 시
    Cardiac Drift(디커플링)가 누적됨. 센서 노이즈 포함.
  - 라벨은 '현재 강도(effort_frac)'가 참 경계 [l_frac, u_frac] 안이냐로 결정.

특징 벡터 순서 (spec-006 §1):
  [hr_frac, dHR, pace, spm, decoupling, slope]
  - hr_frac    : (HR_obs - restingHR) / HRR   (공식 기반 정규화, 개인 경계 미포함 → 라벨 누수 없음)
  - dHR        : HR 변화율 (bpm/s)
  - pace       : min/km
  - spm        : 케이던스 (steps/min)
  - decoupling : 최근 HR/pace 대비 세션 초반 baseline 대비 상승률 (Cardiac Drift 지표)
  - slope      : 고도 경사 (%)
라벨: 0=미달(below), 1=유지(in), 2=초과(above)
"""

import numpy as np

FEATURE_NAMES = ["hr_norm_u", "hr_norm_l", "dHR", "pace", "spm", "decoupling", "slope"]
LABEL_NAMES = ["below", "in", "above"]

DT = 1.0          # 내부 시뮬레이션 간격(초)
STRIDE = 5        # 특징 추출 간격(초)
WARMUP_S = 120    # 초반 워밍업(특징 추출 제외 + decoupling baseline 확보)


def make_runner(rng):
    """가상 러너 프로파일 생성. 참 경계는 공식에서 개인차만큼 벗어난다."""
    age = int(rng.integers(20, 55))
    resting = float(rng.uniform(48, 68))
    max_hr = 208 - 0.7 * age                      # Tanaka
    hrr = max_hr - resting
    # 참 Zone2 상한 비율: 공식은 0.70이라 가정하지만 개인은 다름
    u_frac = float(np.clip(rng.normal(0.70, 0.045), 0.58, 0.82))
    band = float(rng.uniform(0.08, 0.13))         # Zone2 폭
    l_frac = u_frac - band
    return {
        "age": age, "resting": resting, "max_hr": max_hr, "hrr": hrr,
        "u_frac": u_frac, "l_frac": l_frac,
        "hr_tau": float(rng.uniform(20, 40)),      # HR 지연 시상수(초)
        "drift_scale": float(rng.uniform(0.30, 0.60)),  # 지속 초과 시 드리프트 상한 계수
        "drift_tau": float(rng.uniform(90, 180)),  # 드리프트 상승/회복 시상수(초)
        "noise_sd": float(rng.uniform(1.5, 3.5)),  # 센서 노이즈(bpm)
        "base_pace": float(rng.uniform(6.5, 8.0)),  # demand=0.5 부근 기준 페이스(min/km)
    }


def _effort_profile(n, rng):
    """세션 강도(effort_frac) 시계열: 워밍업→메인→간헐 서지. 0.4~0.9 범위."""
    prof = np.empty(n)
    t = 0
    # 워밍업 램프
    w = min(WARMUP_S, n)
    prof[:w] = np.linspace(0.45, 0.62, w)
    t = w
    while t < n:
        seg = int(rng.integers(60, 240))
        seg = min(seg, n - t)
        target = float(np.clip(rng.normal(0.66, 0.09), 0.4, 0.92))
        prof[t:t + seg] = target
        t += seg
    # 부드럽게(이동평균)
    k = 15
    kernel = np.ones(k) / k
    prof = np.convolve(prof, kernel, mode="same")
    return np.clip(prof, 0.35, 0.95)


def _terrain_profile(n, rng):
    """고도 경사(%) 시계열: 평지/오르막/내리막 세그먼트."""
    slope = np.zeros(n)
    t = 0
    while t < n:
        seg = int(rng.integers(90, 300))
        seg = min(seg, n - t)
        s = float(rng.choice([0, 0, 0, 3, 5, -3, -5], p=[.34, .2, .1, .12, .08, .1, .06]))
        slope[t:t + seg] = s
        t += seg
    k = 20
    return np.convolve(slope, np.ones(k) / k, mode="same")


def generate_session(runner, rng, duration_min=30):
    """한 세션의 시계열 생성."""
    n = int(duration_min * 60 / DT)
    effort = _effort_profile(n, rng)          # 참 강도 비율
    slope = _terrain_profile(n, rng)

    resting, hrr = runner["resting"], runner["hrr"]
    u_abs = resting + runner["u_frac"] * hrr  # 참 상한(bpm)

    # 경사 보정: 오르막은 같은 페이스라도 강도↑ (관측 HR 유발 강도 상승)
    effort_eff = np.clip(effort + 0.012 * slope, 0.3, 1.05)
    effort_hr = resting + effort_eff * hrr    # 목표(정상상태) HR

    hr = np.empty(n)
    hr[0] = resting + effort_eff[0] * hrr
    drift = 0.0
    for i in range(1, n):
        # 1차 지연으로 목표 HR 추종
        hr_lag = hr[i - 1] + (effort_hr[i] - hr[i - 1]) * (DT / runner["hr_tau"])
        # Cardiac Drift: 참 상한 초과분에 비례한 목표치로 1차 완화(내려가면 회복)
        excess = max(0.0, effort_hr[i] - u_abs)
        drift_target = runner["drift_scale"] * excess
        drift += (drift_target - drift) * (DT / runner["drift_tau"])
        hr[i] = hr_lag + drift

    # 관측 노이즈
    hr_obs = hr + rng.normal(0, runner["noise_sd"], n)

    # 페이스: 강도↑ → 페이스↓(빨라짐), 오르막은 같은 강도서 페이스↓
    pace = runner["base_pace"] - 3.2 * (effort - 0.5) + 0.10 * slope
    pace += rng.normal(0, 0.08, n)
    pace = np.clip(pace, 3.5, 11.0)
    # 케이던스: 페이스와 완만한 음의 상관
    spm = 168 - 4.0 * (pace - 6.0) + rng.normal(0, 2.5, n)
    spm = np.clip(spm, 150, 200)

    # 참 라벨: '지속 강도' vs 참 경계 (Zone 상태는 순간이 아니라 지속 강도 개념)
    ks = 41
    effort_sustained = np.convolve(effort_eff, np.ones(ks) / ks, mode="same")
    label = np.ones(n, dtype=int)  # in
    label[effort_sustained < runner["l_frac"]] = 0  # below
    label[effort_sustained > runner["u_frac"]] = 2  # above

    return {
        "hr_obs": hr_obs, "pace": pace, "spm": spm, "slope": slope,
        "effort_eff": effort_eff, "label": label, "runner": runner,
    }


def extract_features(session, u_est, l_est):
    """세션 시계열 → (X, y). STRIDE 간격, 워밍업 이후만.

    u_est, l_est: 개인 Zone2 상한/하한 추정 비율. HR을 개인 경계로 정규화
      (spec-006 §1: hr_norm = 개인 경계 μ,band 기준 정규화).
      - 공식 조건: u=0.70, l=0.60 (개인화 없음)
      - 개인화 조건: 참 경계 + 추정오차 (Bayesian 이후 잔차 모사)
    """
    r = session["runner"]
    hr = session["hr_obs"]; pace = session["pace"]; spm = session["spm"]; slope = session["slope"]
    resting, hrr = r["resting"], r["hrr"]
    n = len(hr)

    base_win = slice(WARMUP_S - 60, WARMUP_S)
    base_ratio = np.mean(hr[base_win] / pace[base_win])

    W = 30        # dHR / decoupling 윈도우
    HRW = 60      # 지속 상태 윈도우 (라벨이 지속 강도 기준이므로 특징도 지속값 사용)
    X, y = [], []
    for t in range(WARMUP_S, n, STRIDE):
        hr_recent = np.mean(hr[t - HRW:t])          # 지속 HR
        pace_recent = np.mean(pace[t - HRW:t])       # 지속 페이스
        hr_frac = (hr_recent - resting) / hrr
        hr_norm_u = hr_frac - u_est                  # 개인 상한 대비 위치
        hr_norm_l = hr_frac - l_est                  # 개인 하한 대비 위치
        dHR = (hr[t] - hr[t - W]) / W
        recent_ratio = np.mean(hr[t - W:t] / pace[t - W:t])
        decoupling = recent_ratio / base_ratio - 1.0
        X.append([hr_norm_u, hr_norm_l, dHR, pace_recent, spm[t], decoupling, slope[t]])
        y.append(session["label"][t])
    return np.array(X), np.array(y)


FORMULA_UPPER_FRAC = 0.70  # 공식(HRR) Zone2 상한 비율


def generate_dataset(n_runners=60, sessions_per_runner=6, duration_min=30, seed=42,
                     mode="personalized", est_sigma=0.025):
    """러너 단위로 세션 생성 → (X, y, groups).

    mode:
      "formula"      : HR을 공식(0.70)으로 정규화 (개인화 없음)
      "personalized" : HR을 개인 추정경계(참 + 추정오차 est_sigma)로 정규화
    세션 생성 RNG와 추정오차 RNG를 분리해, 두 모드가 동일 세션을 공유한다.
    """
    rng = np.random.default_rng(seed)
    est_rng = np.random.default_rng(seed + 1000)
    Xs, ys, gs = [], [], []
    for rid in range(n_runners):
        runner = make_runner(rng)
        if mode == "formula":
            u_est, l_est = FORMULA_UPPER_FRAC, FORMULA_UPPER_FRAC - 0.10
        else:
            u_est = float(np.clip(runner["u_frac"] + est_rng.normal(0, est_sigma), 0.5, 0.85))
            band_true = runner["u_frac"] - runner["l_frac"]
            band_est = float(np.clip(band_true + est_rng.normal(0, est_sigma), 0.05, 0.20))
            l_est = u_est - band_est
        for _ in range(sessions_per_runner):
            sess = generate_session(runner, rng, duration_min)
            X, y = extract_features(sess, u_est, l_est)
            Xs.append(X); ys.append(y); gs.append(np.full(len(y), rid))
    return np.vstack(Xs), np.concatenate(ys), np.concatenate(gs)


if __name__ == "__main__":
    import os
    X, y, g = generate_dataset(n_runners=60, sessions_per_runner=6, seed=42, mode="formula")
    print(f"샘플 수: {len(y)}  | 러너 수: {len(np.unique(g))}  | 특징 수: {X.shape[1]}")
    print("특징:", FEATURE_NAMES, "(hr_norm_u/l = 공식 상/하한 대비 위치)")
    print("클래스 분포:", {LABEL_NAMES[i]: int((y == i).sum()) for i in range(3)})
    print("\n특징 통계 (mean / std / min / max):")
    for j, name in enumerate(FEATURE_NAMES):
        c = X[:, j]
        print(f"  {name:11s}: {c.mean():7.3f} / {c.std():6.3f} / {c.min():7.3f} / {c.max():7.3f}")

    # 규칙 baseline(공식 임계값) 참고 정확도: hr_norm_u/l 로 분류
    rule = np.ones_like(y)
    rule[X[:, 1] < 0.0] = 0   # hr_norm_l < 0 → below
    rule[X[:, 0] > 0.0] = 2   # hr_norm_u > 0 → above
    print(f"\n[참고] 공식 임계값 규칙 baseline 정확도: {(rule == y).mean():.3f}")

    # 샘플 세션 플롯 저장
    rng = np.random.default_rng(7)
    runner = make_runner(rng)
    sess = generate_session(runner, rng, 30)
    os.makedirs("ml/artifacts", exist_ok=True)
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    tmin = np.arange(len(sess["hr_obs"])) / 60.0
    fig, ax = plt.subplots(3, 1, figsize=(10, 7), sharex=True)
    ax[0].plot(tmin, sess["hr_obs"], lw=0.8)
    u_abs = runner["resting"] + runner["u_frac"] * runner["hrr"]
    l_abs = runner["resting"] + runner["l_frac"] * runner["hrr"]
    ax[0].axhline(u_abs, color="r", ls="--", lw=0.8, label="참 상한 U*")
    ax[0].axhline(l_abs, color="g", ls="--", lw=0.8, label="참 하한 L*")
    ax[0].set_ylabel("HR(bpm)"); ax[0].legend(fontsize=8)
    ax[1].plot(tmin, sess["pace"], lw=0.8, color="tab:orange"); ax[1].set_ylabel("pace(min/km)"); ax[1].invert_yaxis()
    ax[2].plot(tmin, sess["label"], lw=0.8, color="tab:purple"); ax[2].set_ylabel("label(0/1/2)"); ax[2].set_xlabel("time(min)")
    fig.tight_layout(); fig.savefig("ml/artifacts/sample_session.png", dpi=110)
    print("\n샘플 세션 플롯 저장: ml/artifacts/sample_session.png")
