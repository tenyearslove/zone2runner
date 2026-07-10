# framework/ — 인증 강의 프레임워크 정본 (영구 보존)

이 폴더는 **인증 강의에서 학습한 AI 시스템 설계/품질 프레임워크**를 담는다. 프로젝트 방향은 바뀌어도 이 프레임워크는 **계속 정본**이다(설계/QA의 기준). 다른 문서(spec/arch)는 이 프레임워크에 **매핑해서** 표현한다.

> ★ CLAUDE.md의 "인증 강의 프레임워크 준수" 규칙이 이 폴더를 가리킨다. 여기 내용과 어긋나는 설계/QA는 고친다.

## 구성

| 항목 | 내용 |
|------|------|
| `ai-system-and-quality.md` | 강의 개요 2종(0-AI시스템, 1-AI품질) 재구성 요약 — **AI System 아키텍처**(운영/개발 서브시스템, 입출력 가드레일, HITL/HOTL, 설명/분석 서비스, Retraining) + 품질모델 계보 + **AI 8대 QA** 개관 |
| `ai-8-qa.md` | QA별 상세 9종 재구성 — 각 QA의 강의 정의(ISO 근거)/유형/평가지표/**6요소 QA 시나리오** + 우리 프로젝트 매핑 |
| `lecture-pdfs/` | 강의 원본 PDF 11종(0/1 개요 + 2/4/5/6/7/8/9/10 QA별 + 강건성). **원본이 최종 정본** |
| `assignment/` | 과제 규정/샘플 — 인증 과제 샘플 PDF, 예시 과제(pptx) 3종, 우리 과제계획서(docx) |

## 두 가지 필수 규칙 (과제 규정)

1. **AI 시스템 표현**: 우리 시스템을 강의의 AI System 아키텍처(AI Operation Subsystem + AI Development Subsystem + Model/Container Registry + 입출력 가드레일 + HITL/HOTL + Retraining 루프)에 **매핑해 표현**한다. → `ai-system-and-quality.md §1-3`.
2. **AI QA 선정**: 우리 품질속성은 **AI 8대 QA**(기능정확성/강건성/프라이버시/공정성/기능적응성/제어가능성/설명용이성/수행효율성) 중에서 선택. 규정 = 품질속성 4개 이상, 그중 AI 특화 2개 이상 필수. QA 명칭은 8대 공식 용어를 그대로 쓴다. → `ai-8-qa.md`.

## 관리 원칙

- **정본 위치**: 강의 프레임워크 지식은 여기(framework/)에만 둔다. spec/arch/report에 중복 서술하지 않고 여기를 참조한다.
- **프로젝트 매핑의 소재**: 우리 QA 선정 결과는 `spec/spec-002`, 시스템 아키텍처 매핑은 `arch/architecture-overview.md`. framework/는 "강의가 무엇을 요구하나"(불변), spec/arch는 "우리가 어떻게 따랐나"(가변).
