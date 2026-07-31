# 아키텍처 다이어그램 (PlantUML)

우리 시스템을 표준 SW 아키텍처 표기법으로 그린 뷰 세트. 강의 AI System 아키텍처(운영/개발 서브시스템, 가드레일, 설명/분석 서비스, HITL/HOTL, Retraining)에 매핑했다.

- **표기법**: UML 컴포넌트/배포 다이어그램 + C4 컨텍스트. `«...»` = 강의 표준 컴포넌트 유형(스테레오타입), 박스 = 우리 구현, 원통 = 저장소, 실선 = 데이터 흐름, 점선 = 제어(HITL/HOTL/LLM 서빙/적응).
- **소스**: `.puml`(편집본). **렌더**: `.png`.
- **렌더 방법**: `java -jar plantuml.jar -tpng arch/diagrams/*.puml`. 기본은 **Graphviz(dot)** 레이아웃(더 깔끔). VS Code PlantUML 확장이나 plantuml.com 서버로도 가능.
- **Graphviz(dot)가 없으면** 두 가지 중 하나:
  - (a) 설치: `brew install graphviz`. 만약 `Directory not writable /opt/homebrew` 에러가 나면, brew 프리픽스 소유권이 어긋난 것 → 1회 `sudo chown -R $(whoami) /opt/homebrew` 후 다시 `brew install graphviz`. (sudo 없이도 `micromamba create -p ./gv -c conda-forge graphviz` 로 user-local 설치 가능.)
  - (b) 설치 없이: 각 `.puml` 최상단에 `!pragma layout smetana` 한 줄 추가 → PlantUML 내장 순수 자바 레이아웃으로 렌더(레이아웃은 약간 거침).

## 1. 시스템 컨텍스트 (Context)
시스템 경계와 외부 액터(사용자/워치 센서/Gemini Nano/GPS/날씨 API).

![Context](01-context.png)

## 2. 규칙/통계 기반 개인화 아키텍처 — C&C (상세 + 축약 쌍)
우리 시스템의 메인 컴포넌트-커넥터 뷰(설명용이성 DP에서는 채택안=1안으로도 쓰인다). 내부 컴포넌트를 강의 AI System 서비스별로 묶고, 커넥터를 타입 구분. 각 블록 상단 `«...»`가 강의 표준 컴포넌트 유형.
**항상 상세본과 PPT용 축약본을 쌍으로 유지**한다.

### 2a. 상세본 (전 컴포넌트)
![Component C&C 상세](02-component-cnc.png)

### 2b. 축약본 (PPT용 — 모델 서빙 4역할을 한 블록으로, 저장/로그 세부 생략)
![Component C&C 축약](02b-component-cnc-simple.png)

## 3. 배포 (Deployment)
폰 / 워치 / AICore 노드에 컴포넌트 배치. 2026-08-01 as-built 현행화(포그라운드 서비스/저장 5종/Data Layer 경로/AICore 계측 한계 주석).

![Deployment](03-deployment.png)

## 4. Module View (코드 패키지 의존)
폰 9패키지 + 워치 1패키지의 정적 의존 구조. domain=리프, 결정 로직은 안드로이드 미의존(단위 테스트 152개의 구조적 근거).

![Module View](04-module-view.png)

## 5. Use Case
러너 사용 사례 10종 + 개발/검증자(시뮬 실행 — 정식 액터).

![Use Case](05-usecase.png)

## 6. 최종 Architecture (거시 + 모듈 4장) — 인증 보고서 "최종 Architecture" 정본
최종 구현 상태를 강의 AI System 용어로 묶은 거시 뷰 + 모듈별 상세 4장. 원고 = `report/report-013`.

![최종 아키텍처 거시](06-final-architecture.png)

- 모듈 A 추론 서비스: ![](06a-module-inference.png)
- 모듈 B+C 설명+분석: ![](06b-module-explain-analysis.png)
- 모듈 D+E 운영+저장: ![](06c-module-operation-storage.png)
- 모듈 F 적응+검증: ![](06d-module-adaptation-verification.png)

> 이 폴더 = **일반 시스템 아키텍처**(우리 실제 시스템) 전용. 컨텍스트(01) / 우리 C&C(02, 02b) / 배포(03) / Module View(04) / Use Case(05) / 최종 아키텍처(06, 06a~d).
> **Windows 렌더**: plantuml 1.2025.2 jar + `java "-Dfile.encoding=UTF-8" -jar plantuml.jar -charset UTF-8 -Playout=smetana -tpng <files>` (graphviz 불필요). 신규 puml 폰트 = "Malgun Gothic"(Windows), 기존 = "AppleGothic"(Mac).
> **DP 전용 도식**(카운터 등 가정 설계)은 여기 두지 않고 각 DP 폴더 `arch/dp/dp-{NN}-{qa}/images/`에 둔다 — 예: 설명용이성 DP의 카운터 = `arch/dp/dp-01-explainability/images/`.

---

> 관련: `arch/component-catalog.md`(컴포넌트 식별+디스크립션), `arch/dp/`(DP 설계문서), `framework/ai-system-and-quality.md §1-3`(강의 표준 아키텍처).
