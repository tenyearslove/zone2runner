# app/ — Zone2 Runner (폰 앱)

개인화 유산소(Zone 2) 러닝 코칭 폰 앱. 우리가 설계/학습한 AI(MLP 판정 + Bayesian 개인화 + 온디바이스 LLM 코칭)를 온디바이스로 적용한다. 명세는 `spec/spec-011`, 설계 근거는 `arch/adr-001~011`.

## 빌드/실행

- JDK 17+ 필요(Android Studio JBR 권장). SDK 경로는 `local.properties`의 `sdk.dir`.
- 빌드: `JAVA_HOME=<JBR> ./gradlew.bat assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- 단위 테스트: `./gradlew.bat testDebugUnitTest` (파이프라인 + 실 모델 추론 8건)
- 설치: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## 전체 플로우

```
HomeActivity ──러닝 시작(sim/live)──> RunActivity ──종료 저장──> ReportActivity
     │                                                              ▲
     ├── 기록 ──> HistoryActivity ──탭──────────────────────────────┘
     └── 프로필 ──> ProfileActivity
```

## AI 파이프라인 (RunEngine, 1Hz)

```
Sample ─ OutlierGuard ─ FeatureExtractor(7특징) ─ Zone2Classifier(MLP) | 규칙폴백
       ─ Personalization(Bayesian) ─ Coach(규칙 방향 + LLM 표현) ─ 세션 누적
```

- `assets/zone2_mlp.json`: `ml/export_model.py`가 뽑은 MLP(7→32→16→3) 가중치 + StandardScaler.
- `Zone2Classifier`: TFLite 없이 순수 Kotlin 순전파(adr-011). 미로드 시 규칙 폴백.
- `Personalization`: 공식 사전 + decoupling 관측으로 개인 경계 갱신(adr-004).
- `LlmCoach`: Gemini Nano(ML Kit Prompt API, adr-007). AVAILABLE일 때만 사용, 아니면 `RuleCoach`.

## 입력 소스 (교체 가능, `sensor/`)

- `SimulatedRunSource`: 물리 시뮬레이터 가속 재생. 실기기 없이 전 파이프라인 구동(기본).
- `LiveRunSource`: 실 GPS(FusedLocation) + `HrProvider`.
  - `WatchHrProvider`: 워치가 Data Layer(`/hr`)로 보낸 HR 수신. 워치 측은 `wear/`의 `HrForwarder`.
- `MockRunSource`: 워치 없이 실시간 합성(테스트/시연). 심박/속도 범위를 지정(MockConfigActivity, 프리셋)하면 GPS가 이동하고 페이스가 계산됨. QA 테스트 가능성 지원.

## 모듈 구조

```
app/app/src/main/java/com/zone2runner/app/
├── HomeActivity / RunActivity / ReportActivity / HistoryActivity / ProfileActivity
├── domain/Models.kt          # Profile, Sample, ZoneJudgment, LiveState, RunReport, SeriesPoint
├── pipeline/                 # OutlierGuard, FeatureExtractor, Zone2Classifier, Personalization, RunEngine
├── coaching/                 # Coach(Rule), LlmCoach
├── sensor/                   # RunSource, SimulatedRunSource, LiveRunSource, HrProvider, WatchHrProvider
├── sim/RunSimulator.kt       # 물리 기반 러닝 시뮬레이터(ml/simulator.py 포팅)
├── data/                     # ProfileStore(Prefs), SessionStore(JSON)
└── ui/                       # Ui(팔레트/헬퍼), Charts(시계열/타임라인), ZoneBarView, ReportHolder
```

## 검증 현황

- 빌드: `assembleDebug` 성공(app-debug ≈ 5MB). 단위 테스트 8건 통과.
- 실 모델 추론 검증: export된 `zone2_mlp.json` 로드 → high→ABOVE, low→BELOW, mlp_acc=0.826/QA1=0.996/QA2=1.0 재현.
- **실기기 미검증**: 실센서 모드 GPS/워치 HR end-to-end, Gemini Nano 실기기 코칭, 화면 레이아웃 시각 튜닝.

## 기술 선택

- 지도: osmdroid(OSM, API 키 불필요) — adr-010.
- 온디바이스 추론: 순수 Kotlin(작은 MLP, 의존성 0) — adr-011.
- UI: 프로그래매틱 View(다크 테마). XML 레이아웃 미사용.
