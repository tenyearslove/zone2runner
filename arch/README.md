# arch/ — 아키텍처 문서 인덱스

번호는 생성 순서의 식별자다(재부여하지 않음). 빠진 번호는 archive로 이동한 결정이다. 전 문서는 2026-07-31 구현 전수감사로 정합 확인/갱신됨.

## 아키텍처 정본

| 문서 | 역할 |
|---|---|
| `architecture-overview.md` | 시스템 전체 개요 + 강의 AI System 매핑 (아키텍처 정본) |
| `component-catalog.md` | 컴포넌트 카탈로그(우리 시스템 = DP들의 1안) |
| `diagrams/` | C&C/컨텍스트/배포 PlantUML (상세+PPT 축약 쌍) |
| `system-architecture-diagram.md` | mermaid 개요 도식(검토용 보조) |
| `zone2-physiology-and-estimation.md` | Zone 2 생리/경계 추정 리서치 정본(§4 예측부는 역사 기록) |
| `dp/` | DP 설계문서(문제+2안 비교+결정) — 인덱스 = `dp/README.md` |

## ADR (활성)

| # | 제목 | 결정 요지 |
|---|---|---|
| 001 | watch-phone-architecture | 워치=수집, 폰=AI (하이브리드) |
| 002 | ondevice-llm-coaching | 방향=규칙, 표현=LLM, 출력 가드 (폴백 계층은 adr-028로 개정) |
| 004 | personalization-model-approach | 개인화 = 온라인 Bayesian |
| 007 | ondevice-llm-runtime-verification | Nano 실기기 검증 (다운로드 정책은 adr-027로 개정) |
| 008 | wear-hr-collection-and-deployment | ExerciseClient 수집/배포 |
| 009 | background-hr-service | 워치 포그라운드 서비스 |
| 010 | phone-map-rendering | osmdroid 지도 |
| 012 | cold-start-prior-from-profile-factors | 프로필 factor 콜드스타트 prior |
| 013 | zone2-judgment-role-separation | 판정=규칙 (역할 분리) |
| 017 | hrv-rr-interval-deferral | HRV/IBI 보류 |
| 023 | watch-pure-viewer-instant-hr-display-zone | 워치 무로직 뷰어 |
| 024 | fr3-hr-prediction-drop-to-observation-analysis-engine | 예측 드롭 → 관측 분석 엔진 |
| 025 | ai-method-selection-no-nn | AI≠NN, 자체 NN 0개 (방법 선택 정본) |
| 026 | adopt-mlkit-genai-rewriting-summarization | Nano 과제형 API (Rewriting은 adr-028로 폐지, Summarization 유지) |
| 027 | nano-model-download-policy | 모델 첫 실행 자동 다운로드+진행률+설정 상태 |
| 028 | llm-first-expression-word-level-fallback | 코칭 표현 = LLM 직생성 + 단어 폴백 + 숫자 가드 (표현 정본) |

## 보관 (arch/archive/ — 사유표는 archive/README.md)

003, 005, 006, 011, 014, 016, 018, 019, 020 등 ADR(NN/예측 계열 폐기), 구 예측 설계 워크스루, system-architecture-lecture-style(중복), research-gemini-nano(결정 완료된 리서치 노트).
