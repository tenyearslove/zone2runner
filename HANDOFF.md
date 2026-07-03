# HANDOFF — 작업 이어가기

이 문서는 다른 환경(집 등)에서 repo를 clone해 작업을 이어가기 위한 컨텍스트다. Claude Code 로컬 메모리(`~/.claude/`)는 git으로 전파되지 않으므로, 핵심 컨텍스트를 여기에 담는다.

> **새 세션에서 이어가는 법**: Claude에게 "HANDOFF.md 읽고 이어가자"라고 하면 된다.

최종 갱신: 2026-07-03 (야간 빌드 + 코칭 점검/문서 환류 + 학습 가이드)

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

- spec-001~011, adr-001~011, arch/architecture-overview, report-001~002. ml/(코드+EXPERIMENT_LOG/COMPARISON/AI_EXPLAINED), llm-verify/, sensor-poc/, app/, wear/.
  - 신규(2026-07-02): spec-007(기록/리포트 FR6), spec-008(안전 C03), spec-009(프로필/RHR FR1), ml/AI_EXPLAINED.md(쉬운 AI 설명, 개인용).
  - 신규(2026-07-03): spec-010(워치 대시보드), spec-011(폰 앱), adr-009(백그라운드)/010(지도)/011(추론 런타임), **STUDY_GUIDE.md(루트 — 프로젝트 전체+AI를 바닥부터 배우는 개인 학습서, 실습/Q&A 포함)**.
- **읽기 진입점**: arch/architecture-overview.md(구현 현황 반영됨) → adr-005(DP4 NN 근거) → spec-006(MLP) → spec-004(개인화). AI/구현 이해는 **STUDY_GUIDE.md**(넓고 깊게) 또는 ml/AI_EXPLAINED.md(개념 요약).
- spec-001에 요구사항→설계→구현 추적표, spec-002 부록에 QA 검증 현황 스냅샷 있음.

## 5. 진행 현황 / 스코프 (2026-07-02)

**★ 스코프 확정 — 실제 구현은 2개만 (더 늘리지 말 것)**:
1. **MLP 판정기** (=NN, 교육요건+QA1) — 완성. `ml/train_mlp.py`
2. **Bayesian 개인 경계 보정** (개인화, QA3) — 메커니즘 검증 완료. `ml/personalization.py`
검토한 대안(성시원 HR회귀 B 등)은 ADR/COMPARISON에 **기록만**(구현 X). 대안 비교는 설계 점수 +.

**PoC 결과**:
- 판정(A): 규칙 0.485 → +MLP 0.780 → +개인화 0.826(개발지표). **QA1 코칭방향 0.996, QA2 이상치기각 1.0**(둘 다 목표 달성). 센서노이즈는 범위 밖. `ml/EXPERIMENT_LOG.md`
- 개인화(Bayesian): 관측 양호 시 세션 누적 수렴(오차 5→2bpm, σ↓ = QA3 메커니즘 실증). 단 실제 관측(decoupling 임계추출)은 편향→발산. **병목=임계추출**(Conconi 편향, 향후과제/한계로 명시).
- A vs B 비교: 주 판정기=A, B는 개인화/피트니스 보조. `ml/COMPARISON.md`, `arch/adr-006`(Accepted)
- 실행: `python3 -m venv .venv && ./.venv/bin/pip install -r ml/requirements.txt`

**온디바이스 LLM 검증 완료 (2026-07-02, adr-007)**: `llm-verify/` Android 앱으로 실기기(Exynos S26) 검증.
- Gemini Nano(ML Kit Prompt API) **AVAILABLE**, warm 생성 ~2초, 코칭 방향 정확, **TTS까지 end-to-end 동작**.
- 모델 ~4GB. 제약: 포그라운드 전용(ErrorCode 30) → 러닝 중 화면off 시나리오는 후속 확인.
- 잔여: 오프라인(비행기모드), Snapdragon Ultra 재확인. → Plan A(Gemini Nano) 채택.

**센서 PoC 빌드 완료 (2026-07-02, adr-008)**: `sensor-poc/` (wear+phone 2모듈).
- wear: Health Services 실시간 HR → Data Layer(MessageClient /hr) 전송. phone: HR 수신 + 위치/고도/경사(FusedLocation). 날씨 제외.
- 빌드 성공(phone 3.9MB, wear 12.3MB). **실기기 검증 대기**: 폰 adb 설치 + 워치 무선디버깅 설치 후 HR 수신/위치·경사 확인(README).
- 배포 답: 개발=워치 직접설치(자동X), 프로덕션=Play 자동설치. 페어링=기존 사용.

**자율 세션 완료분 (2026-07-02)**: ml 데이터 3배 확대 재학습(150러너/40만샘플, 규칙0.544→MLP0.802→개인화0.828, QA1 0.996/QA2 1.0), AI_EXPLAINED 작성, 공백 spec 3종(007/008/009), report-002 최신화, spec-001 추적표. 전체 점검(gap analysis) 완료.

**워치 실앱 착수 (2026-07-02, spec-010)**: `wear/` — sensor-poc와 분리된 실제 Zone2 Runner 워치 앱.
- sensor-poc의 실시간 GUI 성과를 productize. 원형 화면 **한 화면(무스크롤)** 러닝 대시보드.
- 표시: 경과시간 / HR 큰숫자+존색상 / 존라벨 / 페이스·거리·속도 3열 / 베젤 존게이지(커스텀 `ZoneGaugeView`, 5구간 아크+마커) / 상태별 버튼.
- 세션 상태기계: 대기→진행→일시정지→재개→종료(시작/일시정지/재개/종료 버튼). HR=Health Services, 페이스/속도/거리=play-services-location(GPS).
- 존은 **경량 %HRmax**(Z2 60~70% 목표, HRmax=190 상수) — 정밀/개인화 판정은 폰 MLP(adr-005). 워치는 즉시 피드백용.
- 빌드 성공(app-debug 12.3MB). **실기기 미검증**: 원형 화면 폰트/여백/게이지 두께 시각 튜닝 필요(코드값은 추정치).
- 독립 Gradle 프로젝트(appId `com.zone2runner.wear`), JDK 17+(JBR) 빌드. `wear/README.md`.
- 통합 미완: 폰 Data Layer 전송(spec-003), 개인 HRmax/RHR(spec-009), 세션 저장/요약(spec-007 FR6).

**코칭 점검 (2026-07-03)**: 프로젝트 목적(설계 능력 입증) 대비 궤도 정상 — 설계 체계 완결(요구→QA/ASR→DP/ADR 11→spec 11→추적표), NN 교육요건 충족, 설계가 구현으로 실증(1단계 PoC 완료, QA1/2/5 달성 + QA3 메커니즘 실증). 발견한 갭 "검증 결과의 문서 환류 누락"은 해소함: architecture-overview 현행화(모듈뷰 구현 반영, QA 매핑에 검증 현황 열, 개발단계 완료 표시), spec-002 부록(QA 검증 스냅샷), spec-001 추적표(구현 열 + adr-009~011/spec-010~011). **남은 코칭 지적: spec-003~011 전부 Draft — 구현으로 검증된 것부터 사용자 검토/승인 필요(승인은 사용자 몫)**.

**시뮬 점검 (2026-07-03, 사용자 보고 "HR 220~230, Zone2가 140?")**: 점검 결과 —
- (버그, 수정됨) 앱/ml 시뮬레이터 HR에 최대심박 클램프가 없어 고강도 지속+드리프트 시 maxHr+30bpm까지 상승. 앱 `RunSimulator`는 클램프 적용, **`ml/simulator.py`는 동일 한계 미수정**(수정 시 재학습+EXPERIMENT_LOG/report 지표 전부 갱신 필요 — 영향 평가 후 결정, 한계로 문서화 가능).
- (버그, 수정됨) 앱 시뮬 몸(랜덤 나이/RHR/maxHr)과 판정 프로필 불일치 → 시뮬 몸을 사용자 프로필에 고정(uFrac 개인차는 유지, 개인화 데모 보존).
- (설계대로) Zone2를 ~140bpm에서 판정하는 것은 Karvonen(%HRR 60~70) 기준으로 정상(기본 프로필 35세/RHR58 → 133~145bpm). %HRmax 60~70 기준이면 110~128bpm — 관례 차이지 버그 아님(spec-004 μ0=RHR+0.70*HRR).
- (불일치, 미해결) **워치 Zones.kt는 %HRmax(HR_MAX=190 상수) → Z2=114~133bpm, 폰은 HRR → 133~145bpm으로 거의 겹치지 않음.** 같은 HR에 워치/폰 판정이 다르게 보일 수 있음 — 워치를 프로필 기반 HRR로 정렬 필요(spec-009/010 연동 과제).

**남은 공백/다음 (서두르지 말 것)**:
- **Draft spec 검토/승인**(우선: spec-006/011 — 구현 검증 완료분), report-002에 야간 빌드 성과 반영.
- **wear/ 실기기 검증 + 시각 튜닝**(원형 레이아웃 잘림/폰트/게이지), 예열 문구, 손목 내림 대응.
- wear ↔ phone Data Layer 통합(현재 wear는 표시 전용, 전송 미구현).

**백그라운드 서비스화 (2026-07-02, adr-009)**: 실기기 피드백 — 워치 백그라운드 시 전송 끊김/재실행해도 안 됨, 폰도 포그라운드에서만 수신.
- 원인: MeasureClient는 포그라운드 전용. → **워치: ExerciseClient(RUNNING) + 포그라운드 서비스(`HrService`)**로 전환(화면off 지속). **폰: `WearableListenerService`(`HrReceiverService`)**로 백그라운드 수신 + 알림 증빙.
- sensor-poc에 먼저 반영(검증용). 구조: 워치 `HrBus`(서비스↔UI 공유상태), 폰 `HrStore`. 빌드 성공. **실기기 재검증 대기**(화면off/앱백그라운드에서 카운트/알림 지속되는지).
- 남는 제약: LLM 코칭은 폰 포그라운드 전용(adr-007) → 화면off 코칭 후속 과제(짧은 포그라운드 승격/규칙 TTS 폴백/화면유지 모드 중 택).
- 실 wear/ 앱도 ExerciseClient 기반으로 전환 예정(거리/페이스/속도 네이티브 → spec-010 GPS 손계산 대체).
- sensor-poc 실기기 검증(HR 수신/위치/경사), 오프라인/Ultra 재확인, 화면off 시나리오(LLM 포그라운드+워치 HR) 대응.
- 개인 임계 추출 개선(개인화 완성) 또는 한계로 확정.
- 최종 Architecture 뷰 상세(Module/C&C/Deployment appendix), app/ 통합 스캐폴딩.
- spec-007/008/009는 Draft → 검토/승인 필요.

**★ 폰 앱 `app/` 야간 빌드 (2026-07-03, spec-011)**: 시뮬레이터 데모였던 `app/`를 사용자가 전체 플로우를 써볼 수 있는 실제 러닝 코칭 앱으로 확장. **app 빌드 성공 + 단위 테스트 13건 통과, wear 빌드 성공**. (문서 `app/README.md`, `app/NIGHTBUILD.md`, `spec/spec-011`, `arch/adr-010`(지도)/`adr-011`(순수 Kotlin 추론))
- 화면 6개: Home(진입/프로필요약/최근세션) → Run(라이브 지도+HR+존+코칭) → Report(요약/시계열차트/유산소분석/경로지도) / History(기록 목록,삭제) / Profile(spec-009 나이/RHR/maxHR) / MockConfig(가짜 라이브 설정).
- AI 파이프라인 온디바이스 적용: OutlierGuard → FeatureExtractor(7특징) → **Zone2Classifier(순수 Kotlin MLP 순전파, TFLite 없음)** | 규칙폴백 → **Personalization(Bayesian)** → **Coach(규칙 방향 + Gemini Nano 표현, 실패 시 규칙 폴백)** → 세션 누적. `assets/zone2_mlp.json`은 `ml/export_model.py` 산출.
- **실 모델 추론 검증(단위 테스트)**: export 모델 로드 → high→ABOVE/low→BELOW, mlp_acc=0.826/QA1=0.996/QA2=1.0 재현(학습 모델과 동일 방향). 순전파 이식이 정확함을 실증.
- 입력 소스 추상화(`RunSource`): `SimulatedRunSource`(가속 재생, 기본) / `LiveRunSource`(실 GPS FusedLocation + `WatchHrProvider`가 워치 Data Layer `/hr` 수신) / `MockRunSource`(**가짜 라이브** — 워치 없이 실시간 합성, 심박/속도 범위 지정, QA 테스트 가능성/시연). 워치 측 `wear/HrForwarder`가 HR 송신(sensor-poc 프로토콜).
- 코칭 음성(TTS 한국어), 세션 JSON 영속화(`filesDir/sessions`), 프로필 Prefs.
- **실기기 미검증**: 실센서 모드 GPS/워치HR end-to-end, Gemini Nano 실기기 코칭, 화면 레이아웃 시각 튜닝. (컴파일/단위테스트까지만 검증 — 에뮬레이터/기기 실행 안 함)
- 빌드: `app/`에서 `JAVA_HOME=<AndroidStudio JBR> ./gradlew.bat assembleDebug testDebugUnitTest`.

## 6. 작업 방침 (사용자 요청)

- **자율 진행**: 매 단계 묻지 말고 "결정 → 실행 → 커밋 → 보고". git이 안전망.
- 멈추고 물을 예외 2가지만: (a) 사용자만 아는 정보로 추측 불가한 것, (b) 되돌리기 어렵거나 외부로 나가는 것(원격 push 등).

## 7. 도구/스킬 메모

- 스킬: `/adr`, `/spec`, `/report` (`.claude/skills/{name}/SKILL.md` 구조).
- 문서 규칙: 번호 3자리, 구체적 제목, ADR은 대안 2~3개 필수, `·` 기호 금지(열거는 `,`/`/`). CLAUDE.md 참조.
- PDF 텍스트 추출 필요 시 `pip install pypdf`. docx는 macOS `textutil`.
