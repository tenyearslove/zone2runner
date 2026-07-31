# ADR-026: ML Kit GenAI Rewriting/Summarization 채택 (코칭 톤 / 리포트 요약)

- **상태**: Accepted
- **부분 폐지(2026-07-23, adr-028)**: 코칭 경로의 Rewriting(톤 재작성 1순위)은 폐지 — 규칙 문장 세트를 전제하기 때문. Summarization(리포트 요약)은 유지.
- **날짜**: 2026-07-14
- **결정자**: 1인 설계

## 맥락

우리는 이미 Gemini Nano(ML Kit GenAI **Prompt API**)로 코칭 문구를 표현한다(adr-007, adr-002: 규칙이 방향 확정, LLM은 표현만, DirectionGuard + 템플릿 폴백). 리서치(`arch/archive/research-gemini-nano-ondevice-capabilities.md`)로 같은 온디바이스 Nano의 **과제형 API**(Rewriting/Summarization 등)를 확인했다. 두 가지가 우리 아키텍처에 잘 맞는다.
- **Rewriting**: 문장의 '톤'만 바꾼다 → "내용은 규칙, LLM은 표현만" 모델을 자유 프롬프트보다 **더 결정론적으로** 강화.
- **Summarization**: 실제 도출 사실을 불릿 요약 → "세밀한 관측 리포트 + 없는 숫자 안 만듦" 제1원칙에 부합.
둘 다 온디바이스(무클라우드), 한국어 지원, 세션 경계 배치/포그라운드로 사용 가능.

## 결정

### 대안 비교

| 기준 | A. Rewriting(톤)+Summarization(요약) 채택 | B. Prompt만 유지(현행) | C. 구조화 출력 API 대기 |
|------|------|------|------|
| 방향 잠금 | ★ 강함(내용=규칙 문장에서 출발) | 보통(프롬프트에 방향 주입) | 강함(스키마 강제) |
| 결정론/제어 | 높음(고정 톤/불릿) | 낮음(자유 생성) | 매우 높음 |
| 없는 숫자 방지 | 강함(입력 사실만 요약) | 보통(프롬프트 규율) | 강함 |
| 지금 가용성 | 가능(beta) | 가능 | ✗ 온디바이스 "예정"(I/O 2026) |
| 리스크 | 중(beta 변동, 페르소나 톤 불완전) | 낮음 | 낮음(단 아직 못 씀) |

### 대안 A: Rewriting + Summarization 채택 (택함)
- 장점: 우리 "약한 LLM을 잘 감싼 시스템" 서사 강화(내용=규칙, LLM=톤/요약만), 자유 프롬프트보다 결정론적, 제1원칙(없는 숫자 금지)과 정합, 지금 구현 가능.
- 단점: 전부 beta(시그니처 변동), Rewriting 고정 톤이 4페르소나를 완벽히 못 담음(스파르타 근사), 플래그십+AICore 한정(폴백 필수), Summarization은 입력이 짧으면 이득 적음.

### 대안 B: Prompt만 유지
- 장점: 무변경/무리스크.
- 단점: 자유 생성이라 방향 이탈 여지, 리서치가 확인한 더 나은 정합 카드를 안 씀.

### 대안 C: 구조화 출력 대기
- 장점: JSON 스키마로 파싱 제거, 가장 강한 제어.
- 단점: **온디바이스 미출시**(I/O 2026 "예정") — 지금 채택 불가. → 별도 감시 항목으로 남김(출시 시 재검토).

### 채택: 대안 A
지금 쓸 수 있고 우리 원칙/서사에 가장 잘 맞는다. 단, **모든 Nano 경로는 가용성 체크 + 무손상 폴백**을 강제하고, 구조화 출력은 출시되면 재검토한다.

## 구현 (as-built)

- **의존성**: `com.google.mlkit:genai-rewriting:1.0.0-beta1`, `genai-summarization:1.0.0-beta1`(common→beta3).
- ~~**`NanoRewriter`**(coaching)~~ (adr-028로 삭제됨 — 이하 역사 기록): `Rewriting.getClient` + `checkFeatureStatus()`(AVAILABLE만, 다운로드 트리거 안 함) + `runInference`(bounded get 6s). 페르소나→톤 매핑(정직한 한계): 친절/다정→FRIENDLY, 차분→PROFESSIONAL, 스파르타→SHORTEN(근사), 그 외 REPHRASE. 미가용/실패/빈 결과 시 **원문 반환**.
- **`NanoSummarizer`**(report): `Summarization.getClient`(ARTICLE/THREE_BULLETS/KOREAN) + `runInference`(bounded get 15s). 입력=`SessionExplainer.article`(facts + 분석 지표, 실제 도출값만). 입력<200자/미가용/실패 시 **null → 폴백**.
- **배선**:
  - ~~`LlmCoach.say`: 1순위 = NanoRewriter 톤 재작성~~ → adr-028로 폐지. 현행 = LLM 직생성 1순위 + 단어 수준 폴백(spec-028).
  - 리포트 세션 스토리: **1순위 = NanoSummarizer 불릿 요약** → 실패 시 **기존 Prompt freeform** → 규칙 facts(항상 저장된 폴백).

## 결과

- (+) 코칭 표현이 "규칙 문장의 톤만 변형"이라 방향 이탈 여지 축소, 리포트 스토리가 실제 사실의 요약이라 지어낸 숫자 원천 차단. 강의 AI System의 "출력 가드레일 + HOTL 폴백" 서사 강화.
- (+) 폴백 체인으로 **최악의 경우 = 현행 동작**(Nano 미가용 기기 포함).
- (−) beta API 변동 리스크 → 버전 고정 + `NanoRewriter/NanoSummarizer` 포트로 격리(교체 지점 단일화).
- (−) 페르소나 톤이 완벽하지 않음(스파르타), Summarization은 EN/JA/KO + 입력 길이 의존.
- 검증: app 유닛테스트 그린 + `assembleDebug` 성공. 실기기 Nano 동작은 사용자 실주행 확인 대기.

## 관련 문서
- 리서치: `arch/archive/research-gemini-nano-ondevice-capabilities.md`
- Spec: `spec/spec-005-llm-coaching-generation.md`(코칭), `spec/spec-023`(설명/리포트 스토리)
- ADR: `arch/adr-007`(Nano 실기기 검증), `arch/adr-002`(방향 잠금/가드레일), `arch/adr-025`(AI≠NN, 표현=LLM)
