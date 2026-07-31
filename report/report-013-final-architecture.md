# Report-013: 최종 Architecture — 인증 보고서 "03. 설계" 마무리 파트

- **날짜**: 2026-08-01 (v1)
- **용도**: 인증 보고서 PPT 원고. 샘플 형식 "최종 Architecture (1/2), (2/2)" + Appendix 뷰 3종(Module/C&C/Deployment) + Use Case/Context.
- **작성 원칙**: 최종 구현 상태(as-built)를 강의 AI System 아키텍처 용어로 표현한다. DP1~DP5에 쓰인 그림이 모두 이 아키텍처의 단면임을 보인다.
- **도식 정본**: `arch/diagrams/` (전부 PlantUML 소스 + PNG 렌더 완비)

---

## 슬라이드 1 — 최종 Architecture (1/2): 거시 — 모듈 조립

**(그림: `arch/diagrams/06-final-architecture.png`)**

**읽는 법**: 시스템 전체를 강의 AI System 아키텍처의 표준 컴포넌트로 묶은 거시 뷰다. 블록의 색은 강의 표준 컴포넌트와의 대응을 나타내고, 각 블록은 다음 페이지의 모듈 상세 한 장씩에 대응한다.

- **AI Operation Subsystem** (실제로 돌아가며 사용자를 상대하는 부분)
  - **추론 서비스(모듈 A)**: 입력 가드레일과 특징 준비를 거친 신호를 규칙 존 판정, 베이지안 개인 경계, 통계 관측 분석이 받아 판단을 만들고, LLM이 문장으로 표현한 뒤 출력 가드레일 3종과 안전 확인을 통과한다. 오케스트레이터(RunEngine)가 매초 이 순서를 구동한다.
  - **설명 서비스(모듈 B)**: 판단에 실제로 쓴 사실로 세션 스토리/개인화 설명을 만들고, 모든 LLM 호출의 근거/프롬프트/경로를 기록한다(프로비넌스).
  - **분석 서비스(모듈 C)**: 세션 안(구간/경사/워밍업)과 세션 사이(비교/추세/기록)를 분석해 리포트 카드를 만든다.
  - **운영 서비스(모듈 D)**: 화면/음성 UI와 폰-워치 인프라, 그리고 사람의 개입 통로 — 루프 안(말하기 테스트)과 루프 위(설정/감사).
- **저장(모듈 E)**: 강의의 Data Lake(세션 이력) + Model Artifact(개인 파라미터) + Registry 대응(APK/AICore).
- **AI Development Subsystem**: 표준의 오프라인 대량 학습이 이 시스템에는 없다 — 대신 **개인 적응 갱신(F1, Retraining 대응)**이 운영 안에서 세션마다 돌고, **시뮬 검증 도구(F2, 모델 테스팅 대응)**가 참값 없는 조건의 검증을 맡으며, **모델 준비 관리자(A7-1, Model Registry 인접)**가 Nano 모델 상태/다운로드를 관리한다.

**강의 표준 용어 대응 표**:

| 강의 AI System 컴포넌트 | 이 시스템의 구현 | 모듈 |
|---|---|---|
| 입력 가드레일 | OutlierGuard(생리범위/신선도) + 분석 게이팅 | A2 |
| 모델 서빙 | 규칙 판정 + 베이지안 경계 + 관측 분석 엔진 + 온디바이스 LLM | A4~A7 |
| 출력 가드레일 | 형식 가드 + DirectionGuard + NumberGuard + SafetyGuard | A8 |
| 추론 제어 | RunEngine(오케스트레이터) | A9 |
| 설명 서비스 | SessionExplainer/PersonalizationExplainer + 프로비넌스 | B |
| 분석 서비스 | SessionAnalytics + SessionCompare/Trends + 서빙 로그 | C |
| 운영 서비스 + HITL/HOTL | 대시보드/리포트 UI + 말하기 테스트 + 설정/감사 | D |
| Data Lake / Model Artifact | SessionStore(이력) / LearnedZone(개인 파라미터) | E |
| Model/Container Registry | 개인 파라미터 + AICore(시스템 제공) + APK — 경량 | E4 |
| Model Construction / Retraining | 개인 적응 갱신(세션마다 베이지안 + EWMA) | F1 |
| 모델 테스팅 | 시뮬 검증 도구(참임계 가상 러너 폐루프) | F2 |

**이 아키텍처의 정체성 한 줄**: 표준 대비 운영 쪽이 두껍고 개발(오프라인 학습) 쪽이 얇다 — **학습이 운영 안의 개인 적응으로 녹아 있다.** (거대 NN 없이, 규칙/통계/베이지안 + 통제된 LLM 표현으로 AI 시스템의 표준 요소를 전부 갖춘다.)

---

## 슬라이드 2 — 최종 Architecture (2/2): 모듈별 상세

한 페이지에 담기지 않으므로 모듈별 4장으로 나눈다. 각 장은 거시 뷰의 블록 하나를 컴포넌트 수준으로 편다.

| 장 | 도식 | 내용 |
|---|---|---|
| A. 추론 서비스 | `arch/diagrams/06a-module-inference.png` | 입력에서 출력까지의 판정 경로 9컴포넌트: 신호 소스(A1) → 입력 가드레일(A2) → 특징 준비(A3) → 존 판정(A4)/개인 경계(A5)/관측 분석(A6) → 코칭 표현(A7) → 출력 가드레일(A8), 오케스트레이터(A9) |
| B+C. 설명+분석 | `arch/diagrams/06b-module-explain-analysis.png` | 설명 생성기(B1), 값 출처 규율(B2), LLM 프로비넌스(B2-1), 세션 내 분석(C1), 세션 간 비교/추세(C2), 서빙 로그(C3) |
| D+E. 운영+저장 | `arch/diagrams/06c-module-operation-storage.png` | 대시보드/리포트 UI(D1), 인프라/폰-워치(D2), HITL/HOTL(D3), 학습값(E1)/세션(E2)/프로필(E3) 저장, 레지스트리 대응(E4) |
| F. 적응+검증 | `arch/diagrams/06d-module-adaptation-verification.png` | 개인 적응 갱신(F1, Retraining), 시뮬 검증 도구(F2, 모델 테스팅), 모델 준비 관리자(A7-1) — 적응 루프와 검증 루프 |

**DP 도식과의 관계** — 각 DP는 이 한 아키텍처의 서로 다른 QA 단면이다. DP에서 쓴 채택안(1안) 그림이 곧 이 최종 아키텍처(C&C 축약 뷰)이고, 카운터(2안)만 DP 전용 가정 설계다:

| DP | QA | 이 아키텍처의 어느 단면인가 | DP 카운터(2안) 도식 |
|---|---|---|---|
| DP1 | 설명용이성 | 투명 판정(A4/A5) + 설명 서비스(B) + 프로비넌스(B2-1) — "설명이 실제 판단 근거" | 사전학습 블랙박스 + 사후 설명 |
| DP2 | 제어가능성 | 구조적 격리(A7) + 출력 가드 3종(A8) + 폴백 + HITL/HOTL(D3) + 감사(B2-1) | LLM 판단 위임 |
| DP3 | 기능적응성 | 개인 경계 추정(A5) + 적응 루프(F1/E1) + 말하기 테스트(D3) | 오프라인 재학습 개인화 |
| DP4 | 강건성 | 입력 가드레일(A2) + 이중 기준 판정(A4) + 통계 평탄화(A6) + 소스 폴백(A1) | 즉시 반응 판정 |
| DP5 | 테스트가능성 | 소스 추상화(A1) + 시뮬 검증(F2) + 순수 로직 분리(Module View) | 실기기 결합 검증 |

---

## Appendix용 뷰 3종 + Use Case/Context (도식 안내)

| Appendix 페이지 | 도식 | 요지 |
|---|---|---|
| Use Case | `arch/diagrams/05-usecase.png` | 러너의 사용 사례 10종 + 개발/검증자의 시뮬 실행. 러닝 중 말하기 테스트가 코칭 흐름에 포함(include)되고, 리포트에서 설명/근거 열람으로 확장(extend) |
| Context View | `arch/diagrams/01-context.png` | 시스템 경계: 외부 5종 = Health Services/GPS/Gemini Nano(AICore)/Open-Meteo/OSM 지도 타일 서버. LLM 왕복에 "가드 3종 통과 시 채택" 명시, 모델 다운로드 경로와 시뮬 입력(검증용) 포함 |
| Module View | `arch/diagrams/04-module-view.png` | 코드 패키지 의존: domain이 리프(의존 0), 결정 로직(pipeline/coaching/analysis)이 안드로이드 미의존 순수 모듈 → 단위 테스트 152개가 기기 없이 실행. sim은 sensor의 RunSource 인터페이스를 구현(DIP) |
| C&C View | `arch/diagrams/02-component-cnc.png`(상세) / `02b-component-cnc-simple.png`(축약) | 실행 시 컴포넌트와 커넥터 — DP1~DP5의 채택안 도식이 바로 이 뷰 |
| Deployment View | `arch/diagrams/03-deployment.png` | 워치(측정/표시 + 포그라운드 서비스) ↔ 폰(판정/분석/개인화/코칭 + 저장 5종, 전체 경로 7종) ↔ AICore(별도 프로세스, 계측 한계 명시) ↔ Open-Meteo(기온 1회) |

> 정합 메모: Context/Deployment는 2026-08-01 as-built로 현행화(가드 3종/모델 다운로드/Data Layer 경로/워치=표시 전담/저장 5종). Module View/Use Case/최종 아키텍처 5종은 신설.
