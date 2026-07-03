# Spec-001: 기능 요구사항 및 제약사항

- **상태**: Approved
- **날짜**: 2026-06-25

---

## 기능 요구사항

| ID | TITLE | Description |
|:---:|:---|:---|
| FR1 | 초기 Zone 2 범위 산정 | 사용자의 나이, 성별, 체중, 안정시 심박수 등 기본 프로필을 입력받아 운동생리학 공식(HRR) 기반으로 개인별 초기 Zone 2 심박 범위를 산정한다. |
| FR2 | 웨어러블 심박 데이터 수집 | Galaxy Watch에 설치된 Wear OS 앱이 Samsung Health Sensor SDK를 통해 심박수를 실시간 수집하고, Wearable Data Layer API로 Android 폰 앱에 전달한다. |
| FR3 | 실시간 Zone 2 상태 판단 | 수집된 심박수, 페이스, Cardiac Drift를 경량 온디바이스 AI 모델로 분석하여 현재 Zone 2 진입 / 유지 / 이탈 상태를 실시간으로 판정한다. |
| FR4 | 상황 맞춤 음성 코칭 | Zone 2 판정 결과와 GPS(오르막·내리막), 페이스 변화, 날씨 등 실시간 맥락을 On-device LLM에 전달하여 상황에 맞는 코칭 멘트를 생성하고 TTS로 음성 출력한다. |
| FR5 | 운동 이력 기반 개인화 보정 | 누적된 운동 세션 데이터(HR-Pace 관계, Cardiac Drift, 회복 지표)를 분석하여 개인별 Zone 2 범위를 점진적으로 보정한다. |
| FR6 | 운동 기록 저장 및 리포트 | 운동 세션 종료 후 주요 지표(Zone 2 유지 시간·비율, 평균 심박수, Cardiac Drift)를 저장하고, 세션별 요약 리포트와 이력 조회를 제공한다. |

---

## 제약사항

| ID | TITLE | Description |
|:---:|:---|:---|
| C01 | 웨어러블 데이터 접근 제약 | Samsung Health Sensor SDK는 Galaxy Watch 4 이상에서만 동작하며, 기기·OS 버전·권한 정책에 따라 수집 가능한 센서 데이터 범위가 제한될 수 있다. |
| C02 | 초기 개인화 정확도 제약 | 운동 데이터가 충분히 누적되기 전까지는 공식(+프로필 factor) 기반 Zone 2 추정값을 사용하며, 개인화 보정(FR5)은 세션 누적으로 점진 적용된다. |
| C03 | 의료 서비스 범위 제한 | 본 시스템은 운동 보조 목적으로 한정되며, 의료적 진단·처방·질병 예측 기능을 제공하지 않는다. 위험 심박 구간 감지 시 운동 중단 권고 안내를 제공한다. |
| C04 | 개인 Zone 2 경계의 참값 부재(근본 제약) | Zone 2는 생리학적으로 1차 젖산역치(LT1/유산소 임계) 부근이며, 그 참값은 혈중 젖산 검사 또는 가스교환(VT1) 검사로만 측정된다. **손목 심박+GPS만으로는 참값을 측정할 수 없다** — 소비자 기기의 모든 Zone 2 값은 대리지표 기반 추정이다. 이는 구현 한계가 아니라 문제의 성질이다. 따라서 (a) 판정 정확도 자체가 아니라 코칭 방향 정확성(QA1)으로 품질을 평가하고, (b) 개인화는 참값 수렴이 아니라 물리 관측 기반 적응 메커니즘으로 검증하며, (c) 다신호(디커플링)+자가관측(토크 테스트)로 관측 품질을 높이고 향후 DFA-α1(HRV) 관측을 확장한다. 근거: `arch/zone2-physiology-and-estimation.md`. |

---

## 요구사항 → 설계 추적

| 요구 | 설계 문서 | 구현 |
|:---:|------|------|
| FR1 초기 Zone2 산정 | spec-009(프로필/RHR), adr-003(HRR baseline), **adr-012/spec-013(factor prior)** | app ProfileActivity(factor 칩 UI)/ProfileStore/Zone2Prior |
| FR2 웨어러블 HR 수집·전달 | adr-001(Hybrid), adr-008(HR API/배포), adr-009(백그라운드), spec-003(파이프라인) | wear RunService(포그라운드)+HrForwarder, app WatchHrProvider. **appId 통일(Data Layer 필수)** |
| FR3 실시간 Zone2 판정 | adr-003(DP1), adr-005(DP4 MLP), adr-011(추론 런타임), spec-006 | ml/(학습), app Zone2Classifier(온디바이스). 워치 존 표시는 /zones 동기화로 기준 일치 |
| FR4 상황 코칭 + TTS | adr-002(DP2), adr-007(LLM 런타임 검증), spec-005 | llm-verify, app LlmCoach/RuleCoach + **DirectionGuard(방향 잠금)** + TTS |
| FR5 개인화 보정 | adr-004(DP3 Bayesian), spec-004, spec-013(안전 가드), spec-007(세션 데이터) | ml/personalization.py, app Personalization(prior 연동 + ±10bpm/≤80%HRR 가드) |
| FR6 기록 저장·리포트 | spec-007, spec-011(리포트/지도), adr-010(지도) | app SessionStore/ReportActivity/HistoryActivity |
| 러닝 UI(워치/폰) | spec-010(워치 대시보드), spec-011(폰 앱) | wear/(실기기 레이아웃 튜닝 완료), app/ |
| 필드 검증 데이터 | **spec-012(필드 로그)** | app RunLogger(JSONL), ml/analyze_runlog.py, FIELD_TEST.md |
| C01 웨어러블 접근 제약 | adr-001, adr-008 | Health Services 채택으로 해소 |
| C02 초기 개인화 정확도 | adr-003(콜드스타트), spec-009, **adr-012(factor prior, 오차 -42%)**, spec-004 | 규칙 폴백 + factor prior + RHR 자동 추정 |
| C03 의료 범위 제한/안전 | spec-008(안전 가드) | 이상치 가드, 코칭 방향 가드, 개인화 상한 가드 |
| **C04 Zone2 참값 부재** | **arch/zone2-physiology-and-estimation.md**, spec-002(평가 관점), adr-003/004/012 | 방향 정확성 평가(QA1), 물리 관측+토크 테스트 자가관측, 안전 가드, DFA-α1 향후 |

- **참고(FR2 정합)**: spec-001은 실시간 HR 소스로 Samsung Health Sensor SDK를 상정했으나, adr-008에서 **PoC는 Wear OS Health Services**(승인 불필요)로 채택하고 HRV/화면off 필요 시 Samsung SDK로 승격하기로 정함.
- **QA 달성 현황**: `spec/spec-002-quality-attributes.md` 부록(검증 스냅샷) 참조 — QA1/QA2/QA5 달성, QA3 메커니즘 실증, QA4 실기기 측정 대기.

## 관련 문서
- 설계 전반: `arch/architecture-overview.md`, `arch/adr-001`~`adr-012`
- 품질: `spec/spec-002-quality-attributes.md`
- 보고서: `report/report-003-certification-final.md` (DP1/DP2/DP3 ↔ ADR 매핑은 그 Appendix D)
