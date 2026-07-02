# ADR-006 (DP3 재검토): 개인화 접근 — 직접 분류 vs HR 회귀 + 잔차 보정

- **날짜**: 2026-07-02
- **상태**: Accepted (실증 비교 완료, `ml/COMPARISON.md`)
- **결정자**: 성시원
- **보고서 매핑**: 설계 - Architectural Decision **DP3 재검토**

---

## 맥락

DP4(adr-005)는 Zone2를 직접 3분류하는 MLP를, DP3(adr-004)는 개인 경계를 Bayesian으로 추정하는 방식을 택했다. 두 방식 모두 "Zone2 정답 라벨이 없다"를 물리 시뮬레이터로 우회한다는 약점이 있다.

대안 아이디어가 제기됐다(성시원): **여러 변수(나이/체중/키/날씨/페이스 등)로 예상 심박을 회귀 예측**하고, 공식으로 부트스트랩 학습한 뒤, **실제 심박과의 잔차로 지속 보정**하며 그 편차로 Zone2 범위를 갱신한다. 이 안의 타당성을 리서치로 검토하고 정식 대안으로 기록한다.

---

## 리서치 (established 방법)

- **개인화 HR 반응 모델** — 웨어러블 데이터(속도/고도/걸음)로 개인별 HR 반응을 예측하는 하이브리드(생리모델+ML) 모델이 이미 검증됨. 워크아웃당 중앙오차 약 6 BPM, 학습된 표현이 VO2max와 상관(0.81), 온도/습도 등 환경요인 반영(고온 시 HR +10%). → **제안 아이디어와 동일한 접근이 실재하며 유효.** (npj Digital Medicine 2023, Apple ML)
- **Conconi test / HR 편향점(1980)** — 점증 운동 중 HR-페이스가 선형이다 특정 지점에서 꺾인다(deflection). 그 지점이 무산소 임계 근처. → HR-페이스 관계에서 임계를 도출 가능. 단, 편향점이 불명확하거나 실제 임계보다 높게 나올 수 있음.
- **Aerobic decoupling (Pa:HR, Joe Friel/TrainingPeaks)** — 유산소 범위에선 페이스:HR가 평행 유지, 임계 초과 시 HR가 떠오르며 decoupling>5%. → 유산소 임계(=Zone2 상한 부근) 식별 신호. Zone2 ≈ 임계 HR의 80~89%.

**핵심 시사점**: 예상 HR 회귀의 정답(실측 심박)은 **측정 가능**하므로 실데이터로 학습·검증할 수 있다(직접 분류의 최대 약점 해소). 다만 "HR 예측"만으로는 임계 위치를 못 주므로 Zone2 경계 도출엔 decoupling/편향점 신호가 추가로 필요하다.

---

## 대안 비교

| 기준 | A. 직접 Zone 분류 (adr-005 MLP) | B. HR 회귀 + 잔차 개인화 (제안, 리서치 발전형) |
|------|------|------|
| 모델 출력 | Zone 상태(3분류) | 예상 심박(회귀) → 경계 도출 |
| 학습 정답(라벨) | 참 Zone (시뮬레이터 필요) | **실측 심박 (측정 가능, 실데이터 학습)** |
| 개인화 | Bayesian 경계 → 입력 정규화 | **예측-실측 잔차로 개인 HR곡선 보정** |
| 런타임 적응 | 신경망 고정 | **실측 심박이 라벨 → 온라인 보정 가능** |
| Zone2 경계 도출 | 분류기에 내재 | HR곡선 + decoupling/편향점으로 임계 산출 |
| 부가 가치 | 없음 | **피트니스 지표(VO2max 상관), HR 예측** |
| 해석가능성 | 낮음(블랙박스) | 높음(예상 HR/편차/경계가 보임) |
| 단점 | 실데이터 라벨 없음, 블랙박스 | 파이프라인 길고, 임계 도출에 별도 신호 필요, HR 잠재변수 노이즈 |

기존 adr-004의 Bayesian 경계 추정은 B의 "잔차 누적 스무딩"으로 자연스럽게 흡수된다(세션별 잔차를 Bayesian으로 안정화).

---

## 결정

실증 비교(`ml/COMPARISON.md`, 임계 변동 스윕 포함) 결과에 근거해 다음과 같이 정한다.

- **주 판정기 = A(다변량 분류).** 코칭 방향 정확성 0.996~0.999로 개인 변동에 견고. 이 프로젝트 목표(판정→코칭)에 적합.
- **B(개인화 HR 회귀)는 보조 모듈로 채택** — (i) 개인 경계 적응 강화(DP3), (ii) 피트니스 추적. B의 잔차 개인화는 실측 HR로 학습 가능하고 개인차가 클수록 값짐이 확인됨.
- **B의 데이터 기반 임계 추정(decoupling)은 미성숙**으로 확인 → 현 단계에서 완결적 판정기로는 채택하지 않음. 개선은 향후 과제.
- 성시원 제안의 "잔차 오프셋으로 Zone2 이동" 원안은 기각(HR 오프셋 ≠ 임계 위치, 실증 43bpm 오차). 단 그 핵심(개인화 HR 모델)은 위처럼 보조로 살린다.

즉 무게중심 A의 **하이브리드**. 새 정보(실데이터/개선된 임계 추정)가 나오면 재검토.

---

## 관련 문서
- ADR: `arch/adr-004` (DP3 Bayesian), `arch/adr-005` (DP4 분류기)
- Spec: `spec/spec-004` (개인화), `spec/spec-006` (분류 NN)
- 실증: `ml/hr_regressor.py`, `ml/COMPARISON.md`

## Sources
- [Modeling personalized heart rate response — npj Digital Medicine 2023](https://www.nature.com/articles/s41746-023-00926-4)
- [Personalizing Health and Fitness with Hybrid Modeling — Apple ML](https://machinelearning.apple.com/research/personalized-heartrate)
- [Conconi test — Wikipedia](https://en.wikipedia.org/wiki/Conconi_test)
- [Aerobic Decoupling (Pa:HR) — TrainingPeaks](https://help.trainingpeaks.com/hc/en-us/articles/204071724-Aerobic-Decoupling-Pw-Hr-and-Pa-HR-and-Efficiency-Factor-EF)
