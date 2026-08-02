# Report — 인증 보고서 원고 인덱스

인증 보고서(PPT)의 파트별 원고. 조립 순서 = 01 과제 소개 → 02 요구사항(spec-001/002 참조) → 03 설계(DP + 최종 아키텍처) → 04 구현 및 검증 → 05 결론 → Appendix.

## 활성 원고

| # | 파일 | 파트 | 내용 |
|---|---|---|---|
| 008 | `report-008-dp01-explainability-decision.md` | 03 설계 | DP1 설명용이성 (3장 + Appendix) |
| 009 | `report-009-dp02-controllability-decision.md` | 03 설계 | DP2 제어가능성 (3장 + Appendix) |
| 010 | `report-010-dp03-adaptability-decision.md` | 03 설계 | DP3 기능적응성 (3장 + Appendix) |
| 011 | `report-011-dp04-robustness-decision.md` | 03 설계 | DP4 강건성 (3장 + Appendix) |
| 012 | `report-012-dp05-testability-decision.md` | 03 설계 | DP5 테스트가능성 (3장 + Appendix) |
| 013 | `report-013-final-architecture.md` | 03 설계 | 최종 Architecture (거시 1장 + 모듈 4장 + 뷰 안내) |
| 014 | `report-014-implementation.md` | 04 구현 | 화면 8종 기능 소개 + 스크린샷 자리 20장 지정 |
| 015 | `report-015-qa-verification.md` | 04 검증 | 품질속성 검증 표(실측) + QA별 지표 산출 근거/측정 기준 + 검증 결과 상세 |
| 016 | `report-016-conclusion.md` | 05 결론 | 주요 성과 / 향후 계획 |
| 017 | `report-017-appendix.md` | Appendix | 부록 조립 인덱스 + Use Case/Context/Module/C&C/Deployment 해설 |
| 018 | `report-018-intro-and-requirements.md` | 01+02 | 과제 소개(배경/필요성/개요) + 요구사항(FR/제약/QA 선정) |

- **도식 정본**: `arch/diagrams/`(일반 아키텍처), `arch/dp/dp-*/images/`(DP 카운터).
- **검증 실측 도구**: `app/app/src/test/.../QaMeasurementTest.kt` (측정값 재현).
- **HTML 렌더**: `html/` — 위 원고를 슬라이드로 렌더한 덱 11종(통합본 포함)과 조립 스크립트. 브라우저로 바로 열린다. Artifact 배포 URL 표도 `html/README.md`에 있다.

## 남은 작업(예정)

- 스크린샷 캡처(실기기) 후 report-014 이미지 교체, QA6 실기기 실측 반영
- 검토 피드백 반영 후 최종 PPT 조립

> 보존 문서 = `archive/` (사유표 = `archive/README.md`).
