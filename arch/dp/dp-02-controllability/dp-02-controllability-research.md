# DP-02 리서치 노트 — 약한 LLM 거버넌스(제어가능성) 근거

> DP-02 본문(`dp-02-controllability-decision.md`)의 근거 문헌 정리. 2026-07-23 웹 리서치 + 강의 정본(framework/) 대조.

## 1. 제어가능성의 공식 정의

- **ISO/IEC 25059:2023 (AI 품질 모델, 25010의 AI 확장)**: User controllability = "사용자가 AI 시스템의 동작에 **적시에 적절히 개입**할 수 있는 정도(degree to which a user can appropriately intervene in an AI system's functioning in a timely manner)". Intervenability(개입가능성)도 AI 특화 특성으로 포함.
  - https://cdn.standards.iteh.ai/samples/80655/168addf09e0a4d8181b9172dc7404fab/ISO-IEC-25059-2023.pdf
  - ISO/IEC 42105(작성 중 계열)가 human oversight(사람의 감독/개입) 지침으로 이를 확장.
- **강의 정본(framework/ai-8-qa.md §3-3)**: 제어 주체(인간/외부 에이전트/상위 앱)가 개입해 목적 완수/안전/기술신뢰를 회복하는 통제권. 제어기능 3유형 = **교정(Correction) / 보완(Compensation) / 안전전환(Fail-safe)**. **입출력 가드레일 조정 자체가 제어 행위**로 명시. 측정 = 제어정확성(안전상태 전이 성공률), 제어효율성(명령→구동 지연).

→ DP-02의 평가 기준: "개입 지점이 구조에 존재하는가, 적시에 작동하는가, 작동했음을 확인할 수 있는가".

## 2. LLM 가드레일 — 계층 방어가 표준 관행

- 실무/서베이 공통 구조: **입력 검증(모델이 보기 전) / 생성 중 제약 / 출력 필터(생성 후) / 구조적 격리(architectural containment — 모델이 할 수 있는 일 자체를 제한)**의 계층 방어.
  - 실무 정리: https://leanware.co/insights/llm-guardrails , https://www.kalviumlabs.ai/blog/guardrails-for-llm-applications/
  - 학술 서베이: LLM Safety survey — https://arxiv.org/pdf/2412.17686 , https://arxiv.org/pdf/2407.18369 , Springer ML 서베이 — https://link.springer.com/article/10.1007/s10994-026-07060-8
- 시사점: 단일 장치(프롬프트 지시 하나, 필터 하나)가 아니라 **여러 층의 독립 장치**가 표준. 특히 "구조적 격리"(모델의 권한 자체를 좁힘)가 가장 강한 층으로 분류됨 — 1안의 "LLM=표현만" 격리가 여기 해당.

## 3. 판단을 LLM에 단독 위임하지 말라는 문헌

- LLM-as-Judge 실무/연구 공통 권고: 고영향 판단에 **LLM을 단독 결정자로 쓰지 말 것**, 결정론적 규칙/전용 분류기가 가드레일의 근간으로 여전히 필수, 고영향 결정에는 사람 검토 병행.
  - https://wandb.ai/site/articles/exploring-llm-as-a-judge/ , https://www.mindstudio.ai/blog/llm-as-judge-agent-safety-pattern
  - 판정자 자체의 강건성 한계(공격/불안정): https://arxiv.org/html/2503.04474v1 , https://arxiv.org/abs/2512.15617
- 시사점: 2안(LLM 판단 위임)의 통제는 "LLM이 규정을 따를 것"이라는 가정에 의존하는데, 그 가정 자체가 문헌상 보장되지 않음.

## 4. 소형/온디바이스 모델의 지시 이행 한계

- 지시 이행(instruction following)은 소형 모델의 알려진 약점: 7B~70B급 다수 모델이 지시 이행률 60% 미만 사례 보고(도메인 벤치마크), "LLM은 단순 지시도 자주 어긴다"는 관찰.
  - https://arxiv.org/pdf/2505.22787 (계열 벤치마크), 소형 모델 환각 관련: https://www.intel.com/content/www/us/en/developer/articles/technical/do-smaller-models-hallucinate-more.html
- 환각은 모델 크기와 단순 비례하지 않으나, **지시(형식/금지어/방향)를 지키는 능력**은 소형에서 불안정 — 온디바이스 Nano급에 "프롬프트 규정 준수"를 통제 수단으로 삼기 어려운 근거.
- 이 저장소의 실측 정합: adr-007(Nano 실기기 검증 POC), spec-005 AC(방향 일치율 95% 목표 — 가드레일 전제), spec-027 감사 기록의 폴백 사유 분포(실데이터 축적 중).

## 5. 안전전환(fail-safe)과 무손상 폴백

- graceful degradation 관행: 모델 미가용/실패 시 기능을 멈추지 않고 하위 경로로 연속 제공. 이 시스템의 as-built: 가용성 체크(AVAILABLE만) + 타임아웃 bounded + 실패 시 원문/템플릿 반환(무손상), 안전 권고는 LLM 미경유(spec-008).
- 대비점: 판단을 LLM에 위임하면(2안) LLM 실패 시 **대체할 판단 주체가 없어** 폴백이 "침묵 또는 지연"이 됨 — 안전전환이 구조적으로 어려움.

## 6. 감사가능성(HOTL)

- human-on-the-loop: 사람이 매 출력을 검수하지 않되(실시간 불가), **사후에 개입 근거를 확인**할 수 있어야 함. ISO 계열 human oversight 논의: https://arxiv.org/pdf/2510.09090
- 이 시스템의 구현: spec-027 — 모든 LLM 호출의 경로/폴백 사유/방향 기각이 세션마다 저장(리포트 "LLM 사용" 카드) = 가드레일 동작의 사후 감사 수단.

## 7. DP-01과의 관계

- DP-01(설명용이성)과 같은 아키텍처의 다른 QA 단면: DP-01은 "설명이 실제 판단 근거와 일치하는가(충실도)", DP-02는 "출력을 통제/개입할 수 있는가(통제권)". 규칙이 판단을 확정하는 구조가 두 QA를 동시에 떠받침 — 강의 정본 기준으로 출력 가드레일(DirectionGuard)은 **제어가능성에 귀속**(설명의 충실성은 그 결과).
