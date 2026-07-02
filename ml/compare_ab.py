"""
접근 A(분류) vs B(HR 회귀+잔차) 공정 비교 — 개인 임계 변동(u_frac_sigma) 스윕.

이전 비교는 시뮬레이터의 개인 임계 변동이 작아(σ=0.045, 공식이 이미 ~4bpm) B에 불리했다.
변동을 키우며(개인화 헤드룸 확대) 두 방식이 어떻게 달라지는지 본다. (adr-006, COMPARISON.md)
"""
import numpy as np
from train_mlp import evaluate_A
from hr_regressor import evaluate_B

SIGMAS = [0.045, 0.09, 0.14]  # 개인 임계 변동폭(작음/보통/큼)


def main():
    print(f"{'σ(임계변동)':>10} | {'A 코칭방향':>9} | {'B 코칭방향':>9} | "
          f"{'B HR MAE(pop→pers)':>18} | {'B 임계오차 공식/decoup':>20}")
    print("-" * 86)
    rows = []
    for s in SIGMAS:
        A = evaluate_A(u_frac_sigma=s)
        B = evaluate_B(u_frac_sigma=s, verbose=False)
        rows.append((s, A, B))
        print(f"{s:>10.3f} | {A['coaching_direction_accuracy_A']:>9.3f} | "
              f"{B['coaching_direction_accuracy_B_decoupling']:>9.3f} | "
              f"{B['hr_mae_population_bpm']:>7.1f}→{B['hr_mae_personalized_bpm']:<9.1f} | "
              f"{B['zone2_upper_err_formula_bpm']:>7.1f}/{B['zone2_upper_err_decoupling_bpm']:<11.1f}")

    print("\n[해석]")
    print("- σ↑ 이면 공식이 부정확해져(임계오차↑) 개인화 헤드룸이 커진다.")
    print("- A는 개인 경계 정규화로 방향 정확성을 유지하는지, B는 decoupling 임계 추정이")
    print("  공식을 이기기 시작하는지를 확인한다.")


if __name__ == "__main__":
    main()
