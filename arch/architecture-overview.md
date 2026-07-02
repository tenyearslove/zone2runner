# 아키텍처 개요 (최종 Architecture)

- **날짜**: 2026-07-01 (최종 갱신 2026-07-03 — 구현 현황 반영)
- **용도**: 전체 설계를 한눈에 보는 진입 문서. 세부는 각 ADR/Spec 참조
- **대상 기기**: Galaxy Watch 8 (수집) + Galaxy S26 Ultra (판정/코칭)

---

## 한 줄 요약

Galaxy Watch가 심박을 실시간 수집해 폰에 보내면, 폰이 개인화된 Zone 2 경계로 상태를 판정하고 On-device LLM으로 상황 맞춤 음성 코칭을 출력한다.

---

## 핵심 설계 결정 (Architectural Decision)

| DP | 결정 | 문서 |
|:---:|------|------|
| DP0 | Watch-Phone Hybrid 구조 (Watch=수집, Phone=AI) | `arch/adr-001-watch-phone-architecture.md` |
| **DP1** | 개인화 Zone 2 판정 = 규칙 baseline + Bayesian 개인화 (개인화가 핵심) | `arch/adr-003-zone2-classification-approach.md` |
| **DP2** | LLM 코칭 = 규칙이 방향 결정 + LLM이 표현 + 출력 가드 | `arch/adr-002-ondevice-llm-coaching.md` |
| **DP3** | 개인화 경계 추정 = 공식 prior + 온라인 Bayesian 적응 | `arch/adr-004-personalization-model-approach.md` |
| **DP4** | Zone 2 판정기 = 다변량 MLP 분류기 (다신호 비선형 정확성) | `arch/adr-005-zone2-classifier-nn.md` |

AI 역량: (1) **다변량 MLP Zone 2 판정기**(학습 산출물), (2) **Bayesian 개인화 경계 추정**, (3) **LLM 상황 코칭**. 판정기(MLP)는 규칙 가드/폴백 위에서 동작하고 Bayesian 개인 경계로 입력이 정규화된다.

- **DP3 개인화**는 신경망이 아니라 경량 Bayesian 적응추정(라벨 불필요, float 산술). 상세 `spec/spec-004`.
- **DP4 판정기**는 학습된 다변량 MLP. 단일 HR 임계값(선형)으로는 오르막/Drift/노이즈 다신호 상황의 정확성(QA1/85%)을 못 채우기 때문에 도입. 라벨은 시뮬레이터, 콜드스타트는 규칙 폴백, 강건성은 노이즈 증강+가드. 상세 `spec/spec-006`.

---

## 데이터 흐름 (Context / C&C View)

```
[Galaxy Watch 8]                    [Galaxy S26 Ultra]
─────────────────                   ─────────────────────────────────────
Sensor SDK                          HrSource 인터페이스 (Real / Mock)
HR 1~2초 수집   ──Wearable──▶       │
                 Data Layer         ▼
                                    이상값 가드 (40~220 bpm)         ← QA2
                                    │
                                    Zone 2 판정
                                    ├ MLP 분류기 (다신호 판정)       ← QA1/85%
                                    ├ 규칙 baseline/가드 (폴백)      ← C02/QA2
                                    └ 개인화 경계 (Bayesian)  ◀─┐    ← QA3 (입력 정규화)
                                    │                          │
                                    ▼                    세션 누적 갱신
                                    코칭 의도 결정 (규칙)          ← QA1 방향
                                    │  + 맥락(고도/페이스/SPM/날씨)
                                    ▼
                                    On-device LLM 표현 생성
                                    │
                                    출력 가드 → TTS 음성            ← QA1/QA4
```

---

## 모듈 구성 (Module View — 구현 반영, 2026-07-03)

```
zone2runner/
├── wear/      Galaxy Watch 앱 — HR 수집/대시보드 + Data Layer 송신(HrForwarder)  (spec-010)
├── app/       Galaxy S26 Ultra 앱 — 판정/개인화/코칭/UI                          (spec-011)
│   ├─ sensor/    RunSource 추상화 (Simulated / Live GPS+WatchHr / Mock)          (spec-003 HrSource 사상, QA5)
│   ├─ pipeline/  이상값 가드 + 특징추출 + MLP 판정기 + Bayesian 개인화 + RunEngine (adr-003/004/005/011, spec-004/006)
│   ├─ coaching/  의도 결정(규칙) + LLM 표현(Gemini Nano) + 출력 가드 + TTS        (adr-002/007, spec-005)
│   ├─ data/      세션 JSON 영속화 + 프로필 저장                                   (spec-007/009, FR6)
│   ├─ sim/       물리 러닝 시뮬레이터 (ml/simulator.py 포팅)
│   └─ ui/ + 화면 6개 (Home/Run/Report/History/Profile/MockConfig)                 (지도 osmdroid, adr-010)
├── ml/        MLP 학습(PyTorch canonical) + 배포 export(sklearn -> zone2_mlp.json) (adr-005/011)
├── sensor-poc/  실기기 HR/GPS 수집 검증 (백그라운드 서비스 포함, adr-008/009)
└── llm-verify/  Gemini Nano 실기기 검증 (adr-007)
```

핵심 원칙: 파이프라인은 `RunSource`(Sample 1Hz)에만 의존 → Watch 없이 Mock/시뮬로 전체 검증 (QA5, 달성).
계획 시점 구조(hr/zone2/session, shared/)는 구현에서 위 구조로 정착 — 공유 모델은 `app/domain/`으로 수렴(워치는 표시 전용이라 shared 모듈 불필요).

---

## 품질 속성 매핑 (달성 지점 + 검증 현황)

| QA | 달성 지점 | 검증 현황 (2026-07-03) |
|:---:|------|------|
| QA1 기능정확성 | 판정: 다변량 MLP (adr-005) / 코칭 방향: 규칙+가드 (adr-002) | **달성(개발지표)**: 코칭방향 0.996 (ml 실험 + 온디바이스 재현) |
| QA2 강건성 | 이상값 가드 40~220 (spec-003), 노이즈 증강 MLP (spec-006), 개인화 급변 방지 (spec-004) | **달성**: 이상치 기각 1.0 (ml 실험 + 단위 테스트) |
| QA3 적응성 | Bayesian 개인화 경계 (adr-004, spec-004) → MLP 입력 정규화 | **메커니즘 실증**: 세션 누적 수렴(오차 5→2bpm). 실측 임계추출 편향은 한계로 명시 |
| QA4 효율성 | 프롬프트 최소화 (adr-002) + 경량 MLP 추론 (spec-006) | 부분: LLM warm ~2초 실기기 확인(adr-007). end-to-end 5초는 실기기 측정 대기 |
| QA5 테스트가능성 | RunSource 추상화로 Mock 교체 (spec-003, spec-011) | **달성**: Mock 모드(심박/속도 범위 지정) + 시뮬 재생으로 Watch 없이 전 파이프라인 실행, 단위 테스트 13건 |

---

## 개발 단계 (Deployment / 구현 순서)

1. **1단계 (PoC, 얇게)** — **완료**: `app/` Mock/시뮬 HR → Zone 2 판정 → 코칭. 전체 파이프라인 흐름과 QA 검증 골격 확보(단위 테스트 13건).
2. **2단계 (실기기 연동)** — **코드 완료, 실기기 검증 대기**: `wear/` HR 수집+Data Layer 송신, 폰 실 GPS(LiveRunSource), On-device LLM(Gemini Nano) 연동. Samsung Health Sensor SDK 대신 Wear OS Health Services 채택(adr-008).

---

## 관련 문서

- 요구/품질: `spec/spec-001-requirements.md`, `spec/spec-002-quality-attributes.md`
- 설계 결정: `arch/adr-001`(DP0), `adr-003`(DP1), `adr-002`(DP2), `adr-004`(DP3), `adr-005`(DP4), `adr-006`(대안 비교), `adr-007`(LLM 검증), `adr-008`(HR 수집), `adr-009`(백그라운드), `adr-010`(지도), `adr-011`(추론 런타임)
- 상세 명세: `spec/spec-003`(HR 파이프라인), `spec-004`(개인화), `spec-005`(LLM 코칭), `spec-006`(MLP), `spec-007`(기록/리포트), `spec-008`(안전), `spec-009`(프로필), `spec-010`(워치 앱), `spec-011`(폰 앱)
