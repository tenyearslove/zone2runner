# 아키텍처 개요 (최종 Architecture)

- **날짜**: 2026-07-01
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

두 개의 AI 역량으로 압축된다: (1) **개인화된 Zone 2 경계 추정**, (2) **LLM 상황 코칭**. 판정과 개인화는 별개가 아니라 하나이고, 코칭의 방향은 규칙이 보장한다.

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
                                    ├ 규칙 baseline (콜드스타트)      ← C02
                                    └ 개인화 경계 (Bayesian)  ◀─┐    ← QA3 (핵심)
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

## 모듈 구성 (Module View)

```
zone2runner/
├── wear/      Galaxy Watch 앱 — HR 수집 + 전송 (최소 companion)
├── app/       Galaxy S26 Ultra 앱 — 판정/개인화/코칭/UI
│   ├─ hr           HrSource 추상화, Data Layer 수신, 이상값 가드   (spec-003)
│   ├─ zone2        규칙 baseline + 개인화 경계 추정 + 판정         (adr-003, spec-004)
│   ├─ coaching     의도 결정 + LLM 표현 + 출력 가드 + TTS          (adr-002, spec-005)
│   └─ session      세션 기록/리포트, 개인화 데이터 누적            (FR6)
└── shared/    공유 도메인 모델 (HR 샘플, Zone 상태, 프로필)
```

핵심 원칙: `zone2`, `coaching`은 `HrSource` 인터페이스에만 의존 → Watch 없이 Mock으로 전체 검증 (QA5).

---

## 품질 속성 매핑

| QA | 달성 지점 |
|:---:|------|
| QA1 기능정확성 | 코칭 방향을 규칙이 결정 + 출력 가드 (adr-002) |
| QA2 강건성 | 이상값 가드 40~220 (spec-003), 개인화 급변 방지 (spec-004) |
| QA3 적응성 | Bayesian 개인화 경계 (adr-003, spec-004) |
| QA4 효율성 | 프롬프트 최소화 + 템플릿 폴백 (adr-002, spec-005) |
| QA5 테스트가능성 | HrSource 추상화로 Mock 교체 (spec-003) |

---

## 개발 단계 (Deployment / 구현 순서)

1. **1단계 (PoC, 얇게)**: `app/`만. Mock HR → Zone 2 판정 → 간단 코칭. Watch/LLM은 stub. 전체 파이프라인 흐름과 QA 검증 골격 확보.
2. **2단계 (실기기 연동)**: `wear/` 추가, Samsung Health Sensor SDK + Wearable Data Layer로 실제 HR. On-device LLM 실연동.

---

## 관련 문서

- 요구/품질: `spec/spec-001-requirements.md`, `spec/spec-002-quality-attributes.md`
- 설계 결정: `arch/adr-001`, `arch/adr-003 (DP1)`, `arch/adr-002 (DP2)`
- 상세 명세: `spec/spec-003 (HR 파이프라인)`, `spec/spec-004 (개인화)`, `spec/spec-005 (LLM 코칭)`
