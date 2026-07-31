# 아키텍처 개요 (최종 Architecture)

- **날짜**: 2026-07-01 (전면 재작성 2026-07-10 — 예측 드롭→관측 분석 엔진(adr-024/spec-025), AI≠NN 자체 NN 0개(adr-025), 강의 AI System 아키텍처 매핑 반영)
- **용도**: 전체 설계를 한눈에 보는 진입 문서. 세부는 각 ADR/Spec 참조
- **대상 기기**: Galaxy Watch (수집) + Galaxy S 폰 (판정/분석/코칭)
- **정본**: 요구/제약 = `spec/spec-001`, QA = `spec/spec-002`, 강의 프레임워크 = `framework/`

---

## 한 줄 요약

Galaxy Watch가 심박을 실시간 수집해 폰에 보내면, 폰이 **규칙으로 Zone 2 상태를 판정**하고(개인 경계 + 히스테리시스), **관측 데이터 분석 엔진**이 라이브 신호에서 파생지표(드리프트/경사보정 페이스/HRR/서브맥시멀 HR/케이던스 안정성)를 도출한다. 이 지표가 **반응형 코칭**(약한 LLM이 문장을 직생성하되 출력 가드 3종+단어 폴백으로 감쌈, adr-028)과 **세션 리포트**로 흐르고, 위험 심박은 **결정론 안전 가드**가 즉시 권고한다. 개인 경계는 온라인 Bayesian이 적응시킨다.

**핵심 원칙: AI ≠ NN, 문제마다 맞는 도구**(adr-025). 자체 학습 NN은 0개 — 개인화=Bayesian, 판정=규칙, 분석=통계/회귀, 표현=LLM. 우리 데이터(참값 부재/개인 소량)에 검증 가능한 도구만 남긴 정직한 결론(예측 NN은 검증 불가로 드롭, adr-024).

---

## 핵심 설계 결정 (Architectural Decision)

| DP | 결정 | 문서 |
|:---:|------|------|
| DP0 | Watch-Phone Hybrid (Watch=수집/표시, Phone=판정/분석/코칭) | `adr-001` |
| **DP1** | **관측 데이터 분석 엔진** = 라이브 신호에서 자기참조 파생지표 도출(예측 대체) | `adr-024`, `spec-025` |
| **DP2** | 개인 Zone 2 경계 = 온라인 Bayesian 적응 (말하기 테스트 실라벨 주도) | `adr-004`, `spec-004` |
| **DP3** | Zone 2 판정 = 규칙 (ZoneJudge — 지속 심박 vs 개인 경계 + 히스테리시스) | `adr-013` |
| **DP4** | 코칭 = 규칙/엔진이 방향·사실 확정 + LLM 직생성(간결 프롬프트) + 출력 가드 3종 + 단어 수준 폴백 | `adr-002`/`adr-028` |
| **DP5** | 안전 가드 = 위험 심박 결정론 규칙 권고 (LLM 우회) | `spec-008` |

- **DP1 분석 엔진**(spec-025): 지표별 작은 모듈(DriftSlope/GapMinetti/CadenceStability/SubmaxHr/Hrr)을 레지스트리에 등록해 조립(OCP). 판단선은 bpm 상수가 아니라 도출 통계량(slope 대 k·SE) + 개인 노이즈플로어(m+k·σ̂, 온라인 EWMA) — 마법상수 금지. 손목 PPG는 정속서만 정확해 노이즈/정속 게이팅.
- **DP2 개인화**는 NN이 아니라 경량 Bayesian(소수/온라인 라벨, float 산술). 주 관측 = 말하기 테스트(검증 가능 실라벨), 디커플링은 Conconi 편향으로 다운웨이트.
- **DP3 판정**은 규칙(결정론, 모순 불가). 표시 존은 순간심박+히스테리시스(5존), 코칭/통계 판정은 60초 지속심박(adr-023 이중 기준).
- **DP4 코칭**: 사실·방향은 규칙/분석 엔진이 확정하고, LLM(Gemini Nano)이 그 사실로 문장을 직접 생성한다(간결 구조화 프롬프트, adr-028). 출력 가드 3종 = 형식(길이/이모지) + DirectionGuard(방향 잠금) + NumberGuard(숫자 무결성 — 출력 숫자 ⊆ 입력 사실). 미가용/기각 시 단어 수준 폴백 큐로 무중단. 모든 호출은 프로비넌스로 기록(spec-027).
- **자체 NN 0개**(adr-025): "AI를 어디에 어떤 방식으로 쓰나"의 도구 선택이 설계 역량. 실제 학습 컴포넌트는 온라인 Bayesian.

---

## 강의 AI System 아키텍처 매핑 (framework/ai-system-and-quality.md §1-3)

우리 시스템을 강의의 표준 AI System 구성에 매핑한다(인증 필수 규칙).

| 강의 구성요소 | 우리 구현 |
|------|------|
| **입력 가드레일** | OutlierGuard(40~220 기각) + 정속/노이즈 게이팅(분석 엔진) + 히스테리시스 |
| **추론/분석 서비스(Analysis Service)** | **관측 데이터 분석 엔진**(AnalysisEngine — 서빙 로그(라이브 신호)→파생지표) + 규칙 판정(ZoneJudge) |
| **출력 가드레일** | 형식 가드 + DirectionGuard(방향 잠금) + NumberGuard(숫자 무결성) + 안전 가드(위험 심박 우선 권고) |
| **모델 준비/등록** | NanoModelManager — Nano 기능 상태 확인/다운로드(홈 배너+설정 카드, adr-027) |
| **프로비넌스(ML Metadata)** | LlmCallLog/LlmCallRecord — 코칭 문장마다 근거 관측+프롬프트+경로 기록, 세션에 영속(spec-027) |
| **설명 서비스(Explanation Service)** | SessionExplainer/PersonalizationExplainer — 세션 스토리/판정 왜/드리프트 인과(spec-023). 세션 종료 1회 생성·저장 |
| **HITL/HOTL 제어** | 말하기 테스트(사용자 개입으로 경계 교정), 설정(코칭 빈도/음성), 규칙이 LLM/개인화를 통제 |
| **적응(Retraining 루프)** | 온라인 Bayesian 경계 갱신 + 개인 노이즈플로어 EWMA + 프로필 값 갱신 — 세션 관측이 다음 세션 prior로(중립 prior→개인 수렴) |
| **저장(Registry 대응)** | LearnedZone(경계/플로어)/ProfileStore/SessionStore — 프로필별 네임스페이스 온디바이스 |

포인트: 강의 아키텍처의 "입출력 가드레일 + 설명 서비스 + HITL/HOTL + 적응 루프"가 우리 컴포넌트로 그대로 대응한다. **약한 온디바이스 LLM을 가드레일+폴백 구조로 감싼 것**이 제어가능성 QA의 실체다.

---

## 데이터 흐름 (Context / C&C View)

```
[Galaxy Watch]                      [Galaxy S 폰]
─────────────────                   ─────────────────────────────────────
Health Services                     RunSource 인터페이스 (Live / Sim / 수동 가상러너)
HR 1~2초 수집   ──Wearable──▶       │
                 Data Layer         ▼
                                    OutlierGuard 입력 가드레일 (40~220 bpm)        ← 강건성
                                    │  (+ 안전 가드: 위험 심박 → 규칙 권고, LLM 우회)  ← 제어가능성
                        ┌───────────┼───────────────────────┐
                        ▼           ▼                        ▼
             판정: ZoneJudge   관측 분석 엔진(DP1)      특징/개인화 관측
             (규칙, 히스테리)   드리프트/GAP/케이던스        (FeatureExtractor)
             → Zone2 상태      /서브맥시멀/HRR                  │
                        │      + 개인 k·σ 플로어               ▼
                        │           │              개인화 경계 (Bayesian)    ← 기능적응성
                        │           │              말하기 테스트 주도(약: 디커플링)
                        │           ▼                          │
                        │   반응형 코칭 트리거(드리프트↑)  ◀────┘
                        └───────────┬───────────────
                                    ▼
                        코칭 의도 = 규칙/엔진 확정(방향·사실)     ← 제어가능성
                                    ▼
                        On-device LLM 표현 + DirectionGuard 출력 가드 → 폴백 → TTS   ← 수행효율성
                                    │
                        세션 종료 → 분석 엔진 onSessionEnd → 리포트/설명 서비스       ← 설명용이성
```

- 판정(규칙)과 분석(엔진)은 별개 경로다. 판정은 규칙으로 즉시 결정, 분석 엔진은 그 옆에서 파생지표를 도출해 코칭/리포트에 공급.
- 개인 경계는 온라인 Bayesian이 말하기 테스트로 적응시켜 ZoneJudge에 공급한다.

---

## 모듈 구성 (Module View — 구현 반영, 2026-07-10)

```
zone2runner/
├── wear/      Galaxy Watch 앱 — RunService(포그라운드, 화면off 지속) + 대시보드     (spec-010, adr-009)
│              HR 송신(HrForwarder /hr) + 폰 확정 표시존 수신(/run/live — 워치=무로직 뷰어, adr-023)
├── app/       Galaxy S 폰 앱 — 판정/분석/개인화/코칭/UI                          (spec-029)
│   ├─ sensor/    RunSource 추상화 (Simulated / Live GPS+WatchHr) + SlopeEstimator (spec-003, QA 테스트가능성)
│   ├─ analysis/  ★관측 분석 엔진(spec-025) — AnalysisMetric/AnalysisEngine(레지스트리, OCP),
│   │             5지표(DriftSlope/GapMinetti/CadenceStability/SubmaxHr/Hrr), LinearRegression(OLS),
│   │             NoiseFloor(개인 k·σ EWMA), SignalWindow/SignalBuffer, AnalysisConfig(種類C 상수)
│   ├─ pipeline/  OutlierGuard + FeatureExtractor + ZoneJudge(규칙 판정) + SafetyGuard(안전 가드)
│   │             + Personalization(Bayesian) + RunEngine(오케스트레이터)          (adr-013/004/024, spec-004/008/025)
│   ├─ coaching/  의도 결정(규칙 RuleCoach) + LLM 표현(LlmCoach, Gemini Nano) + 방향 잠금 가드(DirectionGuard)
│   │             + 반응형 드리프트 코칭 + 설명 서비스(SessionExplainer)           (adr-002/007, spec-005/023)
│   ├─ data/      세션 JSON 영속화 + 프로필 저장 + 필드 로그 + 학습 상태(LearnedZone: 경계/드리프트 플로어) (spec-007/009/012/013)
│   ├─ domain/    공유 모델 + Zone2Prior(factor→prior 순수 함수) + DisplayZoneJudge + VirtualRunner (adr-012/023)
│   ├─ sim/       물리 러닝 시뮬레이터 + SimRunnerSource + 수동 가상러너 시뮬       (spec-022)
│   └─ ui/ + 화면 6개 (Home/Run/Report/History/Profile/Settings)                   (지도 osmdroid, adr-010)
├── ml/        오프라인 검증 하네스(Python) — 시뮬레이터 + prior/개인화 실험 + 필드 로그 분석 (adr-004/012)
├── sensor-poc/  실기기 HR/GPS 수집 검증 (adr-008/009)
└── llm-verify/  Gemini Nano 실기기 검증 (adr-007)
```

- **자체 NN 0개**: 구 NN(판정 MLP/역치 NN/심박 예측)과 예측 ODE(HrOdeModel)는 모두 제거됨(adr-013/016→025, adr-024, git 이력 보존).
- 핵심 원칙: 파이프라인은 `RunSource`(Sample 1Hz)에만 의존(DIP) → Watch 없이 시뮬/수동 가상러너로 전체 검증(테스트가능성). 지표는 작은 모듈 조립(OCP) — 지표 추가가 레지스트리 등록만으로 된다.

---

## 품질 속성 매핑 (spec-002 6 QA)

| QA | 달성 지점 | 검증 현황 |
|:---:|------|------|
| QA1 설명용이성 | 규칙 판정 근거(경계 비교) + 분석 엔진 파생지표 인과(드리프트↑→코칭) + 설명 서비스(spec-023) | 근거 제공 경로 구현. LLM은 표현만(무결성) |
| QA2 기능적응성 | factor prior(adr-012)→온라인 Bayesian 경계(adr-004)→말하기 테스트 적응 + 개인 노이즈플로어 EWMA | 메커니즘 실증(수렴 오차 5→2bpm). 실측 임계추출 편향은 한계로 명시 |
| QA3 제어가능성 | DirectionGuard+NumberGuard 출력 가드레일 + 안전 가드(spec-008) + Nano 미가용 시 단어 폴백 + 개인화 clamp + 감사 기록(spec-027) | 방향/숫자 위반 기각, 안전 권고, 폴백 무중단, 기각·폴백 사유 세션 기록 |
| QA4 강건성 | OutlierGuard 입력 가드레일(40~220) + 히스테리시스 + 분석 엔진 노이즈/정속 게이팅 | 이상치 기각 1.0 + 게이트아웃 |
| QA5 테스트가능성 | RunSource 추상화 + VirtualRunner 폐루프 + 수동 가상러너(spec-022) + 단위+통합 테스트 146건 | Watch 없이 전 파이프라인 실행 + 폐루프 통합검증 |
| QA6 수행효율성 | 규칙 판정 즉시(1Hz 무정지) + 코칭 비동기 분리(무중단) + 반응형(선제 예측 없음) | 판정 1Hz 무정지, LLM 비동기. 실기기 야외 재확인은 필드 |

> QA 정의/시나리오/측정 정본 = `spec/spec-002`. AI 특화 4개(설명/적응/제어/강건) + 일반 SW 2개(테스트/효율).

---

## 개발 단계 (Deployment / 구현 순서)

1. **1단계 (PoC)** — 완료: 시뮬 HR → 규칙 판정 → 코칭 전 파이프라인.
2. **2단계 (실기기 연동)** — 대부분 검증: 폰 Gemini Nano 코칭+TTS, 워치 포그라운드 서비스/표시존 수신(adr-023). 잔여 = 착용 HR 스트림+야외 GPS 필드 테스트.
3. **3단계 (분석 엔진 전환)** — 완료(2026-07-10): 예측 제거 + 관측 분석 엔진 완전 구현(spec-025, 이후 누적 테스트 146건, APK 빌드). 잔여 = 실주행 검증(자기참조/실라벨은 검증 가능, 실인간 정확도는 미측정).

---

## 관련 문서

- 요구/품질: `spec/spec-001`(FR/제약), `spec/spec-002`(QA). 강의 프레임워크: `framework/`
- 설계 결정: `adr-001`(DP0), `adr-002`(LLM 코칭), `adr-004`(Bayesian 경계), `adr-007`(LLM 검증), `adr-008`(HR 수집), `adr-009`(백그라운드), `adr-010`(지도), `adr-012`(콜드스타트 prior), **`adr-013`(판정=규칙)**, **`adr-023`(워치 무로직 뷰어)**, **`adr-024`(예측 드롭→분석 엔진)**, **`adr-025`(AI≠NN, NN 0개)**, `adr-017`(HRV 보류), `adr-026`(Nano 과제형 API — Rewriting은 adr-028로 폐지), **`adr-027`(모델 다운로드)**, **`adr-028`(LLM 직생성+단어 폴백)**
- 상세 명세: `spec-003`(HR 파이프라인), `spec-004`(Bayesian 경계), `spec-005`(LLM 코칭), `spec-007`(리포트), `spec-008`(안전 가드), `spec-009`(프로필), `spec-010`(워치), `spec-029`(폰 화면/플로우, 구 spec-011 대체), `spec-012`(필드 로그), `spec-013`(prior), `spec-016`(말하기 테스트), `spec-019`(가상러너), `spec-022`(수동 시뮬), `spec-023`(설명 서비스), **`spec-025`(관측 분석 엔진)**, `spec-026`(관절 보호), **`spec-027`(프로비넌스/텔레메트리)**, **`spec-028`(LLM 우선 표현)**
- 폐기/대체 전체 목록: `arch/archive/README.md`, `spec/archive/README.md`
