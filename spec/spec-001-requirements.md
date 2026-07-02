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
| C02 | 초기 개인화 정확도 제약 | 운동 데이터가 충분히 누적되기 전까지는 공식 기반 Zone 2 추정값을 사용하며, 개인화 보정(FR5)은 일정 횟수 이상 데이터 누적 후 적용된다. |
| C03 | 의료 서비스 범위 제한 | 본 시스템은 운동 보조 목적으로 한정되며, 의료적 진단·처방·질병 예측 기능을 제공하지 않는다. 위험 심박 구간 감지 시 운동 중단 권고 안내를 제공한다. |

---

## 요구사항 → 설계 추적

| 요구 | 설계 문서 | 구현 |
|:---:|------|------|
| FR1 초기 Zone2 산정 | spec-009(프로필/RHR), adr-003(HRR baseline) | app ProfileActivity/ProfileStore |
| FR2 웨어러블 HR 수집·전달 | adr-001(Hybrid), adr-008(HR API/배포), adr-009(백그라운드), spec-003(파이프라인) | sensor-poc, wear HrForwarder, app WatchHrProvider |
| FR3 실시간 Zone2 판정 | adr-003(DP1), adr-005(DP4 MLP), adr-011(추론 런타임), spec-006 | ml/(학습), app Zone2Classifier(온디바이스) |
| FR4 상황 코칭 + TTS | adr-002(DP2), adr-007(LLM 런타임 검증), spec-005 | llm-verify, app LlmCoach/RuleCoach + TTS |
| FR5 개인화 보정 | adr-004(DP3 Bayesian), spec-004, spec-007(세션 데이터) | ml/personalization.py, app Personalization |
| FR6 기록 저장·리포트 | spec-007, spec-011(리포트/지도), adr-010(지도) | app SessionStore/ReportActivity/HistoryActivity |
| 러닝 UI(워치/폰) | spec-010(워치 대시보드), spec-011(폰 앱) | wear/, app/ |
| C01 웨어러블 접근 제약 | adr-001, adr-008 | Health Services 채택으로 해소 |
| C02 초기 개인화 정확도 | adr-003(콜드스타트), spec-009, spec-004 | 규칙 폴백 + 공식 prior |
| C03 의료 범위 제한/안전 | spec-008(안전 가드) | 이상치 가드, 코칭 출력 가드 |

- **참고(FR2 정합)**: spec-001은 실시간 HR 소스로 Samsung Health Sensor SDK를 상정했으나, adr-008에서 **PoC는 Wear OS Health Services**(승인 불필요)로 채택하고 HRV/화면off 필요 시 Samsung SDK로 승격하기로 정함.
- **QA 달성 현황**: `spec/spec-002-quality-attributes.md` 부록(검증 스냅샷) 참조 — QA1/QA2/QA5 달성, QA3 메커니즘 실증, QA4 실기기 측정 대기.

## 관련 문서
- 설계 전반: `arch/architecture-overview.md`, `arch/adr-001`~`adr-011`
- 품질: `spec/spec-002-quality-attributes.md`
