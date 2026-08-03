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
      dp-{NN}-{qa}-adopted-simple.puml/.png    # 채택안 추상(실제 아키텍처를 DP 축이 드러나게 개별화)
      dp-{NN}-{qa}-detail.puml/.png            # 채택안 상세(그 DP 경로를 실제 클래스 단위로 전개)
      dp-{NN}-{qa}-counter-simple.puml/.png    # 카운터 축약(PPT용)
      dp-{NN}-{qa}-counter-detailed.puml/.png  # 카운터 상세
```

- **`{NN}`** = 보고서 제시 순서(2자리, `01`,`02`,…). **`{qa}`** = 이 DP가 드러내는 8대 QA의 영문 슬러그(`explainability`, `controllability`, …).
- **파일 kind**: `decision` / `research` / `script` / `counter-catalog`. 필요 시 확장(`script-counter`, `qa-eval` 등)하되 `dp-{NN}-{qa}-{kind}` 패턴을 지킨다.
- **이미지**: DP 폴더 안 `images/`에. 카운터(2안) = `dp-{NN}-{qa}-counter-{simple|detailed}`, 채택안(1안) = `dp-{NN}-{qa}-adopted-simple`(추상)/`dp-{NN}-{qa}-detail`(상세). 항상 **상세+축약 쌍**을 유지한다(CLAUDE.md 도식 규칙). 적용 현황: dp-01~05 전 DP 완비(2026-08-03).
- **금지기호 `·` 사용 안 함**(CLAUDE.md). 열거는 `/`,`,`.

## ★ 채택안(1안)의 실체는 일반 아키텍처 — DP 폴더엔 그 DP 관점의 도식만 둔다

각 DP의 **채택안(1안)은 곧 이 시스템의 실제 아키텍처**다. 따라서 1안의 서술과 원본 도식은 DP 폴더에 **중복 저장하지 않고** 일반 시스템 아키텍처를 참조한다:
- 1안 원본 도식(전체 시스템) = `arch/diagrams/02-component-cnc.png`(상세) / `02b-component-cnc-simple.png`(축약)
- 1안 컴포넌트 서술 = `arch/component-catalog.md`

다만 **DP 본문에 싣는 1안 그림은 이 원본을 그 DP의 QA 축이 드러나게 개별화한 것**(`images/*-adopted-simple.png`)이다 — 채택안 그림이 DP마다 똑같아 보이던 문제를 없애기 위함이며, 다섯 그림은 서로 다른 시스템이 아니라 **한 아키텍처의 다섯 단면**이다. 그 경로를 실제 클래스 단위로 전개한 상세본(`images/*-detail.png`)이 짝을 이룬다.

즉 DP 폴더에는 **그 DP 전용 자산**(DP 본문/리서치/대본 + 채택안 도식 2종 + 카운터 2안 도식/카탈로그)만 둔다. 1안의 컴포넌트 서술을 폴더에 복제하지 않고 카탈로그를 참조하는 비대칭이 곧 사실을 반영한다 — 1안은 실제 시스템, 2안은 공정 비교용 가정 설계.

## 인덱스

| DP | QA(정체성) | 상태 | 결정(채택) | 폴더 |
|----|-----------|------|-----------|------|
| DP-01 | 설명용이성(Explainability) | v4 (2026-08-03, 리뷰 반영) | 1안 = 규칙/통계 기반 개인화(intrinsic+provenance, LLM=verbalizer) | `dp-01-explainability/` |
| DP-02 | 제어가능성(Controllability) | v3 (2026-08-03, 리뷰 반영) | 1안 = 규칙 확정 + LLM 표현 한정(구조적 격리 + 출력 가드 3종 + 단어 폴백 + 안전 분리 + HITL/HOTL/감사) | `dp-02-controllability/` |
| DP-03 | 기능적응성(Adaptability) | v2 (2026-08-03, 리뷰 반영) | 1안 = 온라인 베이지안 개인 적응(실라벨 즉시 갱신 + 불확실도 + 이동 한도 + 세션 간 warm-start) | `dp-03-adaptability/` |
| DP-04 | 강건성(Robustness) | v2 (2026-08-03, 리뷰 반영) | 1안 = 계층 방어 판정(입력 가드레일 + 이중 기준/히스테리시스 + 통계 평탄화 k·σ + 소스 폴백) | `dp-04-robustness/` |
| DP-05 | 테스트가능성(Testability) | v2 (2026-08-03, 리뷰 반영) | 1안 = 소스 추상화 + 폐루프 시뮬 계측(RunSource 교체 + 참임계 가상 러너 + 순수 로직 자동 테스트) | `dp-05-testability/` |

- **보고서 원고/HTML**: 각 DP의 PPT 원고 = `report/report-008`(DP1)~`report-012`(DP5). HTML 렌더 = `docs/`(GitHub Pages, 인덱스 `docs/index.html`).
- **DP 도식 렌더 환경**: plantuml 1.2025.2 + smetana 레이아웃(graphviz 불필요), 폰트 = 렌더 머신의 한글 폰트(Mac=AppleGothic, Windows=Malgun Gothic — puml 안 `skinparam defaultFontName`이 그 머신 기준으로 박혀 있으니 수정 시 유지). 명령 = `java "-Dfile.encoding=UTF-8" -jar plantuml.jar -charset UTF-8 -Playout=smetana -tpng <files>`.

> 관련: `framework/ai-8-qa.md`(8대 QA), `framework/assignment/`(과제 규정 = QA 4개+ / AI 특화 2개+), `arch/diagrams/`(일반 아키텍처), `arch/component-catalog.md`(우리 컴포넌트).
