# 아키텍처 개요 (최종 Architecture)

- **날짜**: 2026-07-01 (최종 갱신 2026-07-08 — QA 체계 확정(spec-002) 및 모듈 현행화(adr-020/023, Mock 제거) 반영)
- **용도**: 전체 설계를 한눈에 보는 진입 문서. 세부는 각 ADR/Spec 참조
- **대상 기기**: Galaxy Watch 8 (수집) + Galaxy S26 Ultra (판정/코칭)

---

## 한 줄 요약

Galaxy Watch가 심박을 실시간 수집해 폰에 보내면, 폰이 규칙으로 Zone 2 상태를 판정하고(개인 경계 + 히스테리시스) On-device LLM으로 상황 맞춤 음성 코칭을 출력한다. 개인 경계는 온라인 Bayesian으로 적응하고, 생리 ODE(HrOdeModel, adr-020)가 심박 예측으로 선제 코칭/페이스 제안을 담당한다. 문제마다 맞는 도구를 고른다 (AI ≠ NN, adr-016).

---

## 핵심 설계 결정 (Architectural Decision)

핵심 관점은 **"AI ≠ NN, 문제마다 맞는 도구를 고른다"**(adr-016)이다. 판정은 규칙, 개인화는 Bayesian, 심박 예측은 생리 ODE(+개인 파라미터 온라인 추정, adr-020), 표현은 LLM이 맡는다.

| DP | 결정 | 문서 |
|:---:|------|------|
| DP0 | Watch-Phone Hybrid 구조 (Watch=수집, Phone=판정/코칭) | `arch/adr-001-watch-phone-architecture.md` |
| **DP1** | 심박 예측 = 생리 ODE + 개인 파라미터 온라인 추정 (HrOdeModel — 선제 코칭/페이스 제안) | `arch/adr-020`, `adr-016` (구 NN 명세 `spec/archive/spec-014`는 ODE로 대체) |
| **DP2** | 개인 Zone 2 범위 = 온라인 Bayesian 적응 (토크테스트/디커플링 라벨) | `arch/adr-004`, `adr-016`, `spec/spec-004` |
| **DP3** | Zone 2 판정 = 규칙 (ZoneJudge — 지속 심박 vs 개인 경계 + 히스테리시스) | `arch/adr-013`, `adr-016` |
| **DP4** | LLM 코칭 = 규칙이 방향 결정 + LLM이 표현 + 출력 가드 | `arch/adr-002-ondevice-llm-coaching.md` |

AI 역량: (1) **심박 예측 생리 ODE**(HrOdeModel — 모집단 prior + 개인 τ/드리프트 온라인 추정, 선제 코칭/페이스 제안), (2) **Bayesian 개인 Zone 2 경계 추정**, (3) **규칙 기반 실시간 판정**(ZoneJudge — 결정론/즉시), (4) **LLM 상황 코칭**. 판정과 표현을 분리해, 판정은 규칙으로 결정론/저지연을 보장하고 ODE/LLM은 그 위에서 예측과 표현을 담당한다.

- **DP3 판정**은 규칙(ZoneJudge). 지속 심박을 개인 경계와 비교하고 히스테리시스로 상태 떨림을 억제한다. 결정론이라 QA4 제어가능성(방향)/QA6 수행효율성(저지연)에 유리. **과거 다변량 MLP 판정기(adr-005/spec-006)는 시뮬레이터 라벨 순환 결함으로 Superseded**.
- **DP2 개인화**는 신경망이 아니라 경량 Bayesian 적응추정(소수/온라인 라벨, float 산술). 역치 추정 NN(adr-014)은 개인화 경로에서 제거(Demoted). 상세 `spec/spec-004`.
- **DP1 심박 예측**은 생리 ODE(HrOdeModel, adr-020) — mono-exponential 지연 + 드리프트. 시뮬-학습 MLP는 시뮬 ODE를 흉내내는 순환이라 폐기. 개인 파라미터(τ/드리프트/수요맵)만 실주행 온라인 추정. 상세 `adr-020`(구 NN 명세는 `spec/archive/spec-014`).
- **Zone 2 기준**은 %HRmax (상단 70% = LT1, 하단 ~60%)이다. %HRR 아님.

---

## 데이터 흐름 (Context / C&C View)

```
[Galaxy Watch 8]                    [Galaxy S26 Ultra]
─────────────────                   ─────────────────────────────────────
Health Services                     RunSource 인터페이스 (Live / Sim / 수동 가상러너)
HR 1~2초 수집   ──Wearable──▶       │
                 Data Layer         ▼
                                    OutlierGuard 이상값 가드 (규칙, 40~220 bpm)   ← QA3 강건성
                                    │
                        ┌───────────┴───────────────┐
                        ▼                            ▼
             판정: ZoneJudge (규칙)          특징 추출 (FeatureExtractor)
             지속 심박 vs 개인 경계                  │
             + 히스테리시스 (%HRmax)                 ▼
             → Zone2 상태 (결정론)          심박 예측 ODE (HrOdeModel)    ← DP1
                        │                   선제 코칭 / 목표 페이스 제안
                        │                            │
                        └───────────┬────────────────┘
                                    ▼
                        개인화 경계 (Bayesian)  ◀─┐    ← QA2 기능적응성 (경계 적응)
                        │  토크테스트/디커플링     │
                        │                          세션 누적 갱신
                        ▼
                        코칭 의도 결정 (규칙, 방향)      ← QA4 제어가능성 (방향)
                        │  + 맥락(고도/페이스/SPM/날씨)
                        ▼
                        On-device LLM 표현 생성
                        │
                        출력 가드 → TTS 음성            ← QA4 제어가능성/QA6 수행효율성
```

- 판정(ZoneJudge 규칙)과 예측(HrOdeModel 생리 ODE)은 별개 경로다. 판정은 규칙으로 즉시 결정하고, ODE는 그 옆에서 선제 코칭/페이스 제안을 만든다.
- 개인 경계는 온라인 Bayesian이 토크테스트/디커플링 신호로 적응시켜 ZoneJudge에 공급한다.

---

## 모듈 구성 (Module View — 구현 반영, 2026-07-08)

```
zone2runner/
├── wear/      Galaxy Watch 앱 — RunService(포그라운드, 화면off 지속) + 대시보드     (spec-010, adr-009)
│              HR 송신(HrForwarder /hr) + 폰 확정 표시존 수신(/run/live — 워치=무로직 뷰어, adr-023)
│              ※ Data Layer 라우팅 제약으로 applicationId는 폰과 동일(com.zone2runner.app)
├── app/       Galaxy S26 Ultra 앱 — 판정/개인화/코칭/UI                          (spec-011)
│   ├─ sensor/    RunSource 추상화 (Simulated / Live GPS+WatchHr)                 (spec-003 HrSource 사상, QA5 테스트가능성)
│   │             ※ 구 MockRunSource는 수동 가상러너 시뮬(spec-022)이 상위호환해 제거(7e6d172)
│   ├─ pipeline/  OutlierGuard + FeatureExtractor + ZoneJudge(규칙 판정)          (adr-013/016)
│   │             + HrOdeModel(심박 예측 생리 ODE) + Personalization(Bayesian) + RunEngine (adr-004/020, spec-004)
│   │             ※ 구 NN(Zone2Classifier 판정 MLP, ThresholdEstimator 역치 NN, HrDynamics 예측 MLP)은 제거됨(adr-013/016/020, git 이력 보존)
│   ├─ coaching/  의도 결정(규칙 RuleCoach) + LLM 표현(LlmCoach, Gemini Nano) + 방향 잠금 가드(DirectionGuard) + TTS (adr-002/007, spec-005)
│   ├─ data/      세션 JSON 영속화 + 프로필 저장 + 필드 로그(RunLogger) + 학습 상태(LearnedZone/LearnedDynamics) (spec-007/009/012/013)
│   │             ※ 구 존 동기화(ZoneSync /zones)는 워치 무로직 뷰어 전환으로 제거(adr-023)
│   ├─ domain/    공유 모델 + Zone2Prior(factor→prior 순수 함수) + DisplayZoneJudge(표시존 확정, adr-023) + VirtualRunner (adr-012, spec-013)
│   │             ※ VirtualRunner = 폐루프 시뮬용 가상 러너(코칭 반영해 심박/페이스 반응)
│   ├─ sim/       물리 러닝 시뮬레이터 + SimRunnerSource(폐루프 시뮬 소스)          (ml/simulator.py 포팅)
│   │             + 수동 가상러너 시뮬(ManualVirtualRunnerSource 등)               (spec-022)
│   └─ ui/ + 화면 6개 (Home/Run/Report/History/Profile/Settings)                   (지도 osmdroid, adr-010)
├── ml/        오프라인 검증 하네스(Python) — 시뮬레이터 + prior/개인화 실험         (adr-004/012)
│              + 필드 로그 분석. ※ 구 NN 학습 스크립트는 기록 보존(앱 미사용, adr-013/016/020)
├── sensor-poc/  실기기 HR/GPS 수집 검증 (백그라운드 서비스 포함, adr-008/009)
└── llm-verify/  Gemini Nano 실기기 검증 (adr-007)
```

핵심 원칙: 파이프라인은 `RunSource`(Sample 1Hz)에만 의존 → Watch 없이 시뮬/수동 가상러너(spec-022)로 전체 검증 (QA5 테스트가능성, 달성). 엔진/러너를 분리해 `SimRunnerSource` + `VirtualRunner`로 코칭이 심박에 되먹임되는 **폐루프 시뮬**까지 자동 검증한다.
계획 시점 구조(hr/zone2/session, shared/)는 구현에서 위 구조로 정착 — 공유 모델은 `app/domain/`으로 수렴(워치는 표시 전용이라 shared 모듈 불필요).

---

## 품질 속성 매핑 (달성 지점 + 검증 현황)

QA 번호/이름은 2026-07-08 확정 체계(spec-002) 기준이다. 구 체계의 QA1 기능정확성(코칭 방향)은 최종 QA에서 제외되었고, 그 실체(코칭 방향 무결성, DirectionGuard)는 QA4 제어가능성이 흡수한다. 검증 수치는 구 체계 시점(2026-07-05)의 사실 기록이다.

| QA | 달성 지점 | 검증 현황 |
|:---:|------|------|
| QA1 설명용이성 | 규칙 판정 근거(ZoneJudge 결정론, adr-013) + 생리 ODE 예측 항목분해(adr-020, buildPredWhy) + 설명 서비스(spec-023 — 세션 스토리/판정 왜/드리프트 인과) | 근거 제공 경로 구현(판정/예측/개인화 설명). 정량(근거 제공률/설명 재현율)은 spec-002 §4 지표로 측정 |
| QA2 기능적응성 | factor prior(adr-012) → 온라인 Bayesian 개인 경계 (adr-004) → 토크테스트(spec-016)/디커플링 신호로 적응 | **메커니즘 실증**: 수렴(오차 5→2bpm) + **콜드스타트 오차 -42%**(prior 실험). 실측 임계추출 편향은 한계로 명시 *(구 QA3 적응성 지표 — 현 체계에선 QA2 기능적응성 계열)* |
| QA3 강건성 | OutlierGuard 이상값 가드 40~220 (규칙, spec-003), 개인화 가드(세션 내 ±10bpm) (spec-013) | **달성**: 이상치 기각 1.0 + 관측 폭주 가드 테스트 *(구 QA2 강건성 지표 — 현 체계에선 QA3 강건성 계열)* |
| QA4 제어가능성 | 코칭 방향: 규칙 확정 + **방향 잠금 가드(DirectionGuard)** + LLM 표현 (adr-002/013), Nano 미가용 시 규칙 폴백, 개인화 clamp(세션 내 한계) | **달성(개발지표)**: 규칙 판정 결정론 재현 + 실기기 LLM 출력 가드 검증(무방향 문장 기각) *(구 QA1 기능정확성 지표 — 현 체계에선 QA4 제어가능성 소관)* |
| QA5 테스트가능성 | RunSource 추상화 (spec-003, spec-011) + **VirtualRunner 폐루프 시뮬(엔진/러너 분리, spec-019)** + 수동 가상러너 시뮬(spec-022) + 필드 로그(spec-012) | **달성**: 시뮬/폐루프/수동 러너로 Watch 없이 전 파이프라인 + 단위 테스트 + 로그 분석 파이프라인 |
| QA6 수행효율성 | 규칙 판정 즉시 결정(결정론, 1Hz 무정지) + 프롬프트 최소화 (adr-002) + 코칭 비동기 분리 | 실기기: LLM 생성 1.1~2.7초(중앙값 1.3초), 판정 1Hz 무정지 — 5초 내 충족. 야외 재확인은 필드 *(구 QA4 효율성 지표 — 현 체계에선 QA6 수행효율성 계열)* |

---

## 개발 단계 (Deployment / 구현 순서)

1. **1단계 (PoC, 얇게)** — **완료**: `app/` 시뮬 HR(당시 Mock 포함 — Mock은 이후 spec-022 수동 러너 시뮬로 대체, 7e6d172 제거) → Zone 2 판정 → 코칭. 전체 파이프라인 흐름과 QA 검증 골격 확보(단위 테스트 66건).
2. **2단계 (실기기 연동)** — **대부분 검증 완료(2026-07-03)**: 폰 — Gemini Nano 코칭 실동작+TTS, 시뮬 세션 e2e, 프로필 factor UI.
   워치 — 세션 시작/권한/포그라운드 서비스 화면off 지속(adr-009), 폰 확정 표시존(/run/live) 수신(무로직 뷰어, adr-023).
   **잔여: 착용 상태 HR 스트림(워치→폰) + 야외 GPS — 필드 테스트(FIELD_TEST.md)**. Samsung Health Sensor SDK 대신 Wear OS Health Services 채택(adr-008).

---

## 관련 문서

- 요구/품질: `spec/spec-001-requirements.md`, `spec/spec-002-quality-attributes.md`
- 설계 결정: `arch/adr-001`(DP0), `adr-002`(LLM 코칭), `adr-004`(개인 Bayesian 경계), `adr-006`(대안 비교), `adr-007`(LLM 검증), `adr-008`(HR 수집), `adr-009`(백그라운드), `adr-010`(지도), `adr-012`(콜드스타트 factor prior), **`adr-013`(판정 역할 재분리 — 규칙 판정)**, **`adr-016`(AI 방법 선택 — 문제별 도구)**, `adr-017`(HRV 보류), **`adr-020`(심박 예측 = 생리 ODE)**, **`adr-023`(워치 = 무로직 뷰어)**
- 폐기/강등(전체 목록: `arch/archive/README.md`): `arch/archive/adr-005`(판정 MLP) = **Superseded by adr-013**, `spec/archive/spec-006`(판정 MLP) = **Superseded**, `arch/archive/adr-003`(옛 DP1 판정 접근) 및 `arch/archive/adr-014`(역치 추정 NN) = **Demoted by adr-016**, `arch/archive/adr-011`(순전파 런타임)/`arch/archive/adr-019`(온라인 예측 보정)/`spec/archive/spec-014`(심박 동역학 NN)/`spec/archive/spec-015`(역치 NN)/`spec/archive/spec-018`(온라인 보정) = **adr-020(생리 ODE)으로 대체**, `arch/archive/adr-022`(워치 존 미러) = **adr-023으로 대체**, `arch/archive/adr-018`(음성 토크테스트) = **보류(spec-016 설문 유지)**
- 상세 명세: `spec/spec-003`(HR 파이프라인), `spec-004`(개인 Bayesian 경계), `spec-005`(LLM 코칭), `spec-007`(기록/리포트), `spec-008`(안전), `spec-009`(프로필), `spec-010`(워치 앱), `spec-011`(폰 앱), `spec-012`(필드 로그), `spec-013`(프로필 factor prior), `spec-016`(토크테스트), **`spec-019`(가상러너 검증 계기)**, **`spec-022`(수동 가상러너 시뮬)**, `spec-023`(설명 서비스)
- 보고서: `report/report-003-certification-final.md` — 보고서 DP 매핑은 그 Appendix D (파일 DP 번호와 보고서 DP 번호는 다를 수 있음)
