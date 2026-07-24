# 리서치(러프): 설명용이성 DP — 설계에 의한 설명(intrinsic) vs 사후 설명(post-hoc)

- **상태**: 러프 리서치 노트(DP 탐색) — 정식 DP 설계문서 전 단계
- **날짜**: 2026-07-14
- **관련**: `spec/spec-002`(QA — 설명용이성=정체성), `spec/spec-023`(설명 서비스), `spec/spec-001`(제1원칙: 없는 숫자 금지), `arch/adr-025`(AI≠NN), `framework/ai-8-qa.md`
- **방법**: 별도 리서치 에이전트(WebSearch/WebFetch), 공식/1차 문헌 위주.

## 왜 이 DP인가

우리 앱의 **정체성 QA = 설명용이성**. 그리고 우리 제1원칙("없는 숫자 안 만듦")이 XAI의 핵심 긴장과 정확히 맞닿는다. 그래서 설명용이성을 전면에 세우는 DP를 잡으면, 우리 강점을 그대로 심사 서사로 만든다.

## 문제 정의 (DP)

코칭 AI는 달리는 동안 참고할 안내(존 판정, 개인 경계 적응, 코칭 큐)를 건넨다 — 지시가 아니라 안내다. 이 앱의 지향은 판단마다 "왜"를 풍부하게 붙이는 것인데, XAI의 함정이 여기 있다: **실제 판단과 어긋난 설명(plausible-but-unfaithful)은 잘못된 확신(false confidence)을 만든다.** 참값이 없어 설명이 "옳다"는 보장은 불가능하므로, 설계 문제는 "온디바이스에서, 비전문가에게, **충실도(faithfulness) 있게**(실제 판단 근거와 일치하게) 모든 결정을 설명하기".

- **결정축 = 충실도(fidelity)**: 그럴듯함(plausibility) ≠ 충실함(faithfulness). 설명을 풍부하게 주는 것이 정체성인 시스템에선, 설명이 실제 판단 근거와 일치하는지가 지배 축이다. (Rudin 2019; 헬스-XAI 리뷰)

## 두 가지 설계 (비교)

### 설계 A(채택): 설계에 의한 설명 (intrinsic / glass-box + provenance)
투명한 규칙 판정 + 해석가능한 베이지안 경계(prior/σ 노출) + 모든 수치의 출처 추적(종류 A 도출 / B 학습 / C 설계선택 = data lineage) + **LLM은 이미 정해진 사실을 말로만 옮김(verbalizer)** + 출력 가드레일/템플릿 폴백.
- 판단이 투명해 근거가 **추적 가능** → 설명이 실제 로직과 어긋날 여지가 작음(사후 근사가 아님).
- QA: **설명용이성↑↑**, 제어가능성↑(검증/반박 가능), 강건성↑(안정적 설명, 가드로 LLM 출력 한정), 수행효율성↑(사후 계산 불필요). 정확도 비용 작음(의미있는 저차원 특징 — Rashomon, "트레이드오프는 신화").

### 설계 B(대안/포일): 블랙박스 + 사후 설명 (post-hoc)
학습된 불투명 모델이 결정/예측 → 사후에 SHAP/LIME/saliency 또는 LLM 이유생성으로 설명.
- QA: 기능정확성 잠재↑, 기능적응성↑. 하지만 **설명용이성↓**(그럴듯하나 충실하지 않음 = 사후 설명은 원모델의 근사), 제어가능성↓(불투명, 반박 어려움), 강건성↓(attribution 불안정 + LLM 컨패뷸레이션/세탁), 수행효율성↓(사후 계산 비용). 우리 제1원칙 위배.

### 결정
우리 특징(HR/페이스/케이던스/경사)은 의미있고 저차원이라 설계 B의 정확도 우위 논거가 약하고(트레이드오프 신화), 코치의 정체성 QA가 설명용이성이라 **충실도가 지배** → **설계 A가 원칙적 선택, 설계 B는 포일**.

## 우리 시스템 = 이미 설계 A의 구현체

| 기능 | XAI 범주 |
|------|----------|
| 규칙 존 판정(미달/유지/초과) | intrinsic(glass-box) — 규칙이 곧 추론, 손실 없이 충실 |
| 베이지안 경계(prior/σ 노출, 말하기 테스트 라벨로 갱신) | intrinsic + provenance |
| "없는 숫자 금지"(종류 A/B/C 출처 선언) | **provenance / data lineage** = 값의 출처를 구조적으로 보장 |
| 코칭: 규칙이 방향/사실 확정 + LLM은 표현만 + 가드레일/폴백 | intrinsic 결정 + **LLM = verbalizer(사실 표현, 이유 생성 아님)** |
| 코칭 reason 태그, 설명 서비스(spec-023), 지표 터치 설명 팝업 | intrinsic replay + **layered explanation**(헬스-XAI 권장 패턴) |

→ 우리 설명은 전부 **intrinsic + provenance**, LLM이 설명에 닿는 유일한 지점도 **"사실 verbalize"로 제한**. 블랙박스 사후 설명은 어디에도 없음 = Rudin이 고위험 결정에 처방한 바로 그 방향. 웨어러블 XAI 실무는 88%가 post-hoc이고 충실도 검증이 부족(리뷰) — 우리가 그 공백을 메움.

## AI System 매핑

강의의 **설명 서비스(Explanation Service)** 컴포넌트 + provenance(데이터 lineage) + 출력 가드레일 + HITL(말하기 테스트 라벨 = 사용자 반박 채널).

## 정직한 한계

- **intrinsic=faithful은 "설명이 모델과 일치"를 보장하지, 모델이 옳음을 보장하지 않음.** 정확성은 provenance + 사용자 반박(말하기 테스트)이 커버.
- **Rudin의 "트레이드오프는 신화"는 강한 주장** — 우리처럼 의미있는 저차원 특징엔 성립, raw 지각 데이터(이미지/음성)엔 블랙박스가 우세. "우리 문제엔 대체로 성립"으로 신중히.
- **LLM verbalizer도 phrasing 왜곡 가능** — 결정의 충실도 문제는 없애지만 표현 왜곡은 남음 → 가드레일이 실질적 역할(형식이 아님).

## 핵심 문헌

- Rudin 2019, *Stop Explaining Black Box ML Models for High-Stakes Decisions*, Nature Machine Intelligence — https://transferlab.ai/refs/rudin_stop_2019/
- Doshi-Velez & Kim 2017, *Towards a Rigorous Science of Interpretable ML* — https://arxiv.org/abs/1702.08608
- Molnar, *Interpretable Machine Learning*(intrinsic/post-hoc 분류) — https://christophm.github.io/interpretable-ml-book/
- Faithfulness vs Plausibility — https://arxiv.org/pdf/2402.04614 / Assessing Fidelity — https://arxiv.org/abs/2311.01961
- LLM 자기설명 충실도(컨패뷸레이션/세탁) — https://arxiv.org/pdf/2506.09277 / https://arxiv.org/html/2605.27879v1
- 웨어러블 XAI 체계적 리뷰(JMIR 2024, post-hoc 88%/충실도 검증 부족) — https://pmc.ncbi.nlm.nih.gov/articles/PMC11707450/
- DARPA XAI("appropriately trust") — https://www.darpa.mil/research/programs/explainable-artificial-intelligence
- provenance/lineage as trust — https://techstrong.ai/articles/provenance-and-traceability-in-ai-ensuring-accountability-and-trust/

## 다음 단계

- 이 DP를 정식 설계문서로(report-006 형식: mermaid 2안 비교표 + QA 별점 + Trade-off + 채택안). 리포트는 아직 이르니 spec/arch에 먼저.
- 제어가능성 DP(약한 LLM 거버넌스)와 짝지어 "설명용이성 + 제어가능성" 두 AI 특화 QA 축 확정.
- DP-①(경계 추정)은 후보 유지.
