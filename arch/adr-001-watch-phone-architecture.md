# ADR-001: Watch-Phone 앱 구성 방식

- **날짜**: 2026-06-26
- **상태**: 승인
- **결정자**: 팀 전체

---

## 배경

Zone 2 코칭 앱은 두 가지 핵심 능력이 필요하다.

1. **실시간 심박 수집** — Galaxy Watch 온센서 데이터가 필요 (1~2초 주기)
2. **온디바이스 AI/LLM** — Zone 2 판정 모델 + On-device LLM 코칭 생성

이 두 능력이 서로 다른 디바이스에 맞게 설계되어 있어, 앱 구성 방식을 명확히 결정해야 한다.

---

## 대안 비교

### 대안 A — Phone 앱 단독

Phone 측 Samsung Health SDK API만으로 Watch HR 데이터를 수집한다.

- **장점**: 앱이 하나라 개발 복잡도 최소
- **단점**: Samsung Health Phone API는 집계/이력 데이터만 제공. 운동 중 1~2초 주기의 **실시간 HR 스트림 불가** → 핵심 요구사항 미충족

### 대안 B — Wear OS 앱 단독

Watch에서 HR 수집, Zone 2 판정, 코칭 생성, TTS 출력을 모두 처리한다.

- **장점**: 기기 간 통신 불필요
- **단점**: Watch 컴퓨팅 파워로 On-device LLM 실행 불가. Wear OS에서 Gemini Nano/Llama 계열 모델 동작 불가 → AI 코칭 핵심 기능 구현 불가

### 대안 C — Hybrid (Watch 최소 companion + Phone 메인) ← **선택**

```
[Galaxy Watch]                         [Android Phone]
──────────────────                     ──────────────────────────────
Samsung Health Sensor SDK              Zone 2 판정 (경량 AI 모델)
HR 실시간 수집 (1~2초)   ──Data──▶    On-device LLM 코칭 생성
Wear OS 앱 (최소 UI)       Layer       TTS 음성 출력
                                       메인 UI
```

- **장점**: 실시간 HR 수집과 AI 처리를 각 디바이스의 강점에 맞게 분리
- **단점**: 앱이 두 개(Watch companion + Phone 앱). Wearable Data Layer 연동 필요
- **복잡도 완화**: Watch 쪽은 "HR 수집 + 전송"만 담당하는 최소 앱 — Wear OS 고유 UI 거의 불필요

---

## 결정

**대안 C (Hybrid)** 를 선택한다.

실시간 HR 스트림은 Watch 측 Wear OS companion 없이는 불가하고, On-device LLM은 Phone 없이는 불가하다. 두 제약이 Hybrid를 유일한 현실적 선택으로 만든다.

---

## 구현 전략

Watch 쪽 없이도 개발을 시작할 수 있도록 **두 단계로 진행**한다.

1. **1단계 (Phone 앱 우선)**: Mock HR 스트림(고정/패턴 심박 데이터)으로 Zone 2 판정 AI·LLM 코칭 전체 흐름 구현 및 검증
2. **2단계 (Watch companion 추가)**: Samsung Health Sensor SDK + Wearable Data Layer 연동으로 실제 Watch HR 수신. Mock 스트림을 실제 스트림으로 교체

---

## 영향 범위

- `spec/spec-003-hr-data-pipeline.md` 작성 필요 — Watch→Phone HR 전달 구조 상세화
- Phone 앱과 Wear OS 앱을 하나의 저장소에서 별도 모듈(`app/`, `wear/`)로 관리
- On-device LLM 모델 선정은 별도 ADR 필요 (`adr-002-ondevice-llm-selection.md`)
