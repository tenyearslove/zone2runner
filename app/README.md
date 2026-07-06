# app/ — Zone2Runner (폰 앱)

개인화 유산소(Zone 2) 러닝 코칭 폰 앱. 문제별로 알맞은 AI 도구를 온디바이스로 적용한다(adr-016):
**규칙 판정(ZoneJudge) + 온라인 Bayesian 개인화 + 심박 예측 NN(HrDynamics) + LLM 코칭(Gemini Nano)**.
명세는 `spec/spec-011`, 설계 결정 종합은 `report/report-004`, 설계 근거는 `arch/adr-*`.

## 빌드/실행

- JDK 17+ 필요(Android Studio JBR 권장). SDK 경로는 `local.properties`의 `sdk.dir`.
- 빌드: `JAVA_HOME=<JBR> ./gradlew.bat assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- 단위 테스트: `./gradlew.bat testDebugUnitTest` (판정/개인화/예측/코칭 가드/가상러너/통합 등 63건)
- 설치: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## 전체 플로우

```
HomeActivity ──러닝 시작(sim/live)──> RunActivity ──종료 저장──> ReportActivity
     │                                                              ▲
     ├── 기록 ──> HistoryActivity ──탭──────────────────────────────┘
     └── 프로필 ──> ProfileActivity
```

## AI 파이프라인 (RunEngine, 1Hz) — adr-016 "문제별 도구"

```
Sample ─ OutlierGuard ─ ZoneJudge(규칙 판정: 지속심박 vs 개인 경계 + 히스테리시스)
       ─ Personalization(온라인 Bayesian: 토크테스트로 개인 경계 학습)
       ─ HrDynamics(심박 예측 NN) + HrPredictionLearner(온라인 개인 보정) ─ 선제 코칭/페이스 제안
       ─ Coach(규칙 방향 + LLM 표현 + DirectionGuard) ─ 세션 누적
```

- `ZoneJudge`: 판정=규칙(결정론, 모순 불가). 개인 경계(Personalization)와 지속 심박 비교(adr-013).
- `Personalization`: 공식 prior(factor, adr-012) + **토크 테스트(주 라벨)** + 디커플링(약보조)로
  개인 Zone2 경계를 온라인 Bayesian 갱신(adr-004/016). 세션 누적 = `LearnedZone`.
- `assets/hr_dynamics.json`: `ml/train_hr_dynamics.py`가 뽑은 심박 예측 NN(7특징→…→[t+30,t+60]).
  `HrDynamics`가 순수 Kotlin 순전파, `HrPredictionLearner`가 개인 잔차를 LMS로 온라인 보정(spec-018).
- `LlmCoach`: Gemini Nano(ML Kit Prompt API, adr-007). 방향은 규칙, LLM은 표현만, `DirectionGuard`가
  역/무방향 기각. 미가용 시 `RuleCoach` 폴백. 프롬프트는 `assets/coach_prompt.json`(외부화).

> 폐기: 판정 MLP(Zone2Classifier)/역치추정 NN(ThresholdEstimator)은 제거됨(adr-013/016, git 이력).

## 입력 소스 (교체 가능, `sensor/`)

- `SimulatedRunSource`: 물리 시뮬레이터 가속 재생. 실기기 없이 전 파이프라인 구동(기본).
- `LiveRunSource`: 실 GPS(FusedLocation) + `HrProvider`.
  - `WatchHrProvider`: 워치가 Data Layer(`/hr`)로 보낸 HR 수신. 워치 측은 `wear/`의 `HrForwarder`.
- `MockRunSource`: 워치 없이 실시간 합성(테스트/시연). 심박/속도 범위를 지정(MockConfigActivity, 프리셋)하면 GPS가 이동하고 페이스가 계산됨. QA 테스트 가능성 지원.

## 모듈 구조

```
app/app/src/main/java/com/zone2runner/app/
├── HomeActivity / RunActivity / ReportActivity / HistoryActivity / ProfileActivity / MockConfigActivity
├── domain/Models.kt          # Profile, Sample, ZoneJudgment, LiveState, RunReport, SeriesPoint
├── pipeline/                 # OutlierGuard, FeatureExtractor, ZoneJudge(규칙 판정), Personalization(Bayesian), HrDynamics+HrPredictionLearner(예측), RunEngine
├── coaching/                 # Coach(Rule), LlmCoach
├── sensor/                   # RunSource, SimulatedRunSource, LiveRunSource, MockRunSource, HrProvider, WatchHrProvider
├── sim/RunSimulator.kt       # 물리 기반 러닝 시뮬레이터(ml/simulator.py 포팅)
├── data/                     # ProfileStore, SessionStore(JSON), MockConfigStore
└── ui/                       # Ui(팔레트/헬퍼), Charts(시계열/타임라인), ZoneBarView, ReportHolder
```

## 검증 현황

- 빌드: `assembleDebug` 성공(ML Kit GenAI/GMS 포함). 단위 테스트 63건 통과.
- 심박 예측 NN 검증: `hr_dynamics.json` 순전파 이식 정확성 + 폐루프 온라인 보정 60초 RMSE 27→12.5bpm(EXPERIMENT_LOG §12).
- 규칙 판정: 모순 불가 속성 테스트(ZoneJudgeTest). 개인화: 토크테스트 단측 관측 수렴/무변화 회귀 테스트.
- **실기기 검증**: 폰 시뮬/프로필 관리/AI 설명(Gemini Nano) 확인. 실센서 GPS/워치 HR end-to-end는 필드 테스트(FIELD_TEST.md).

## 기술 선택

- 지도: osmdroid(OSM, API 키 불필요) — adr-010.
- 온디바이스 추론: 순수 Kotlin(작은 MLP, 의존성 0) — adr-011.
- UI: 프로그래매틱 View(다크 테마). XML 레이아웃 미사용.
