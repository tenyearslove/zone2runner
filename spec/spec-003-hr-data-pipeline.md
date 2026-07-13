# Spec-003: HR 데이터 파이프라인 (Watch→Phone, 소스 교체 가능)

- **상태**: Draft
- **날짜**: 2026-07-01
- **관련 ADR**: `arch/adr-001-watch-phone-architecture.md`, `arch/archive/adr-003-zone2-classification-approach.md`(탈락 — 판정은 adr-013)

## 목표

Galaxy Watch 8이 수집한 실시간 심박수를 Galaxy S26 Ultra의 Zone 2 판정 파이프라인까지 안정적으로 전달한다. 동시에 심박 소스를 추상화해 Watch 없이 시뮬 소스(가상러너/수동 러너 시뮬, spec-019/022)로 전체 파이프라인을 독립 검증할 수 있게 한다(QA5 테스트가능성).

## 범위

**포함**:
- 심박 소스 추상화 인터페이스 (`HrSource`)
- Watch→Phone 전송 (Wearable Data Layer)
- 샘플링/버퍼링 (1~2초 주기)
- 이상값 필터 (생리적 범위 40~220 bpm, QA3 강건성)

**제외**:
- Zone 2 판정 로직 (adr-013 / spec-004)
- Samsung Health Sensor SDK 실제 연동 코드 상세 (구현 시)
- LLM 코칭 (spec-005)

## 기능 명세

### 주요 흐름
1. Watch: Sensor SDK가 심박을 1~2초 주기로 수집
2. Watch: 샘플링 후 Wearable Data Layer로 Phone에 전송
3. Phone: 수신 → 이상값 필터(40~220 밖은 기각) → 최근값 버퍼
4. Phone: `HrSource` 인터페이스가 판정 파이프라인에 심박 스트림 공급
5. 판정/개인화/코칭은 `HrSource`만 의존 (소스 종류 불문)

### 시뮬 소스 교체
- 시뮬 소스(가상러너 재생/수동 러너 조종, spec-019/022)가 동일 소스 인터페이스로 합성 심박을 공급
  (구 `MockHrSource`/가짜 라이브는 커밋 7e6d172에서 제거 — 수동 가상러너 시뮬(spec-022)이 상위호환)
- Watch 없이 실행 시 시뮬 소스 주입 → 판정~코칭 전체 파이프라인 동작 (QA5 테스트가능성)

### 예외 처리
- Data Layer 연결 끊김: 마지막 유효값 유지 + 일정 시간 초과 시 코칭 보류
- 이상값 연속 유입: 기각 후 직전 유효 구간 유지 (QA3 강건성)

**수신 유통기한 `staleMs` = 15000ms(種類 C)**: `WatchHrProvider`는 마지막 수신 HR/SPM을 붙잡아 두되, 15초 넘게 미수신이면 `-1`(무효) 반환(`WatchHrProvider.kt:21`). 근거: 워치 HR은 BT/Wi-Fi로 간헐 끊김이 잦아 이보다 짧으면 파이프라인이 자주 멈춘다(실기기 관찰). 이는 워치 자체 표시용 8초 신선도(spec-010)와 별개인 폰측 수신 관용치로, 필드 데이터로 조정. 워치→폰 전송 범위 게이트(HR 30~240, SPM 60~260)는 spec-001/§FR1 참조.

## 수락 기준 (AC)
- [ ] AC-1: `HrSource` 인터페이스로 실센서/시뮬 소스를 코드 변경 없이 교체 가능
- [ ] AC-2: 40~220 bpm 밖 입력은 100% 기각되고 다운스트림에 전달되지 않음 (QA3 강건성)
- [ ] AC-3: 시뮬 소스만으로 판정~코칭 파이프라인이 끝까지 실행됨 (QA5 테스트가능성)
- [ ] AC-4: Watch→Phone 전송 지연을 측정할 수 있음 (QA6 수행효율성 기여)

## 미해결 사항
- [ ] Data Layer 재연결/버퍼 정책 상세
- [ ] 전송 주기와 배터리 소모 트레이드오프
