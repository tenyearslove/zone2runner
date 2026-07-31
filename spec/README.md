# spec/ — 기능 명세 인덱스

번호는 생성 순서의 식별자다(재부여하지 않음 — 코드 주석/커밋 이력의 참조를 보존). 빠진 번호는 archive로 이동한 문서다. 전 문서는 2026-07-31 구현 전수감사로 정합 확인/갱신됨.

## 활성 (현재 적용 중)

| # | 제목 | 상태 | 한 줄 |
|---|---|---|---|
| 001 | requirements | Approved | FR1~6 + 제약 + QA 추적표 (요구 정본) |
| 002 | quality-attributes | Approved | QA 6종 시나리오/측정 (QA 정본) |
| 003 | hr-data-pipeline | Implemented | 심박 수집→정제→공급 파이프라인 |
| 004 | zone2-personalization | Implemented | 온라인 Bayesian 경계 개인화 |
| 005 | llm-coaching-generation | Draft(부분 대체) | 코칭 원칙 — 표현 경로는 spec-028이 정본 |
| 007 | session-record-and-report | Implemented | 세션 저장(JSON)/리포트 |
| 008 | safety-guard | Implemented | 위험 심박 규칙 권고(LLM 우회) |
| 009 | profile-and-resting-hr | Draft | 프로필 입력/RHR 폴백 사다리 |
| 010 | wear-running-dashboard | Implemented | 워치 뷰어 대시보드(무로직) |
| 012 | field-test-data-collection | Implemented | 필드 JSONL 로깅 |
| 013 | profile-factors-and-prior | Implemented | factor 기반 초기 prior |
| 016 | talk-test-improvement | Implemented | 말하기 테스트 3단계(실라벨) |
| 019 | virtual-runner-verification-instrument | Implemented | 가상 러너 검증 도구 |
| 020 | profile-management-and-personalization-viz | Implemented | 다중 프로필/개인화 시각화 |
| 021 | app-settings | Implemented | 설정(빈도/음성/더위/화면/AI 모델) |
| 022 | manual-virtual-runner-sim | Implemented | 수동 가상러너 시뮬 |
| 023 | explanation-service | Implemented | 설명 서비스(1회 생성/저장) |
| 024 | voice-and-persona-settings | Implemented | 목소리/페르소나(LLM 전용) |
| 025 | observation-analysis-engine | Implemented | 관측 분석 엔진(FR3) |
| 026 | downhill-joint-protection-coaching | Implemented | 내리막 관절 보호 코칭 |
| 027 | llm-prompt-provenance-and-usage-telemetry | Implemented | LLM 프로비넌스/사용 감사 |
| 028 | llm-first-coaching-expression | Implemented | LLM 직생성 + 단어 폴백 + 숫자 가드 (표현 정본) |
| 029 | phone-app-screens-and-flow | Implemented | 폰 앱 화면/플로우 as-built (구 011 대체) |

## 보관 (spec/archive/ — 사유표는 archive/README.md)

006, 011(→029), 014, 015, 017, 018 등 — 예측/NN 계열 폐기 및 대체 문서.
