# HANDOFF — 작업 이어가기

이 문서는 다른 환경(집 등)에서 repo를 clone해 작업을 이어가기 위한 컨텍스트다. Claude Code 로컬 메모리(`~/.claude/`)는 git으로 전파되지 않으므로, 핵심 컨텍스트를 여기에 담는다.

> **새 세션에서 이어가는 법**: Claude에게 "HANDOFF.md 읽고 이어가자"라고 하면 된다.

최종 갱신: 2026-07-03 저녁 (★ adr-013 판정 아키텍처 재설계 — 판정=규칙, NN=심박 동역학 모델/페이스 제안. 폰 설치 완료, 사용자 시뮬 체험 대기)

> **지금 상태 한 줄 요약**: 실기기에서 판정 MLP 치명 결함 발견("심박 170인데 미달" — 시뮬 라벨 순환)
> → 사용자와 합의로 **adr-013 역할 재분리**: 판정=규칙(ZoneJudge, 경계+히스테리시스, 모순 구조적 불가),
> NN=심박 동역학 모델(t+30/60 예측, RMSE 8.3/14.7bpm = baseline 절반) → **Zone2 목표 페이스 제안** + 선제 코칭.
> 테스트 39건 통과, 폰 설치됨. **다음 = 사용자 시뮬 체험 피드백 → 커밋 → (아침) 실주행 필드 테스트**.

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
- (불일치, **해소됨 — 자율 세션 3**) 워치 %HRmax(190 상수) vs 폰 HRR 기준 불일치 → /zones 동기화로 워치가 폰 프로필 prior 경계를 사용(실기기 확인).

**★ 필드 테스트 준비 완료 (2026-07-03 저녁, 내일 아침 실주행)**: 런북 = **FIELD_TEST.md**.
- 폰 앱 UX 수정: edge-to-edge 시스템 바 침범 수정, **코칭 생성(LLM ~2초) 비동기 분리**(샘플 루프 멈춤 해소, prewarm 추가), 러닝 중 화면 유지.
- 시뮬 정합성: HR 최대심박 클램프 + 시뮬 몸을 사용자 프로필에 고정(테스트 15건 통과).
- **wear 서비스화**: sensor-poc 검증 패턴 이식 — RunService(포그라운드, ExerciseClient) + RunBus. 화면off HR 측정/전송 지속 목적. 빌드 성공, **실기기 미검증(내일 1순위)**.
- **필드 데이터 수집(spec-012 Draft)**: RunLogger(1Hz JSONL, meta/s/e, adb pull 회수) + `ml/analyze_runlog.py`(QA 매핑 분석). **실기기 AC1 검증 완료**(시뮬 세션 → meta1+s1800+coach8+end1).
- 실기기 첫 실측: **Gemini Nano 코칭 실동작 확인**(coachSource=llm), LLM 지연 1.1~2.7초(중앙값 1.3초), warmup 전 규칙 폴백 정상.
- **방향 잠금 가드 구현(같은 날 해소)**: 필드 실측에서 "초과" 상황에 "힘내세요!" 같은 무방향 LLM 문구 발견 → `DirectionGuard`(모순 기각 + 방향 표현 필수, 좁은 명령어/넓은 단서 2단계 어휘) + 프롬프트에 방향 표현 강제 + 기각 카운터를 필드 로그 end 이벤트에 기록. 실기기 LLM 출력을 테스트 케이스로 사용(단위 테스트 5건, 총 20건 통과). adr-002 출력 가드가 길이/형식 → 방향까지 확장됨.

**★ 자율 세션 2 (2026-07-03 밤, 사용자 위임)**:
- **프로필 factor prior (adr-012/spec-013, DP2 완성)**: 키/몸무게 + 체형/러닝수준/주간빈도 5단계 칩 UI,
  BMI 자동 제안(대한비만학회), factor→HRR 오프셋(문헌 근거)+clamp(이중 계상 방지), 극단/불일치/RHR모름 시 σ0 확대.
  RHR 0=자동(수준/빈도 기반 모집단 추정). Personalization prior 연결. Home 표기 통일.
  `ml/prior_experiment.py`: **세션 0 오차 -42%(4.34→2.51bpm), 저품질 관측 수렴 2→1세션, capture=0 downside +0.08bpm**.
  실기기: 칩 탭 → 미리보기 즉시 갱신 스크린샷 검증. 테스트 28건.
- **개인화 안전 가드**(사용자 실기기 관찰 "HR 160인데 Zone2" 반영): 세션 내 μ ±10bpm, uFrac ≤ 0.80.
- **report-003(인증 최종 보고서)**: DP1(판정)/DP2(콜드스타트+적응)/DP3(LLM 배치) 후보-트레이드오프 서사,
  Appendix(지표 근거/오프셋 표/DP↔ADR 매핑). 씬스틸러 목차 전체 덱.
- **워치 실기기 검증(adb 무선)**: 원형 레이아웃 튜닝(BOX_ALL 제거 — 버튼 잘림/페이스 줄바꿈 해소),
  권한 3종 플로우, RunService **화면off 35초 후 포그라운드 유지+타이머 연속** = adr-009 실증.
  잔여: 착용 상태 HR 스트림(내일 필드), 워치 존 기준을 프로필 HRR로 정렬.

**★ 자율 세션 3 (2026-07-03 밤, 이어서)**:
- **(치명 결함 발견/수정) Data Layer appId 불일치**: wear(com.zone2runner.wear) ≠ app(com.zone2runner.app)이면
  메시지 라우팅 자체가 안 됨(sensor-poc는 주석까지 있었는데 실앱 미적용) → 워치→폰 HR이 필드에서 조용히
  실패했을 것. wear appId를 com.zone2runner.app으로 통일(네임스페이스 유지), 워치 재설치+권한 재부여.
- **워치-폰 존 기준 동기화**: app ZoneSync(홈 진입 시 /zones 푸시) + wear ZoneSyncService(수신/저장) +
  Zones 재구성(Z2=폰 prior와 동일, 폴백 %HRmax). **실기기 e2e 확인**(워치 prefs에 131~142/176 수신).
- 문서 정합성 현행화(spec-001 추적표/architecture-overview/spec-010) — 커밋 a01a45c.

**★ 사용자 피드백 세션 (2026-07-03 저녁, 커밋 6bd4920~74d678c)**:
- **러닝 대시보드 재구성**(사용자 요청): 지도 축소(0.7), **ZoneBandView**(Zone2 구간 색 밴드 + HR 마커 +
  하한/상한/최대 라벨, 개인화 갱신 시 밴드 이동) + "목표 126~138 · n bpm 초과" 이탈 텍스트,
  판정 요소 5타일(경사/케이던스/**보폭**/드리프트/기온). 기온=Open-Meteo 1회 조회(참고 정보 — 판정 특징 아님, spec-011 명시).
- **드리프트 표시 재정의**(사용자 질문 "50%가 근거 있나"): 기존 표시는 MLP 특징 feat[5](hr/pace,
  강도 변화에 지배+워밍업 기준선)를 그대로 노출한 것 → 표시용은 HR/속도(EF 역수)+안정 후(3~4분) 기준선으로
  재정의(통상 0~10%, 실기기 -9.1% 확인). 리포트 cardiacDriftPct도 동일 수정. **특징 벡터/모델은 불변**.
- **케이던스(spm) 코칭 축 승격**: CoachContext에 spm, LOW<162/HIGH>190 기준(근거: Heiderscheit 2011
  +5~10% 빈도↑→관절 부하↓, Schubert 2014, Daniels 180 — spec-005 부록), RuleCoach 팁 + LLM 프롬프트.
  **DirectionGuard는 폼 절(발걸음/보폭 등)을 방향 판정에서 제외**(방향어는 폼 절 밖 규약).
  **워치 실측 케이던스**: ExerciseClient STEPS_PER_MINUTE → /spm → LiveRunSource 실측 우선/추정 폴백
  (실센서 spm이 페이스 추정 가짜값이던 공백 해소). 보폭=속도/케이던스: 라이브 타일 + 리포트 평균(코덱 하위 호환).
- 홈 타이틀 "AI Specialist / Zone2 Runner"(사용자 요청). 테스트 31건 통과.
- 실기기 검증: 대시보드/밴드/5타일/기온 27℃/LLM 케이던스 코칭 문장 생성까지 스크린샷 확인.
- **폰+워치 모두 최신(74d678c) 설치 완료(16:05), 워치 권한/존 동기화 유지 확인** — 내일 아침 바로 러닝 가능.

**★ 운동생리학 근거 세션 (2026-07-03 밤, 커밋 09a5ee6)**: 사용자 문제제기 "정답 없는 개인 Zone2 한계를
제약에 명시하고, 왜 이 방식/왜 AI인지 생리학 리서치로 설득해야 한다. 새 발견은 적용도".
- **웹 리서치 반영**: Zone2=LT1(유산소 임계) 부근, 참값은 랩 젖산/가스교환만 → 손목 기기로 원천 불가.
  %HRR>%HRmax, 토크테스트≈VT1, DFA-α1(HRV) 0.75=유산소 임계(Rogers 2021).
- **신설**: `arch/zone2-physiology-and-estimation.md`(정의/참값부재/추정지형/선택근거/왜AI/한계),
  spec-001 **C04**(참값 부재=문제의 성질), report-003 생리 슬라이드+인용 Appendix E.
- **적용**: 토크테스트 자가관측(Personalization.observeTalkTest) — 디커플링 편향 보완 무비용 채널.
  Run 화면 '대화 가능? 편함/애매/벅참' 칩. DFA-α1은 향후 관측 채널로 문서화. 실기기 렌더 확인.
- **왜 AI인가**를 생리학 논리로 재정립: 정답(라벨) 없음→시뮬레이터 라벨+라벨없는 Bayesian,
  비선형 다신호 경계→MLP, 개인·시변→온라인 적응. "화려함 아니라 정공법".

**★ 판정 아키텍처 재설계 (2026-07-03 저녁, adr-013 Accepted — 사용자 합의 후 진행)**:
- **발단**: 시뮬 실행 중 "심박 170(지속 180)인데 판정=미달" — 순전파 재현으로 원인 확정:
  판정 MLP가 시뮬 라벨 순환으로 추세(dHR)가 절대 위치를 압도하는 지름길 학습(dHR≤-0.5면 상한
  +0.30HRR 초과에도 미달). 기존 지표(acc 0.828/QA1 0.996)는 시뮬 라벨 채점 = 자기참조였음.
- **구조 문제**: "진실 두 개"(개인화 경계 vs 별도 판정기) + 라벨/지표 자기참조. 사용자 지적
  "모호한 설계로 구현 먼저"가 정확 — spec이 "판정=무엇에 대한 분류인가"를 정의 안 했음.
- **재설계(adr-013)**: 판정=규칙 `ZoneJudge`(지속심박 vs 개인화 경계 ±2bpm 히스테리시스 —
  밴드와 같은 경계라 모순 구조적 불가, 속성 테스트 2만회). NN 전수조사(라벨 정직성/비선형/제품가치)
  결과 유일 합격지 = **심박 동역학 모델**(spec-014): 8특징→[t+30,t+60] 심박 회귀, 라벨=실제 미래
  심박(순환 없음). RMSE 8.30/14.65bpm vs persistence 15.08/26.69 (AC2 O), 페이스 단조성 위반 1.3%(AC3 O).
- **제품 출력**: ① **Zone2 목표 페이스 제안**(모델 역질의 — "지금 6'30"로 뛰세요", 경사/피로 자동 반영)
  ② 선제 코칭("이대로면 곧 초과 → 미리 낮춰요", CoachContext.preemptive, LLM 프롬프트 반영)
  ③ (후속 유보) 예측오차→prior 관측 채널, HR 드롭아웃 브리지.
- **문서**: adr-005/spec-006 Superseded(경위 주석), EXPERIMENT_LOG §8(판정 지표 자기참조 명시+RMSE 체계),
  spec-014 신규. Zone2Classifier는 LEGACY 표기로 보존(결함 재현 회귀 테스트 유지).
- **부수 개선(같은 날)**: 밴드 마커=지속 심박(60초)+순간 틱 병기, LLM 프롬프트/경로 화면 노출(시뮬/목 전용),
  Zone2Classifier.guard(임시 가드레일 — 규칙 판정 전환으로 역할 종료, 테스트로만 남음).
- **미커밋**: 이 세션 전체(마커/프롬프트 노출/adr-013/spec-014/ml/앱 통합). 사용자 시뮬 체험 후 커밋 예정.
- **워치 미갱신**: 이번 변경은 폰 앱만. 워치는 74d678c 그대로.

**토크테스트 자동화 논의 (2026-07-03, 보류 — 사용자 고민 중)**: 현 폰 화면 3칩은 러닝 중 사용 불가(데모용).
검토한 안 — A) 음성 숨참 분류 NN: **기각 권고**(라벨 없음 = adr-013과 같은 함정, 실외 노이즈).
A′) CTT(숫자 세기) 자동화: 검증된 프로토콜 + OS 내장 ASR(우리가 학습 안 함 = 정직), 리스크는
달리며 숫자 세기 UX/인식률 — 워치 단독 미니 PoC로 선검증 필요. B) 워치 3버튼 + 능동 프롬프트:
확실/저비용, "σ 크고 지속HR이 경계 ±5bpm일 때만 질문"(능동 학습 정책 — 어느 안이든 이 정책은 공통).
Claude 추천 = B 먼저, A′는 PoC 후. 인증 서사 주의: A′의 AI는 ASR이 아니라 **질문 정책+Bayesian 융합**.
러닝 중 손목에서 얻을 추가 자동 신호는 음성뿐(호흡수=수면 전용, HRV RR=러닝 중 품질 불가).

**다음 (사용자 체험 후)**: 시뮬 피드백 반영 → 커밋 → FIELD_TEST.md 실주행 → "필드 로그 분석해줘"
(+ 필드 로그로 동역학 모델 실데이터 채점 가능 — 라벨이 관측값이므로).

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
