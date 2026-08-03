# DP(설계 문제) 설계문서 — 관리 규칙 + 인덱스

인증 과제의 각 **DP(Design Problem)** = "문제점 하나 + 그 해결 설계 2안 비교 + 결정"을 담는 자족적 설계문서 묶음이다. DP별로 폴더 하나에 문서/도식/대본을 모아 **따로 관리**한다.

## 폴더/네이밍 규칙

```
arch/dp/
  README.md                                    # (이 파일) 규칙 + 인덱스
  dp-{NN}-{qa}/                                # DP 하나 = 폴더 하나
    dp-{NN}-{qa}-decision.md                   # DP 본문: 문제정의 → 2안 비교 → 결정표 → 근거
    dp-{NN}-{qa}-research.md                   # 근거 리서치 노트(문헌/출처)
    dp-{NN}-{qa}-script.md                     # 채택안(1안) 발표 대본(쉬운 풀이)
    dp-{NN}-{qa}-counter-catalog.md            # 카운터(2안) 컴포넌트 카탈로그
    images/
      dp-{NN}-{qa}-counter-simple.puml/.png    # 카운터 축약(PPT용)
      dp-{NN}-{qa}-counter-detailed.puml/.png  # 카운터 상세
```

- **`{NN}`** = 보고서 제시 순서(2자리, `01`,`02`,…). **`{qa}`** = 이 DP가 드러내는 8대 QA의 영문 슬러그(`explainability`, `controllability`, …).
- **파일 kind**: `decision` / `research` / `script` / `counter-catalog`. 필요 시 확장(`script-counter`, `qa-eval` 등)하되 `dp-{NN}-{qa}-{kind}` 패턴을 지킨다.
- **이미지**: DP 폴더 안 `images/`에, `dp-{NN}-{qa}-{design}-{variant}` 로. `{design}`=`counter`(2안), `{variant}`=`simple`|`detailed`. 항상 **상세+축약 쌍**을 유지한다(CLAUDE.md 도식 규칙).
- **금지기호 `·` 사용 안 함**(CLAUDE.md). 열거는 `/`,`,`.

## ★ 채택안(1안)은 여기 두지 않는다 — 참조한다

각 DP의 **채택안(1안)은 곧 이 시스템의 실제 아키텍처**다. 따라서 1안의 도식/카탈로그는 DP 폴더에 **중복 저장하지 않고**, 일반 시스템 아키텍처를 참조한다:
- 1안 도식 = `arch/diagrams/02-component-cnc.png`(상세) / `02b-component-cnc-simple.png`(축약)
- 1안 컴포넌트 서술 = `arch/component-catalog.md`

DP 폴더에는 **그 DP 전용 자산**(DP 본문/리서치/대본 + 카운터 2안 도식/카탈로그 + **채택안의 DP 관점 개별화 도식과 하위 모듈 상세 도식**)만 둔다. 채택안 도식 2종 = `-adopted-simple`(추상, DP 축 강조) / `-detail`(해당 블록을 실제 클래스 단위로 전개). 이 비대칭이 곧 사실을 반영한다 — 1안은 실제 시스템, 2안은 공정 비교용 가정 설계.

## 인덱스

| DP | QA(정체성) | 상태 | 결정(채택) | 폴더 |
|----|-----------|------|-----------|------|
| DP-01 | 설명용이성(Explainability) | v3 (2026-07-31) | 1안 = 규칙/통계 기반 개인화(intrinsic+provenance, LLM=verbalizer) | `dp-01-explainability/` |
| DP-02 | 제어가능성(Controllability) | v2 (2026-07-31, 사용자 검토 대기) | 1안 = 규칙 확정 + LLM 표현 한정(구조적 격리 + 출력 가드 3종 + 단어 폴백 + 안전 분리 + HITL/HOTL/감사) | `dp-02-controllability/` |
| DP-03 | 기능적응성(Adaptability) | v1 (2026-07-31, 사용자 검토 대기) | 1안 = 온라인 베이지안 개인 적응(실라벨 즉시 갱신 + 불확실도 + 이동 한도 + 세션 간 warm-start) | `dp-03-adaptability/` |
| DP-04 | 강건성(Robustness) | v1 (2026-07-31, 사용자 검토 대기) | 1안 = 계층 방어 판정(입력 가드레일 + 이중 기준/히스테리시스 + 통계 평탄화 k·σ + 소스 폴백) | `dp-04-robustness/` |
| DP-05 | 테스트가능성(Testability) | v1 (2026-07-31, 사용자 검토 대기) | 1안 = 소스 추상화 + 폐루프 시뮬 계측(RunSource 교체 + 참임계 가상 러너 + 순수 로직 자동 테스트) | `dp-05-testability/` |

- **보고서 원고/HTML**: 각 DP의 PPT 원고 = `report/report-008`(DP1)~`report-012`(DP5). HTML 렌더 = `docs/`(GitHub Pages, 인덱스 `docs/index.html`).
- **DP2~DP5 카운터 도식 렌더 환경**: plantuml 1.2025.2 + smetana 레이아웃(graphviz 불필요), 폰트 = 렌더 머신의 한글 폰트(Mac=AppleGothic, Windows=Malgun Gothic).

> 관련: `framework/ai-8-qa.md`(8대 QA), `framework/assignment/`(과제 규정 = QA 4개+ / AI 특화 2개+), `arch/diagrams/`(일반 아키텍처), `arch/component-catalog.md`(우리 컴포넌트).
