# Spec-001: 기능 요구사항 및 제약사항

- **상태**: Approved (2026-07-08 갱신 — QA/제약/FR 확정 반영)
- **관련**: report-001(과제 개요), report-007(QA 척추), spec-002(품질속성), references/강의요약-AI-8대QA-상세.md

---

## 기능 요구사항 (Functional Requirements)

| ID | TITLE | Description | AI |
|:--:|:--|:--|:--:|
| FR1 | 개인 Zone 2 경계 설정 | 프로필(나이/안정심박/최대심박/신체 factor)로 개인 유산소 상한과 하한을 산정하고, 학습 데이터가 없으면 factor 기반 초기값을 제공한다. | |
| FR2 | 실시간 심박 수집 및 존 판정 | 갤럭시워치/센서에서 심박을 1초 주기로 수집하고, 규칙 기반으로 Zone 2 미달/유지/초과를 실시간 판정한다. | ● |
| FR3 | 심박 예측 및 선제 코칭 | 생리 모델(ODE)로 현재 페이스 유지 시의 앞선 심박을 예측해, 존을 벗어나기 전에 미리 코칭한다. | ● |
| FR4 | 개인화 학습 | 실주행 관측(말하기 테스트/드리프트)으로 개인 경계와 심박 반응 파라미터를 세션마다 자동 보정한다(점진적 개인화). | ● |
| FR5 | 실시간 코칭 제공 | 규칙이 코칭 방향과 사실을 확정하고, 온디바이스 LLM(Gemini Nano)이 이를 자연어(음성/텍스트)로 표현한다. | ● |
| FR6 | 세션 리포트 및 근거 설명 | 종료 후 경로/심박 추이/유산소 분석과 함께 "왜 이렇게 판정하고 코칭했는지"를 설명으로 제공한다. | ● |

- 규정 충족: FR 6개(최소 4개 이상), **AI 관련 FR = FR2/FR3/FR4/FR5/FR6**.

---

## 제약사항 (Constraints)

| ID | TITLE | Description |
|:--:|:--|:--|
| C01 | 운영 비용 0 (서버/추론 API 과금 불가) | 1인 프로젝트로 지속 비용 감당 불가 → 온디바이스 추론/LLM(Gemini Nano) 강제, 클라우드 배제. |
| C02 | 우리 도메인/사용자에 맞는 대량 학습 데이터를 만들 수 없음 | 개인 데이터는 세션 수십 개 수준. 공개 데이터셋(FitRec 25만 워크아웃 등)은 실존하나 이기종 기기/종목/인구가 혼재된 "모집단 평균"이라 개인 예측의 병목(개인차)을 풀지 못하고, 그 홀드아웃 정확도는 우리 사용자에 대한 보장이 아님(C03과 결합) → 블랙박스 NN 사전학습 대신 생리 ODE + 점진적 개인화(온라인 추정/Bayesian) 채택의 전제. (2026-07-08 정밀화 — "데이터가 없다"가 아니라 "있는 데이터가 우리 문제를 풀지 못한다") |
| C03 | 개인 참값(젖산/환기역치, Zone 2 상단) 측정 불가 | 소비자 기기로 랩 수준 참값 측정 불가 → 절대 정확도 대신 방향 정확성으로 품질을 목표한다. 근거: arch/zone2-physiology-and-estimation.md |
| C04 | 정량 평가는 폐루프 시뮬레이션으로 수행 | 실측 참값 부재(C03)로 QA 정량 평가를 가상러너 폐루프로 대신한다 → 소프트웨어/알고리즘 검증 범위이며, 실인간 정확도 주장이 아니다. |

- 온디바이스(전면)는 제약이 아니라 **C01이 강제한 설계 결정(DP0)**이다. (오프라인 동작/프라이버시는 그 부수효과.)

---

## 요구사항 → 설계/QA 추적

| 요구 | 관련 QA | 핵심 설계/구현 |
|:--:|:--|:--|
| FR1 초기 경계 | - | Zone2Prior(공식 + factor prior, adr-012/spec-013), ProfileStore |
| FR2 수집/판정 | 강건성, 수행효율성 | Health Services HR 수집(adr-008/009), ZoneJudge(규칙 판정, adr-013), OutlierGuard/히스테리시스 |
| FR3 예측/선제 | 설명용이성, 수행효율성 | HrOdeModel(생리 ODE, adr-020), 예측 항목분해(buildPredWhy) |
| FR4 개인화 | 기능적응성 | Personalization(온라인 Bayesian, adr-004/016), 토크테스트(spec-016), LearnedZone/LearnedDynamics 누적 |
| FR5 코칭 | 제어가능성 | RuleCoach/LlmCoach + DirectionGuard(출력 가드레일, adr-002), Nano 미가용 시 규칙 폴백 |
| FR6 리포트/설명 | 설명용이성 | ReportActivity, 설명 서비스(spec-023: 세션 스토리/판정 왜/드리프트 인과) |
| 전 파이프라인 검증 | 테스트가능성 | RunSource 추상화 + VirtualRunner 폐루프(spec-019/022) |

## 관련 문서
- 품질속성: `spec/spec-002-quality-attributes.md`
- QA 선정 근거(척추): `report/report-007-qa-selection-spine.md`
- 과제 개요: `report/report-001-project-overview.md`
