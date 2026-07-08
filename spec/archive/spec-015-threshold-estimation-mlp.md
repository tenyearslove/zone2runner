# Spec-015: 개인 젖산역치(Zone2 상단) 추정 MLP

- **상태**: Demoted by adr-016 (2026-07-04) — 개인화 경로에서 제거, 기록 보존
- **날짜**: 2026-07-04
- **관련 ADR**: `arch/adr-014-nn-threshold-estimation.md`

## 목표

러닝 세션에서 뽑은 특징으로 개인 젖산역치 ≈ Zone2 상단(HRR 비율)을 추정하는 경량 MLP를 설계/학습/
평가/온디바이스 배포한다. 문헌(Etxegarai 2018, Zhu 2025)이 보인 "다신호 -> 신경망 -> 무랩 역치 추정"
접근을 1인 과제 규모로 경량화한 것이다. 랩 참값이 없으므로 절대 정확도 대신 무랩 정합/수렴으로 검증한다.

## FR

- FR1 (추정): 세션 특징 벡터 -> 개인 Zone2 상단 uFrac(HRR 비율) 회귀.
- FR2 (개인 적응): NN 추정치를 관측으로 Bayesian(adr-004)에 투입해 세션 누적 수렴. NN=관측 산출기,
  Bayesian=시변 적응. (판정은 규칙, adr-013 — 역할 분리 유지)
- FR3 (배포): 가중치+StandardScaler를 JSON export -> 순수 Kotlin 순전파(adr-011). TFLite 불필요.
- FR4 (표시): 홈/러닝 화면의 "개인 Zone2 상한 추정" 값이 NN 추정 + Bayesian 누적 결과를 반영.

## 입력 특징 8종 (세션에서 관측 가능한 것만, 참 경계 미포함 -> 라벨 누수 없음)

| # | 이름 | 정의 |
|---|------|------|
| 0 | hr_frac_slow | 느린 1/3 구간 HR의 HRR 비율 평균 |
| 1 | hr_frac_mid | 중간 1/3 구간 HR의 HRR 비율 평균 |
| 2 | hr_frac_fast | 빠른 1/3 구간 HR의 HRR 비율 평균 |
| 3 | hr_speed_slope | HR(HRR비율) vs 속도(m/s) 선형회귀 기울기 (심박 반응성) |
| 4 | drift | 세션 후반/전반 HR/속도 비율 상승률(디커플링) |
| 5 | cadence_n | 평균 케이던스 / 200 |
| 6 | rhr_frac | 안정심박 / 최대심박 (체력 대리) |
| 7 | age_n | 나이 / 100 |

구간 분할은 속도 삼분위 기준. HR을 HRR로 정규화해 러너 간 전이 가능하게 한다. 근거: 심박-페이스
반응 곡선의 형태와 드리프트가 유산소 임계 위치와 연관(문헌 근거, adr-014).

## 출력

- uFrac (Zone2 상단, HRR 비율). 역치 심박(bpm) = restingHr + uFrac x HRR.

## 모델/학습

- MLP 8 -> 16 -> 8 -> 1 (ReLU, 회귀). PyTorch/sklearn 학습(MSE, early stopping).
- 데이터: `ml/simulator.py`가 러너마다 다른 참 uFrac로 세션 생성 -> (세션 특징, 참 uFrac). 러너 단위 분할.
- export: `ml/artifacts/threshold_mlp.json` + `app assets`.

## 평가 (AC)

| AC | 기준 |
|----|------|
| AC1 | export 모델을 Kotlin 순전파로 로드, sklearn 예측과 일치(허용오차 1e-4) — 단위 테스트 |
| AC2 | 참 uFrac 대비 추정 MAE가 공식(고정 0.70) 대비 낮음(bpm 환산) — 개발 지표 |
| AC3 | 앱: NN 추정 -> Bayesian 누적으로 "개인 Zone2 상한" 표시가 세션 반영 — 실기기 확인 |
| AC4 | 무랩 검증(문서): 토크테스트/DFA-α1 정합은 향후 필드 데이터로 확인(한계 명시) |

## 앱 통합

- `ThresholdEstimator`(순수 Kotlin MLP) — `assets/threshold_mlp.json` 로드, 세션 종료 시 특징 집계 -> uFrac.
- `Personalization`에 관측 투입: 세션 종료 시 NN uFrac을 관측으로 `update`(기존 Bayesian 경로 재사용).
- 표시: Home/Run의 uEst 표기가 누적 결과 반영. 판정 경로(규칙, ZoneJudge)는 변경 없음.

## 한계 (명시)

- 랩 참값 부재로 절대 정확도 검증 불가 — 무랩 서로게이트 정합/수렴으로 대체(adr-014, C04).
- 시뮬 학습 — 실데이터 fine-tune은 향후(필드 로그 spec-012). 논문 원형(RNN/전이) 대비 표현력 축소는 의도적.
