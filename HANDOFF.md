# HANDOFF — 작업 이어가기

이 문서는 다른 환경(집 등)에서 repo를 clone해 작업을 이어가기 위한 컨텍스트다. Claude Code 로컬 메모리(`~/.claude/`)는 git으로 전파되지 않으므로, 핵심 컨텍스트를 여기에 담는다.

> **새 세션에서 이어가는 법**: Claude에게 "HANDOFF.md 읽고 이어가자"라고 하면 된다.

최종 갱신: 2026-07-01

---

## 1. 프로젝트 본질 (스코프 판단 기준)

- zone2runner = 상용화가 아니라 **AI 설계 교육과정 수료(자격인증) 프로젝트**.
- 규모: 원래 1인 3달 난이도를 **Claude 활용해 1달**에 완주 목표.
- **핵심 산출물 = 설계 문서**(spec/arch/report). 설계 능력 입증이 최우선. 구현은 부차적 — 설계가 동작함을 보이는 **얇은 PoC 한 조각**이면 충분.
- **교육과정 요건**: 작은 부분이라도 **실제 NN을 설계/학습/모델 산출**해야 함.
- 과설계 경계. 상세는 "구현 시 확정"으로 명시적 유보(유보 자체가 설계 판단).

## 2. 확정 사항

- **대상 기기**: Galaxy Watch 8 + Galaxy S26 Ultra (본인 보유). 과제계획서의 "저가형 워치"는 올드버전 — 무시. 정본은 repo의 spec/arch/CLAUDE.md.
- **주 목적**: AI 기반 개인화 Zone 2 판정. "판정을 개인 신체능력으로 한다"가 곧 개인화.

## 3. 핵심 설계 결정 (DP) — 완료

| DP | 결정 | 문서 |
|:---:|------|------|
| DP0 | Watch-Phone Hybrid (Watch=수집, Phone=AI) | arch/adr-001 |
| DP1 | 개인화 Zone2 판정 = 규칙 baseline + 개인화 하이브리드 | arch/adr-003 |
| DP2 | LLM 코칭 = 규칙이 방향 결정 + LLM 표현 + 출력 가드 | arch/adr-002 |
| DP3 | 개인화 경계 추정 = 공식 prior + 온라인 Bayesian (NN 아님) | arch/adr-004 |
| DP4 | Zone2 판정기 = 다변량 MLP 분류기 (실제 NN) | arch/adr-005 |

### AI 컴포넌트 3개 (혼동 주의)
1. **다변량 MLP 판정기 (DP4)** — 교육과정용 실제 NN. HR정규화/페이스/SPM/디커플링/경사 → 미달/유지/초과 3분류. PyTorch 학습 → TFLite 온디바이스.
2. **Bayesian 개인화 경계 (DP3)** — 딥러닝 아님. 공식=prior, 세션별 물리관측으로 갱신. float 산술.
3. **On-device LLM 코칭 (DP2)** — Galaxy AICore 상정.

### 왜 NN(MLP)인가 — QA 근거 (핵심, "AI과제라서" 아님)
단일 HR 임계값(선형·단변량)은 오르막(HR지연)/Cardiac Drift/노이즈 다신호 상황에서 오판 → QA1 기능정확성/계획지표 85%/QA2 강건성을 규칙으론 못 채움 → 비선형 다변량 함수근사기(MLP)가 필요. adr-005에 QA→메커니즘 표. adr-003이 순수ML 뺐던 근거(콜드스타트/라벨/강건/검증)는 하이브리드가 해소(라벨=시뮬레이터, 콜드스타트=규칙폴백, 강건=노이즈증강+가드).

## 4. 문서 현황

- 완료(커밋됨): spec-001(FR), spec-002(QA+Utility Tree+ASR→DP), adr-001~005, spec-003(HR파이프라인), spec-004(개인화 모델 상세+검증 시뮬레이터), spec-005(LLM 코칭), spec-006(MLP 설계/학습/평가/배포), arch/architecture-overview(진입 문서), report-001(슬라이드1~2).
- **읽기 진입점**: arch/architecture-overview.md → adr-005(DP4 NN 근거) → spec-006(MLP 상세) → spec-004(개인화).

## 5. 진행/다음 할 일

**완료 (2026-07-02): Zone2 판정 MLP PoC** — `ml/` (simulator.py, train_mlp.py, README.md, EXPERIMENT_LOG.md)
- 결과: 규칙 0.486 → +MLP(DP4) 0.726 → +개인화(DP3) 0.743. 방향 정확성 0.995(QA1 초과), 노이즈 강건 0.702.
- 3분류 85%는 미달(경계 밀집 인접혼동) — 상세/튜닝여정은 `ml/EXPERIMENT_LOG.md`.
- 실행: `python3 -m venv .venv && ./.venv/bin/pip install -r ml/requirements.txt` 후 `./.venv/bin/python ml/train_mlp.py`

**다음 후보**:
1. 3분류 정확도 개선(노이즈 현실화/지표 재정의) 또는 지표를 방향정확성/이진으로 확정.
2. Bayesian 개인화 추정기 수렴 검증 하네스 (spec-004 §8).
3. 학습 모델 TFLite 변환 → app/ 온디바이스 통합.
4. 남은 문서: 최종 Architecture 뷰 상세, 보고서 확장(구현·검증/결론).

## 6. 작업 방침 (사용자 요청)

- **자율 진행**: 매 단계 묻지 말고 "결정 → 실행 → 커밋 → 보고". git이 안전망.
- 멈추고 물을 예외 2가지만: (a) 사용자만 아는 정보로 추측 불가한 것, (b) 되돌리기 어렵거나 외부로 나가는 것(원격 push 등).

## 7. 도구/스킬 메모

- 스킬: `/adr`, `/spec`, `/report` (`.claude/skills/{name}/SKILL.md` 구조).
- 문서 규칙: 번호 3자리, 구체적 제목, ADR은 대안 2~3개 필수, `·` 기호 금지(열거는 `,`/`/`). CLAUDE.md 참조.
- PDF 텍스트 추출 필요 시 `pip install pypdf`. docx는 macOS `textutil`.
