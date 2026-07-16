# 컴포넌트 카탈로그 (식별 + 디스크립션)

- **상태**: 검토용 v1 (2026-07-14). 도식보다 먼저 **컴포넌트를 식별하고 각각의 책임을 서술**한다. 이후 이 위에 아키텍처 도식/DP 문서를 얹는다.
- **구성**: 강의 표준 AI System 역할(추론/설명/분석/운영 서비스 + 개발/저장)별로 묶었다. 각 항목 = **컴포넌트명 | 강의 표준 매핑 | 책임(디스크립션) | 입력→출력 | 구현 파일**.

---

## A. 추론 서비스 (Inference Service)

### A1. 신호 소스 (RunSource)
- **표준 매핑**: 데이터 수집(추론 입력원)
- **책임**: 실기기/시뮬/수동 소스를 하나의 추상(`RunSource`)으로 감싸, 매초 하나의 통합 샘플(`Sample`: 시각/심박/페이스/케이던스/경사/좌표)을 엔진에 흘려보낸다. 소스 교체가 코드 변경 없이 되도록 하는 DIP 경계.
- **입력→출력**: 워치 HR + 폰 GPS/케이던스 → `Sample`(1Hz)
- **구현**: `sensor/RunSource.kt`, `sensor/WatchHrProvider.kt`, `sensor/SlopeEstimator.kt`

### A2. 입력 가드레일 (OutlierGuard + 게이팅)
- **표준 매핑**: 입력 가드레일
- **책임**: 생리적으로 불가능한 값(40~220 밖 심박) 기각, 신선도(staleMs) 초과 값 무효화, 분석용 정속/노이즈 구간 게이팅. 이후 모든 계산이 깨끗한 신호만 받게 하는 방어선.
- **입력→출력**: 원시 `Sample` → 검증된 신호(또는 무효 표시)
- **구현**: `pipeline/OutlierGuard.kt` (+ 분석 게이팅은 `analysis/`)

### A3. 특징 준비 (FeatureExtractor + SignalWindow)
- **표준 매핑**: 데이터 준비 + Feature Store(경량)
- **책임**: 지속 심박(60초 이동평균), 심박 추세(dHr/s) 등 파생 특징을 만들고, 지표 계산용 롤링 신호 창을 유지한다. Feature Store에 해당하는 온디바이스 경량 버퍼.
- **입력→출력**: 검증된 1Hz 신호 → 지속심박/추세/신호창
- **구현**: `pipeline/FeatureExtractor.kt`, `analysis/SignalWindow.kt`

### A4. 존 판정기 (ZoneJudge / DisplayZoneJudge)
- **표준 매핑**: 모델 서빙(규칙 기반 결정론 모델)
- **책임**: 지속 심박을 개인 경계와 비교해 미달/유지/초과를 판정(히스테리시스로 깜빡임 방지). 화면 색용 표시존(순간심박 기반)도 별도로 판정. 결정론 규칙이라 판정 자체가 곧 설명이 된다.
- **입력→출력**: 지속/순간 심박 + 경계 → Zone2 판정 / 표시존
- **구현**: `pipeline/ZoneJudge.kt`, `domain/DisplayZone.kt`

### A5. 개인 경계 추정기 (Personalization + Zone2Prior)
- **표준 매핑**: 모델 서빙(해석가능 모델, **개인 적응**)
- **책임**: 개인 Zone 2 상한을 베이지안으로 추정한다 — 프로필로 콜드스타트 prior(μ0, σ0)를 잡고(`Zone2Prior`), 말하기 테스트 라벨이 들어올 때마다 μ/σ를 세션마다 갱신(`Personalization`). 참값 없이 그 사람 자리로 수렴하는 개인화의 핵심. NN 아님.
- **입력→출력**: 프로필 + 말하기 테스트 라벨 → 경계 μ/σ(불확실도 포함)
- **구현**: `pipeline/Personalization.kt`, `domain/Zone2Prior.kt`, `domain/PersonalizationStatus.kt`

### A6. 관측 분석 엔진 (AnalysisEngine + 5지표 + NoiseFloor)
- **표준 매핑**: 모델 서빙(분석) / 분석 서비스로도 연결
- **책임**: 라이브 관측만으로 참값 없이 파생지표를 도출한다 — 드리프트 기울기, 경사보정 페이스(GAP), 케이던스 안정성, 서브맥시멀 심박, 심박 회복(HRR). 지표별 작은 모듈을 레지스트리로 조립(OCP), 판단선은 개인 k×σ(마법상수 금지). 소비처 = 코칭/리포트/개인화.
- **입력→출력**: 신호창 + 프로필 → 파생지표(값/기울기/SE/R²/추세)
- **구현**: `analysis/AnalysisEngine.kt`, `analysis/AnalysisMetric.kt`, `analysis/{DriftSlope,GapMinetti,CadenceStability,SubmaxHr,Hrr}Metric.kt`, `analysis/NoiseFloor.kt`, `analysis/LinearRegression.kt`, `analysis/AnalysisConfig.kt`

### A7. 코칭 표현기 (RuleCoach + LlmCoach + NanoRewriter/Summarizer)
- **표준 매핑**: 모델 서빙(온디바이스 LLM) + 규칙
- **책임**: **규칙(RuleCoach)이 코칭 방향과 사실을 확정**하고, **온디바이스 LLM(LlmCoach = Gemini Nano)은 그 사실을 문장으로 표현만** 한다. Nano의 Rewriting으로 톤(페르소나)을 입히고, 미가용/실패 시 규칙 문구로 폴백. 리포트 요약은 Summarization.
- **입력→출력**: 판정/분석 사실 + 페르소나 → 코칭 문장(음성/텍스트)
- **구현**: `coaching/Coach.kt`(RuleCoach/CoachContext/DirectionGuard), `coaching/LlmCoach.kt`, `coaching/NanoRewriter.kt`, `coaching/NanoSummarizer.kt`, `coaching/CoachPrompt.kt`, `pipeline/CoachCadence.kt`

### A8. 출력 가드레일 (DirectionGuard + SafetyGuard)
- **표준 매핑**: 출력 가드레일
- **책임**: LLM 문장이 규칙이 정한 방향과 모순되면 기각(`DirectionGuard`, 방향 잠금). 위험 고심박이 지속되면 LLM을 우회해 규칙이 즉시 감속 권고(`SafetyGuard`, 안전 최우선). 약한 LLM을 신뢰 가능하게 만드는 통제층.
- **입력→출력**: LLM 문장 / 심박 → 통과 문장 or 폴백 / 안전 경고
- **구현**: `coaching/Coach.kt`(DirectionGuard), `pipeline/SafetyGuard.kt`

### A9. 오케스트레이터 (RunEngine)
- **표준 매핑**: Inference Control
- **책임**: 매 틱마다 위 컴포넌트(가드레일→판정/경계/분석→코칭 트리거→출력 가드)를 순서대로 구동하고, 누적 지표(거리/존 체류/드리프트 플로어/코칭)를 관리하며 세션 종료 시 리포트를 만든다. GRASP 컨트롤러.
- **입력→출력**: `Sample` 스트림 → `LiveState`(실시간) / `RunReport`(종료)
- **구현**: `pipeline/RunEngine.kt`

---

## B. 설명 서비스 (Explanation Service)

### B1. 설명 생성기 (SessionExplainer + PersonalizationExplainer)
- **표준 매핑**: 설명 서비스(설명 생성 → 설명 결과)
- **책임**: "왜 이렇게 판정/코칭했는지", "경계를 어떻게 학습했는지"를 실제 계산 사실에서 생성한다(규칙 팩트 우선 저장 → LLM 자연어 풀이로 덮되 사실 불변). 세션 종료 1회 생성해 저장(재열람은 재호출 없음).
- **입력→출력**: 리포트 사실 + 값 출처 태그 → 세션 스토리/개인화 설명(저장)
- **구현**: `coaching/SessionExplainer.kt`, `coaching/PersonalizationExplainer.kt`

### B2. 값 출처 메타데이터 (종류 A/B/C 태그) — 규율/횡단
- **표준 매핑**: ML Metadata store (provenance/lineage)
- **책임**: 표시/판정/코칭에 쓰는 모든 수치가 자기 출처(A 도출값 / B 학습값 / C 설계선택)를 갖게 하는 **전 컴포넌트 규율**("없는 숫자 금지"). 하나의 클래스가 아니라 설계 원칙이며, 설명 서비스가 이 태그를 읽어 근거를 만든다.
- **입력→출력**: (모든 값에 부여) → 설명 서비스가 소비
- **구현**: 원칙(CLAUDE.md) + 각 값의 근거가 spec/arch에 문서화(예: `analysis/AnalysisConfig.kt` 주석, spec-013/016/025)

---

## C. 분석 서비스 (Analysis Service)

### C1. 세션 내 분석기 (SessionAnalytics)
- **표준 매핑**: 분석 서비스(지표 산출)
- **책임**: 세션 시계열을 쪼개 km 구간 splits(경사보정 포함), 오르막/평지/내리막 경사 분해, 워밍업 품질, 초과 원인 분해, 내리막 습관을 도출한다. 순수 함수(테스트 가능).
- **입력→출력**: `RunReport.series` → 구간/경사/워밍업/원인 지표
- **구현**: `analysis/SessionAnalytics.kt`

### C2. 세션 간 비교/추세 (SessionCompare + SessionTrends)
- **표준 매핑**: 분석 서비스(추론 모니터링 지표 — 세션 단위)
- **책임**: 이전 세션 대비 향상/악화(효율 EF/드리프트/Zone2%/서브맥시멀), 최근 N세션 추세 스파크라인, 개인 기록(PR), 그날 컨디션, 기간 요약을 도출한다. 누적될수록 가치가 커지는 모니터링.
- **입력→출력**: 세션 이력 → 비교/추세/기록/컨디션
- **구현**: `domain/SessionCompare.kt`, `domain/SessionTrends.kt`

### C3. 세션 로거 (RunLogger)
- **표준 매핑**: 서빙 로그
- **책임**: 세션 이벤트/신호/코칭 경로를 기록해(필드 검증 대비) 분석/디버깅의 원천 로그를 남긴다.
- **입력→출력**: 런타임 이벤트 → 세션 로그
- **구현**: `data/RunLogger.kt`

---

## D. 운영 서비스 (Operation Service)

### D1. 대시보드/리포트 UI (RunActivity / WearRunActivity / ReportActivity 등)
- **표준 매핑**: Operation UI
- **책임**: 실시간 대시보드(폰), 워치 존 게이지, 세션 리포트(카드 + 지표 터치 설명 팝업), 프로필/설정/이력 화면. 설명 서비스/분석 결과의 표시 계층.
- **입력→출력**: `LiveState`/`RunReport`/설명 → 화면/음성
- **구현**: `RunActivity.kt`, `ReportActivity.kt`, `ProfileActivity.kt`, `SettingsActivity.kt`, `HistoryActivity.kt`, `HomeActivity.kt`, wear `WearRunActivity.kt`/`ZoneGaugeView.kt`/`TalkTestActivity.kt`

### D2. 인프라/세션 제어 (RunService + RunControlService + RunLink/RunBus)
- **표준 매핑**: Infrastructure Control
- **책임**: 포그라운드 서비스로 화면이 꺼져도 측정/코칭 지속, 폰-워치 원격 제어와 데이터 계층 통신(HR/존/토크/미러). 세션 수명/기기 간 동기화 관리.
- **입력→출력**: 시작/종료 명령 + 기기 간 메시지 → 서비스 수명/전송
- **구현**: `RunControlService.kt`, `RunLink.kt`, wear `RunService.kt`/`RunControlService.kt`/`RunLink.kt`/`RunBus.kt`/`HrForwarder.kt`

### D3. HITL / HOTL 제어 (말하기 테스트, 설정, 안전 개입)
- **표준 매핑**: HITL / HOTL Control triggering
- **책임**: 사람이 루프 안에서 라벨을 넣거나(HITL = 말하기 테스트 응답 → 경계 교정) 루프 위에서 감독/개입(HOTL = 코칭 무시, 코칭 빈도/음성 설정, 안전 상황에서 규칙이 대신 개입).
- **입력→출력**: 사용자 응답/설정 → 개인화 라벨 / 코칭 통제
- **구현**: `TalkTestActivity.kt`(워치), `RunActivity` 토크 팝업, `data/AppSettings.kt`, `SettingsActivity.kt`

---

## E. 저장 (Data Lake / Registry 대응)

### E1. 학습값 저장 (LearnedZone)
- **표준 매핑**: Model Artifact / ML Metadata (개인 파라미터)
- **책임**: 학습된 경계(uFrac 이력/σ), 드리프트 개인 플로어, 오르막 초과 경향, 개인화 설명을 프로필별로 온디바이스 영속화한다. 다음 세션이 여기서 이어받아 개인화가 누적된다.
- **입력→출력**: 세션 종료 학습값 → 다음 세션 prior
- **구현**: `data/LearnedZone.kt`

### E2. 세션 저장 (SessionStore)
- **표준 매핑**: Data Lake (관측 이력)
- **책임**: 세션 리포트(요약/시계열/분석 라인/스토리)를 저장하고 이력을 로드한다. 세션 간 분석/추세의 원천.
- **입력→출력**: `RunReport` → 저장/조회
- **구현**: `data/SessionStore.kt`

### E3. 프로필/설정 저장 (ProfileStore + Profiles + AppSettings)
- **표준 매핑**: 구성/컨텍스트 저장
- **책임**: 나이/안정/최대심박/신체 factor/관절 주의 등 프로필과 코칭 설정을 저장, 다중 프로필 네임스페이스 관리. prior 산정과 개인화의 기준 입력.
- **입력→출력**: 사용자 입력 → 프로필/설정
- **구현**: `data/ProfileStore.kt`, `data/Profiles.kt`, `data/AppSettings.kt`, `data/WeatherProbe.kt`(기온 참고 입력)

### E4. 공유 레지스트리 (Model / Container Registry 대응)
- **표준 매핑**: Model Registry / Container Registry
- **책임**: 모델 산출물 = 개인 파라미터(LearnedZone) + 시스템 제공 Nano 모델(AICore). 패키지 = 앱(APK, 폰+워치). 우리는 자체 학습 모델 배포 파이프라인이 없어 이 부분이 경량이다.
- **구현**: LearnedZone + AICore(외부) + APK 빌드

---

## F. 개발/검증 (Development — 오프라인 학습 없음, 개인 적응/시뮬로 축소)

### F1. 개인 적응 갱신 (Retraining 대응)
- **표준 매핑**: Model Construction / Retraining
- **책임**: 표준의 "오프라인 대량 학습"이 우리에겐 없다. 대신 **운영 안에서 세션마다** 경계 μ/σ를 베이지안 갱신하고 k×σ 노이즈플로어를 EWMA로 추정한다. 세션 관측/말하기 테스트가 곧 갱신 트리거.
- **입력→출력**: 관측/라벨 → 갱신된 개인 파라미터
- **구현**: `Personalization.update()`, `NoiseFloor`(EWMA), `LearnedZone` 누적

### F2. 시뮬 검증 도구 (VirtualRunner + 폐루프 소스)
- **표준 매핑**: 모델 테스팅(참값 없는 조건의 우회)
- **책임**: 참값을 아는 가상 러너로 앱 전체(판정/개인화/분석/코칭)를 워치 없이 폐루프 검증한다. 앱은 정답(trueUpper)을 모른 채 수렴하는지 본다(자기참조 방지). 실인간 정확도가 아니라 소프트웨어/알고리즘 검증.
- **입력→출력**: 시드/특성 → 합성 세션 + 수렴 검증
- **구현**: `domain/VirtualRunner.kt`, `sim/SimRunnerSource.kt`, `sim/RunSimulator.kt`, `sim/ManualRunSource.kt`, `sim/ManualVirtualRunnerSource.kt`, `sim/RouteWalk.kt`

---

## 요약 — 컴포넌트 수와 정체성

- **추론 서비스 9 + 설명 2 + 분석 3 + 운영 3 + 저장 4 + 개발/검증 2 = 우리 시스템의 컴포넌트 뷰.**
- 표준 대비 **Operation 쪽이 두껍고 Development(오프라인 학습/테스팅/승격)가 얇다** — 학습이 운영 안 개인 적응(세션마다 갱신)으로 녹아 있는 게 우리 아키텍처의 정체성.
- **가드레일(입력 A2 / 출력 A8) + 설명 서비스(B) + HITL/HOTL(D3) + 개인 적응(F1)** 이 강의 표준의 AI 특화 요소와 그대로 대응한다.

> 관련: `framework/ai-system-and-quality.md §1-3`, `arch/architecture-overview.md`(모듈 뷰), `arch/adr-025`(AI≠NN).
