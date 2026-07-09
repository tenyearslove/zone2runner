"""
DP1 다중 시간대 추이 심박 예측 — 프로토타입 (설계 검증용)

목적: arch/hr-prediction-쉬운설명.md / hr-prediction-multiscale-trend-design.md 의 알고리즘이
      실제로 도는지, 각 조각(페이스 전이 가중 / 개인 반응 보정층)이 값을 하는지 눈으로 보는 것.

구조:
  1. 합성 러너: 속도 프로파일(워밍업→정속→감속→정속+드리프트)을 만들고,
     "진짜 심박"을 1차 지연(τ) + 카디악 드리프트 + 센서 노이즈로 생성한다.
     (예측기는 τ도, 속도→심박 매핑도 모른다 → 공정한 시험.)
  2. 예측기(우리 알고리즘): 3창 OLS 기울기+R², 페이스 전이 가중 융합, LMS 보정층(+게이트).
  3. 채점: persistence / 균등평균 / 페이스가중 뼈대 / 풀(보정층) 을 비교.
     전이구간/정속구간/드리프트에서 각각 MAE.

주의: 여기 합성 "진짜 심박"은 시험용 지상진실일 뿐 제품 일부가 아니다. 실인간 검증은 별도(spec-012).
값 3분류: 창크기/지평/μ/게이트/floor = C(설계 선택, 근거 있는 출발점). 그 외 라이브 계산=A, 반응배율 g=B.
"""

import sys
import numpy as np

try:
    sys.stdout.reconfigure(encoding="utf-8")   # Windows 콘솔 cp949 → UTF-8
except Exception:
    pass

# ---------------- 설계 선택 상수 (C) — 실데이터로 튜닝할 출발점 ----------------
DT = 1.0                 # 스트림 간격(초). HR 1Hz.
HORIZON = 30             # 예측 지평(초). 리드타임.
WIN = {"short": 20, "med": 120, "long": 480}   # 창 크기(초). §2 생리 위상 출발값.
MIN_PTS = 5              # 창에서 기울기 낼 최소 표본 수.
PACE_FLOOR = 0.10        # 페이스 "활발" 판정 바닥(m/s). 이하면 페이스 평평 → 장기 눈에 위임.
MU = 0.02               # 보정 배율 학습률(정규화 LMS, 0<μ<2 안정).
LMS_EPS = 1.0           # 0 나눗셈 방지.
G_CLAMP = (0.3, 3.0)    # 반응 배율 상식 범위.
GATE_ALPHA = 0.97       # 게이트 오차 EWMA 계수(느린 비교).
HR_CLAMP = (40.0, 210.0)  # 출력 가드레일(생리 가능범위).


# ======================= 1. 합성 러너 (지상진실) =======================
def make_run(seconds=1400, seed=7):
    rng = np.random.default_rng(seed)
    n = int(seconds / DT)
    t = np.arange(n) * DT

    # --- 속도 프로파일(m/s) ---
    # ★ 핵심: 감속을 "심박이 아직 오르는 중"에 일으킨다 → 중기/장기 추세는 stale하게 상승세인데
    #    단기만 꺾이는 상황(사용자가 지적한 바로 그 케이스). 이래야 페이스 가중의 진가가 보인다.
    speed = np.empty(n)
    for i in range(n):
        s = t[i]
        if s < 120:            speed[i] = 1.6 + (2.9 - 1.6) * (s / 120)   # 워밍업 램프(심박 급상승)
        elif s < 150:          speed[i] = 2.9                             # 30초만 유지(심박 아직 상승 중)
        elif s < 170:          speed[i] = 2.9 - (2.9 - 2.1) * ((s-150)/20) # 감속 — 심박 상승 중에!
        elif s < 600:          speed[i] = 2.1                             # 정속(느림)
        elif s < 620:          speed[i] = 2.1 + (2.6 - 2.1) * ((s-600)/20) # 재가속
        else:                  speed[i] = 2.6                             # 장시간 정속 → 드리프트 무대
    speed += rng.normal(0, 0.03, n)   # GPS 속도 미세 노이즈

    # --- 진짜 심박: 1차 지연 + 드리프트 + 센서 노이즈 (예측기는 이 식을 모름) ---
    resting, max_hr = 55.0, 190.0
    tau = 25.0                                  # 심박 지연 시상수(초)
    hr_target = lambda v: resting + (150.0 - resting) * np.clip(v / 3.0, 0, 1.4)  # 속도→목표심박
    hr_true = np.empty(n)
    hr = 70.0
    drift = 0.0
    for i in range(n):
        tgt = hr_target(speed[i])
        hr += (tgt - hr) / tau * DT             # 1차 지연으로 목표에 접근
        # 카디악 드리프트: 5분 이후, 뛰는 중이면 서서히 누적(워크로드 불변인데 상승)
        if t[i] > 300 and speed[i] > 1.5:
            drift += 0.010 * DT                 # 초당 +0.01 bpm 누적
        hr_true[i] = hr + drift

    # 관측 심박 = 진짜 + 센서 노이즈 + 가끔 이상치 스파이크
    hr_obs = hr_true + rng.normal(0, 1.2, n)
    spike_idx = rng.choice(n, size=max(1, n // 300), replace=False)
    hr_obs[spike_idx] += rng.choice([-1, 1], size=len(spike_idx)) * rng.uniform(18, 35, len(spike_idx))
    return t, speed, hr_true, hr_obs


# ======================= 유틸: OLS 기울기 + R² =======================
def ols_slope_r2(x, y):
    n = len(x)
    if n < MIN_PTS:
        return None, 0.0
    x = x - x.mean()
    sxx = (x * x).sum()
    if sxx <= 1e-9:
        return None, 0.0
    b = (x * (y - y.mean())).sum() / sxx        # 기울기
    yhat = y.mean() + b * x
    ss_res = ((y - yhat) ** 2).sum()
    ss_tot = ((y - y.mean()) ** 2).sum()
    r2 = 0.0 if ss_tot <= 1e-9 else max(0.0, 1 - ss_res / ss_tot)
    return b, r2


# 입력 가드레일: 이상치 완화(간이 Hampel — 중앙값에서 MAD*k 벗어나면 클립)
def outlier_guard(window_vals):
    if len(window_vals) < 5:
        return window_vals
    med = np.median(window_vals)
    mad = np.median(np.abs(window_vals - med)) + 1e-6
    hi, lo = med + 5 * mad, med - 5 * mad
    return np.clip(window_vals, lo, hi)


# ======================= 2. 예측기 (우리 알고리즘) =======================
class MultiscalePredictor:
    def __init__(self):
        self.g_up = 1.0
        self.g_down = 1.0
        # 게이트: 보정 적용 오차 vs 뼈대단독 오차의 EWMA
        self.err_corr = None
        self.err_skel = None
        self.gate_on = False

    def _windows(self, t_hist, hr_hist, v_hist, now_t):
        """각 창의 (기울기 b, 신뢰도 R², 평균속도)."""
        out = {}
        for name, w in WIN.items():
            mask = t_hist >= (now_t - w)
            xs, ys, vs = t_hist[mask], hr_hist[mask], v_hist[mask]
            if len(xs) < MIN_PTS:
                out[name] = None
                continue
            ys = outlier_guard(ys)
            b, r2 = ols_slope_r2(xs, ys)
            out[name] = None if b is None else dict(b=b, r2=r2, v=vs.mean())
        return out

    def predict(self, t_hist, hr_hist, v_hist, now_t, hr_now, session_v):
        """뼈대 기울기(융합), 뼈대 예측변화, 각 baseline 예측을 반환."""
        w = self._windows(t_hist, hr_hist, v_hist, now_t)
        ready = {k: v for k, v in w.items() if v is not None}
        if not ready:
            # 아무 창도 준비 안 됨 → persistence
            return dict(fused_b=0.0, skel_change=0.0, equal_change=0.0, ready=ready, weights={})

        # --- 페이스 활동량 (창 평균 속도 차이, 전부 속도 단위) ---
        v_short = w["short"]["v"] if w["short"] else session_v
        v_med = w["med"]["v"] if w["med"] else v_short
        v_long = w["long"]["v"] if w["long"] else v_med
        act = {
            "short": abs(v_short - v_med),
            "med": abs(v_med - v_long),
            "long": abs(v_long - session_v),
        }
        total_act = sum(act[k] for k in ready)

        # --- 융합 가중 ---
        if total_act < PACE_FLOOR:
            # 페이스 평평 → 장기 눈에 위임(장기 기울기 0이면 persistence, >0이면 드리프트)
            weights = {k: 0.0 for k in ready}
            weights["long" if "long" in ready else max(ready, key=lambda k: WIN[k])] = 1.0
        else:
            raw = {k: act[k] * ready[k]["r2"] for k in ready}
            s = sum(raw.values())
            weights = ({k: raw[k] / s for k in ready} if s > 1e-9
                       else {k: 1.0 / len(ready) for k in ready})

        fused_b = sum(weights[k] * ready[k]["b"] for k in ready)
        equal_b = sum(ready[k]["b"] for k in ready) / len(ready)   # 균등평균 baseline

        return dict(
            fused_b=fused_b,
            skel_change=fused_b * HORIZON,
            equal_change=equal_b * HORIZON,
            ready=ready, weights=weights,
        )

    def corrected_change(self, skel_change):
        g = self.g_up if skel_change > 0 else self.g_down
        return (g * skel_change) if self.gate_on else skel_change

    def learn(self, skel_change, actual_change):
        """라벨 성숙 시 호출: 반응 배율 LMS 갱신 + 게이트 갱신."""
        # 게이트용: 보정 적용/미적용 오차 비교
        g = self.g_up if skel_change > 0 else self.g_down
        e_corr = actual_change - g * skel_change
        e_skel = actual_change - skel_change
        self.err_corr = abs(e_corr) if self.err_corr is None else GATE_ALPHA * self.err_corr + (1 - GATE_ALPHA) * abs(e_corr)
        self.err_skel = abs(e_skel) if self.err_skel is None else GATE_ALPHA * self.err_skel + (1 - GATE_ALPHA) * abs(e_skel)
        # 보정이 뼈대보다 나을 때만 켠다(약간의 여유로 깜빡임 방지)
        self.gate_on = self.err_corr < self.err_skel * 0.98

        # 정규화 LMS로 활성 배율 갱신
        denom = skel_change ** 2 + LMS_EPS
        step = MU * e_corr * skel_change / denom
        if skel_change > 0:
            self.g_up = float(np.clip(self.g_up + step, *G_CLAMP))
        else:
            self.g_down = float(np.clip(self.g_down + step, *G_CLAMP))


# ======================= 3. 시뮬레이션 루프 + 채점 =======================
def run_sim(seed=7):
    t, speed, hr_true, hr_obs = make_run(seed=seed)
    n = len(t)
    pred = MultiscalePredictor()

    # 대기 중인 예측: t_pred -> (hr_now, skel_change, equal_change)
    pending = {}
    rows = []  # (t, actual_change, persist_change=0, equal_change, skel_change, full_change, gate_on)
    g_trace = []

    for i in range(n):
        now_t = t[i]
        hr_now = float(np.clip(hr_obs[i], *HR_CLAMP))
        # 라벨 성숙: HORIZON 초 전 예측을 지금 채점
        mature_t = round(now_t - HORIZON, 3)
        if mature_t in pending:
            hr_then, skel_change, equal_change = pending.pop(mature_t)
            actual_change = hr_now - hr_then
            full_change = pred.corrected_change(skel_change)   # 게이트 반영 출력
            rows.append((mature_t, actual_change, 0.0, equal_change, skel_change, full_change, pred.gate_on))
            pred.learn(skel_change, actual_change)             # 그 다음 학습(같은 라벨로)
            g_trace.append((mature_t, pred.g_up, pred.g_down, pred.gate_on))

        # 예측 생성
        session_v = speed[:i + 1].mean()
        out = pred.predict(t[:i + 1], hr_obs[:i + 1], speed[:i + 1], now_t, hr_now, session_v)
        pending[round(now_t, 3)] = (hr_now, out["skel_change"], out["equal_change"])

    return t, speed, hr_true, hr_obs, np.array(rows, dtype=float), g_trace


def mae(err):
    return float(np.mean(np.abs(err))) if len(err) else float("nan")


def report(t, speed, hr_true, hr_obs, rows, g_trace):
    # rows columns: t, actual_change, persist_change, equal_change, skel_change, full_change, gate_on
    tt = rows[:, 0]
    actual = rows[:, 1]
    persist_err = actual - rows[:, 2]
    equal_err = actual - rows[:, 3]
    skel_err = actual - rows[:, 4]
    full_err = actual - rows[:, 5]

    # 구간 분류: 전이(속도 변화 큰 시점 부근) / 정속 / 드리프트(>660s 정속)
    def seg_mask(lo, hi):
        return (tt >= lo) & (tt < hi)
    trans = seg_mask(150, 210) | seg_mask(600, 650)     # 감속(상승 중)/재가속 근처
    drift = tt >= 700                                    # 장시간 정속(드리프트)
    steady = ~trans & ~drift & (tt >= 120)               # 그 외 정속

    print("=" * 74)
    print("DP1 다중 시간대 예측 프로토타입 — 결과")
    print("=" * 74)
    print(f"시나리오: 워밍업→[150s 감속(심박 상승 중!)]→정속(느림)→[600s 재가속]→장시간 정속+드리프트")
    print(f"총 {int(t[-1])}초, 채점된 예측 {len(rows)}개, 지평 {HORIZON}초\n")

    print(f"{'구간':<16}{'개수':>6}{'persist':>10}{'균등평균':>10}{'페이스뼈대':>12}{'+보정(풀)':>12}")
    print("-" * 74)
    for name, m in [("전체", np.ones(len(tt), bool)), ("전이(감속/가속)", trans),
                    ("정속", steady), ("드리프트", drift)]:
        if m.sum() == 0:
            continue
        print(f"{name:<16}{int(m.sum()):>6}{mae(persist_err[m]):>10.2f}"
              f"{mae(equal_err[m]):>10.2f}{mae(skel_err[m]):>12.2f}{mae(full_err[m]):>12.2f}")
    print("-" * 74)
    print("(숫자 = MAE, 30초 뒤 심박 예측 오차 bpm. 낮을수록 좋음)\n")

    # 반응 배율 수렴
    print("반응 배율(보정층) 수렴 — '이 사람은 추세의 몇 배로 움직이나':")
    for frac in [0.1, 0.3, 0.6, 1.0]:
        k = min(len(g_trace) - 1, int((len(g_trace) - 1) * frac))
        gt = g_trace[k]
        print(f"  t={gt[0]:>5.0f}s   g_up={gt[1]:.2f}  g_down={gt[2]:.2f}  게이트={'ON' if gt[3] else 'off'}")
    gate_on_ratio = np.mean(rows[:, 6])
    print(f"게이트 ON 비율(보정이 뼈대보다 나아 실제 적용된 비율): {gate_on_ratio*100:.0f}%\n")

    # ASCII 스파크라인: 실제 vs 풀예측 (30초 뒤 예측을 예측시점+30에 정렬해 실제와 겹쳐봄)
    ascii_overlay(tt, rows, hr_obs, t)

    # CSV 저장
    import csv, os
    outp = os.path.join(os.path.dirname(__file__), "artifacts", "hr_multiscale_prototype.csv")
    os.makedirs(os.path.dirname(outp), exist_ok=True)
    with open(outp, "w", newline="") as f:
        wcsv = csv.writer(f)
        wcsv.writerow(["t_pred", "hr_now", "actual_hr_t+H", "persist_pred", "equal_pred", "skel_pred", "full_pred", "gate_on"])
        for r in rows:
            tp = r[0]
            hr_now = float(hr_obs[int(round(tp / DT))])
            wcsv.writerow([tp, round(hr_now, 1), round(hr_now + r[1], 1),
                           round(hr_now, 1), round(hr_now + r[3], 1),
                           round(hr_now + r[4], 1), round(hr_now + r[5], 1), int(r[6])])
    print(f"CSV 저장: {outp}  (엑셀/그래프로 열어보실 수 있어요)")

    plot_png(t, speed, hr_obs, rows, g_trace)


def ascii_overlay(tt, rows, hr_obs, t):
    """실제 심박(●) vs 풀예측(×)을 시간축으로 겹쳐 그린다. 예측은 30초 뒤 값이라 그 시점에 배치."""
    print("실제 심박(●) vs 우리 예측(×) — 시간축 (30초 앞을 미리 맞힌 것):")
    # 예측시점 tp의 풀예측 = hr_now + full_change, 이를 tp+HORIZON 시점에 배치
    ptime = tt + HORIZON
    pval = np.array([hr_obs[int(round(tp / DT))] for tp in tt]) + rows[:, 5]
    # 실제(관측)와 예측을 20초 버킷으로 다운샘플
    lo, hi = 130, int(t[-1])
    buckets = np.arange(lo, hi, 20)
    hrmin, hrmax = 95, 165
    W = 50
    def barpos(v):
        return int(np.clip((v - hrmin) / (hrmax - hrmin) * (W - 1), 0, W - 1))
    print(f"   {'t(s)':>5} | HR {hrmin}{'-'*(W-8)}{hrmax}")
    for b in buckets:
        # 해당 버킷의 실제 관측 평균, 예측 평균
        am = (t >= b) & (t < b + 20)
        a = np.mean(hr_obs[am]) if am.sum() else np.nan
        pm = (ptime >= b) & (ptime < b + 20)
        p = np.mean(pval[pm]) if pm.sum() else np.nan
        line = [" "] * W
        if not np.isnan(a):
            line[barpos(a)] = "●"
        if not np.isnan(p):
            pp = barpos(p)
            line[pp] = "×" if line[pp] == " " else "◉"   # 겹치면 ◉
        tag = ""
        if 150 <= b < 210: tag = " ← 감속(상승 중!)"
        elif 600 <= b < 650: tag = " ← 재가속"
        elif b >= 700: tag = " (드리프트)"
        print(f"   {b:>5} | {''.join(line)}{tag}")
    print("   (●와 ×가 가까울수록 잘 맞은 것. ◉ = 거의 겹침)\n")


def plot_png(t, speed, hr_obs, rows, g_trace):
    import os
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    tt = rows[:, 0]
    ptime = tt + HORIZON
    hr_now_arr = np.array([hr_obs[int(round(tp / DT))] for tp in tt])
    full_pred = hr_now_arr + rows[:, 5]
    persist_pred = hr_now_arr + rows[:, 2]
    equal_pred = hr_now_arr + rows[:, 3]
    gt = np.array(g_trace, dtype=float)

    fig, ax = plt.subplots(3, 1, figsize=(12, 10), sharex=True,
                           gridspec_kw={"height_ratios": [3, 1.3, 1.3]})

    # (1) 실제 vs 예측들
    ax[0].plot(t, hr_obs, color="0.55", lw=0.8, label="실제 심박(관측)")
    ax[0].plot(ptime, full_pred, color="#d62728", lw=1.6, label="우리 예측(페이스가중+보정)")
    ax[0].plot(ptime, equal_pred, color="#1f77b4", lw=1.0, ls="--", alpha=0.7, label="균등평균(비교)")
    ax[0].plot(ptime, persist_pred, color="#2ca02c", lw=1.0, ls=":", alpha=0.7, label="persistence(비교)")
    for x0, x1, lbl in [(150, 170, "감속"), (600, 620, "재가속")]:
        ax[0].axvspan(x0, x1, color="orange", alpha=0.15)
    ax[0].axvspan(700, t[-1], color="purple", alpha=0.06)
    ax[0].set_ylabel("심박(bpm)")
    ax[0].set_title("30초 앞 심박 예측 — 예측선을 도착 시점(t+30)에 정렬")
    ax[0].legend(loc="lower right", fontsize=8)
    ax[0].grid(alpha=0.3)

    # (2) 속도(전이가 어디서 일어났나)
    ax[1].plot(t, speed, color="#111", lw=1.2)
    ax[1].set_ylabel("속도(m/s)")
    ax[1].grid(alpha=0.3)

    # (3) 반응 배율 학습 + 게이트
    ax[2].plot(gt[:, 0], gt[:, 1], color="#d62728", label="g_up(오를 때 배율)")
    ax[2].plot(gt[:, 0], gt[:, 2], color="#1f77b4", label="g_down(내릴 때 배율)")
    ax[2].axhline(1.0, color="0.6", ls=":", lw=0.8)
    ax[2].fill_between(gt[:, 0], 0.3, 3.0, where=gt[:, 3] > 0.5, color="green", alpha=0.06,
                       label="게이트 ON(보정 적용)")
    ax[2].set_ylabel("반응 배율 g")
    ax[2].set_xlabel("시간(초)")
    ax[2].set_ylim(0.3, 1.6)
    ax[2].legend(loc="upper right", fontsize=8)
    ax[2].grid(alpha=0.3)

    try:
        plt.rcParams["font.family"] = ["Malgun Gothic", "DejaVu Sans"]
        plt.rcParams["axes.unicode_minus"] = False
    except Exception:
        pass
    fig.tight_layout()
    outp = os.path.join(os.path.dirname(__file__), "artifacts", "hr_multiscale_prototype.png")
    fig.savefig(outp, dpi=110)
    print(f"그래프 저장: {outp}")


if __name__ == "__main__":
    # 한글 폰트(있으면)
    try:
        import matplotlib
        matplotlib.rcParams["font.family"] = ["Malgun Gothic", "DejaVu Sans"]
        matplotlib.rcParams["axes.unicode_minus"] = False
    except Exception:
        pass
    report(*run_sim(seed=7))
