# 아키텍처 개요 (최종 Architecture)

- **날짜**: 2026-07-01 (최종 갱신 2026-07-05 — adr-013/016 판정 재분리 반영)
- **용도**: 전체 설계를 한눈에 보는 진입 문서. 세부는 각 ADR/Spec 참조
- **대상 기기**: Galaxy Watch 8 (수집) + Galaxy S26 Ultra (판정/코칭)

---

## 한 줄 요약

Galaxy Watch가 심박을 실시간 수집해 폰에 보내면, 폰이 규칙으로 Zone 2 상태를 판정하고(개인 경계 + 히스테리시스) On-device LLM으로 상황 맞춤 음성 코칭을 출력한다. 개인 경계는 온라인 Bayesian으로 적응하고, 심박 동역학 NN은 선제 코칭/페이스 제안을 담당한다. 문제마다 맞는 도구를 고른다 (AI ≠ NN, adr-016).

---

## 핵심 설계 결정 (Architectural Decision)

핵심 관점은 **"AI ≠ NN, 문제마다 맞는 도구를 고른다"**(adr-016)이다. 판정은 규칙, 개인화는 Bayesian, 심박 예측은 NN, 표현은 LLM이 맡는다.

| DP | 결정 | 문서 |
|:---:|------|------|
| DP0 | Watch-Phone Hybrid 구조 (Watch=수집, Phone=판정/코칭) | `arch/adr-001-watch-phone-architecture.md` |
| **DP1** | 심박 예측 = 개인 심박 동역학 NN (HrDynamics — 선제 코칭/페이스 제안) | `arch/adr-013`, `adr-016`, `spec/spec-014` |
| **DP2** | 개인 Zone 2 범위 = 온라인 Bayesian 적응 (토크테스트/디커플링 라벨) | `arch/adr-004`, `adr-016`, `spec/spec-004` |
| **DP3** | Zone 2 판정 = 규칙 (ZoneJudge — 지속 심박 vs 개인 경계 + 히스테리시스) | `arch/adr-013`, `adr-016` |
| **DP4** | LLM 코칭 = 규칙이 방향 결정 + LLM이 표현 + 출력 가드 | `arch/adr-002-ondevice-llm-coaching.md` |

AI 역량: (1) **심박 동역학 예측 NN**(HrDynamics, 학습 산출물 — 선제 코칭/목표 페이스 제안), (2) **Bayesian 개인 Zone 2 경계 추정**, (3) **규칙 기반 실시간 판정**(ZoneJudge — 결정론/즉시), (4) **LLM 상황 코칭**. 판정과 표현을 분리해, 판정은 규칙으로 결정론/저지연을 보장하고 NN/LLM은 그 위에서 예측과 표현을 담당한다.

- **DP3 판정**은 규칙(ZoneJudge). 지속 심박을 개인 경계와 비교하고 히스테리시스로 상태 떨림을 억제한다. 결정론이라 QA1(방향)/QA4(저지연)에 유리. **과거 다변량 MLP 판정기(adr-005/spec-006)는 시뮬레이터 라벨 순환 결함으로 Superseded**.
- **DP2 개인화**는 신경망이 아니라 경량 Bayesian 적응추정(소수/온라인 라벨, float 산술). 역치 추정 NN(adr-014)은 개인화 경로에서 제거(Demoted). 상세 `spec/spec-004`.
- **DP1 심박 예측 NN**은 개인 심박 동역학 모델(HrDynamics). 순수 Kotlin 순전파(adr-011)로 온디바이스 추론하며, 판정이 아니라 선제 코칭/목표 페이스 제안에 쓰인다. 상세 `spec/spec-014`.
- **Zone 2 기준**은 %HRmax (상단 70% = LT1, 하단 ~60%)이다. %HRR 아님.

---

## 데이터 흐름 (Context / C&C View)

```
[Galaxy Watch 8]                    [Galaxy S26 Ultra]
─────────────────                   ─────────────────────────────────────
Health Services                     RunSource 인터페이스 (Live / Sim / Mock)
HR 1~2초 수집   ──Wearable──▶       │
                 Data Layer         ▼
                                    OutlierGuard 이상값 가드 (규칙, 40~220 bpm)   ← QA2
                                    │
                        ┌───────────┴───────────────┐
                        ▼                            ▼
             판정: ZoneJudge (규칙)          특징 추출 (FeatureExtractor)
             지속 심박 vs 개인 경계                  │
             + 히스테리시스 (%HRmax)                 ▼
             → Zone2 상태 (결정론)          심박 예측 NN (HrDynamics)     ← DP1
                        │                   선제 코칭 / 목표 페이스 제안
                        │                            │
                        └───────────┬────────────────┘
                                    ▼
                        개인화 경계 (Bayesian)  ◀─┐    ← QA3 (경계 적응)
                        │  토크테스트/디커플링     │
                        │                          세션 누적 갱신
                        ▼
                        코칭 의도 결정 (규칙, 방향)      ← QA1 방향
                        │  + 맥락(고도/페이스/SPM/날씨)
                        ▼
                        On-device LLM 표현 생성
                        │
                        출력 가드 → TTS 음성            ← QA1/QA4
```

- 판정(ZoneJudge 규칙)과 예측(HrDynamics NN)은 별개 경로다. 판정은 규칙으로 즉시 결정하고, NN은 그 옆에서 선제 코칭/페이스 제안을 만든다.
- 개인 경계는 온라인 Bayesian이 토크테스트/디커플링 신호로 적응시켜 ZoneJudge에 공급한다.

---

## 모듈 구성 (Module View — 구현 반영, 2026-07-05)

```
zone2runner/
├── wear/      Galaxy Watch 앱 — RunService(포그라운드, 화면off 지속) + 대시보드     (spec-010, adr-009)
│              HR 송신(HrForwarder /hr) + 존 경계 수신(ZoneSyncService /zones)
│              ※ Data Layer 라우팅 제약으로 applicationId는 폰과 동일(com.zone2runner.app)
├── app/       Galaxy S26 Ultra 앱 — 판정/개인화/코칭/UI                          (spec-011)
│   ├─ sensor/    RunSource 추상화 (Simulated / Live GPS+WatchHr / Mock)          (spec-003 HrSource 사상, QA5)
│   ├─ pipeline/  OutlierGuard + FeatureExtractor + ZoneJudge(규칙 판정)          (adr-013/016, spec-014)
│   │             + HrDynamics(심박 예측 NN) + Personalization(Bayesian) + RunEngine (adr-004/011, spec-004)
│   │             ※ Zone2Classifier = LEGACY(미사용, adr-005 판정 MLP의 잔재)
│   ├─ coaching/  의도 결정(규칙) + LLM 표현(Gemini Nano) + 방향 잠금 가드 + TTS    (adr-002/007, spec-005)
│   ├─ data/      세션 JSON 영속화 + 프로필 저장 + 필드 로그(RunLogger) + 존 동기화  (spec-007/009/012/013)
│   ├─ domain/    공유 모델 + Zone2Prior(factor→prior 순수 함수) + VirtualRunner    (adr-012, spec-013)
│   │             ※ VirtualRunner = 폐루프 시뮬용 가상 러너(코칭 반영해 심박/페이스 반응)
│   ├─ sim/       물리 러닝 시뮬레이터 + SimRunnerSource(폐루프 시뮬 소스)          (ml/simulator.py 포팅)
│   └─ ui/ + 화면 6개 (Home/Run/Report/History/Profile/MockConfig)                 (지도 osmdroid, adr-010)
├── ml/        심박 동역학 NN 학습 train_hr_dynamics.py(PyTorch) + export           (adr-013/011, spec-014)
│              + prior/개인화 실험 + 필드 로그 분석 (adr-012)
├── sensor-poc/  실기기 HR/GPS 수집 검증 (백그라운드 서비스 포함, adr-008/009)
└── llm-verify/  Gemini Nano 실기기 검증 (adr-007)
```

핵심 원칙: 파이프라인은 `RunSource`(Sample 1Hz)에만 의존 → Watch 없이 Mock/시뮬로 전체 검증 (QA5, 달성). 엔진/러너를 분리해 `SimRunnerSource` + `VirtualRunner`로 코칭이 심박에 되먹임되는 **폐루프 시뮬**까지 자동 검증한다.
계획 시점 구조(hr/zone2/session, shared/)는 구현에서 위 구조로 정착 — 공유 모델은 `app/domain/`으로 수렴(워치는 표시 전용이라 shared 모듈 불필요).

---

## 품질 속성 매핑 (달성 지점 + 검증 현황)

| QA | 달성 지점 | 검증 현황 (2026-07-05) |
|:---:|------|------|
| QA1 기능정확성 | 판정: 규칙(ZoneJudge — 지속 심박 vs 개인 경계 + 히스테리시스, 결정론) (adr-013) / 코칭 방향: 규칙 + **방향 잠금 가드(DirectionGuard)** + LLM 표현 (adr-002/013) | **달성(개발지표)**: 규칙 판정 결정론 재현 + 실기기 LLM 출력 가드 검증(무방향 문장 기각) |
| QA2 강건성 | OutlierGuard 이상값 가드 40~220 (규칙, spec-003), 개인화 가드(세션 내 ±10bpm) (spec-013) | **달성**: 이상치 기각 1.0 + 관측 폭주 가드 테스트 |
| QA3 적응성 | factor prior(adr-012) → 온라인 Bayesian 개인 경계 (adr-004) → 토크테스트/디커플링 신호로 적응 | **메커니즘 실증**: 수렴(오차 5→2bpm) + **콜드스타트 오차 -42%**(prior 실험). 실측 임계추출 편향은 한계로 명시 |
| QA4 효율성 | 규칙 판정 즉시 결정(결정론, 1Hz 무정지) + 프롬프트 최소화 (adr-002) + 코칭 비동기 분리 | 실기기: LLM 생성 1.1~2.7초(중앙값 1.3초), 판정 1Hz 무정지 — 5초 내 충족. 야외 재확인은 필드 |
| QA5 테스트가능성 | RunSource 추상화로 Mock 교체 (spec-003, spec-011) + **VirtualRunner 폐루프 시뮬(엔진/러너 분리)** + 필드 로그(spec-012) | **달성**: Mock/시뮬/폐루프로 Watch 없이 전 파이프라인 + 단위 테스트 + 로그 분석 파이프라인 |

---

## 개발 단계 (Deployment / 구현 순서)

1. **1단계 (PoC, 얇게)** — **완료**: `app/` Mock/시뮬 HR → Zone 2 판정 → 코칭. 전체 파이프라인 흐름과 QA 검증 골격 확보(단위 테스트 66건).
2. **2단계 (실기기 연동)** — **대부분 검증 완료(2026-07-03)**: 폰 — Gemini Nano 코칭 실동작+TTS, 시뮬 세션 e2e, 프로필 factor UI.
   워치 — 세션 시작/권한/포그라운드 서비스 화면off 지속(adr-009), 존 동기화(/zones) 수신.
   **잔여: 착용 상태 HR 스트림(워치→폰) + 야외 GPS — 필드 테스트(FIELD_TEST.md)**. Samsung Health Sensor SDK 대신 Wear OS Health Services 채택(adr-008).

---

## 관련 문서

- 요구/품질: `spec/spec-001-requirements.md`, `spec/spec-002-quality-attributes.md`
- 설계 결정: `arch/adr-001`(DP0), `adr-002`(LLM 코칭), `adr-004`(개인 Bayesian 경계), `adr-006`(대안 비교), `adr-007`(LLM 검증), `adr-008`(HR 수집), `adr-009`(백그라운드), `adr-010`(지도), `adr-011`(온디바이스 순전파 런타임), `adr-012`(콜드스타트 factor prior), **`adr-013`(판정 역할 재분리 — 규칙 판정 + NN 재조준)**, **`adr-016`(AI 방법 선택 — 문제별 NN/Bayesian/규칙)**
- 폐기/강등: `arch/adr-005`(판정 MLP) = **Superseded by adr-013**, `spec/spec-006`(판정 MLP) = **Superseded by spec-014**, `arch/adr-003`(옛 DP1 판정 접근) 및 `adr-014`(역치 추정 NN) = **Demoted by adr-016**
- 상세 명세: `spec/spec-003`(HR 파이프라인), `spec-004`(개인 Bayesian 경계), `spec-005`(LLM 코칭), **`spec-014`(심박 동역학 NN — 예측/페이스 제안)**, `spec-007`(기록/리포트), `spec-008`(안전), `spec-009`(프로필), `spec-010`(워치 앱), `spec-011`(폰 앱), `spec-012`(필드 로그), `spec-013`(프로필 factor prior)
- 보고서: `report/report-003-certification-final.md` — 보고서 DP 매핑은 그 Appendix D (파일 DP 번호와 보고서 DP 번호는 다를 수 있음)
