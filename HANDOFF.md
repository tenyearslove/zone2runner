# HANDOFF — 작업 이어가기

이 문서는 다른 환경(집 등)에서 repo를 clone해 작업을 이어가기 위한 컨텍스트다. Claude Code 로컬 메모리(`~/.claude/`)는 git으로 전파되지 않으므로, 핵심 컨텍스트를 여기에 담는다.

> **새 세션에서 이어가는 법**: Claude에게 "HANDOFF.md 읽고 이어가자"라고 하면 된다.

최종 갱신: 2026-07-06 세션 B (심박 예측 시뮬-MLP → 생리 ODE 재설계 adr-020 / 앱 설정 spec-021 / 백서 factor·신뢰원칙 / 자체 NN 도입은 집에서 결정 — 아래 ★★ 블록)

> **★ adr-016 (2026-07-04): AI≠NN, 문제별 도구 선택 — 최종 구조.**
> - **심박 예측 = NN(딥러닝, HrDynamics)**: 1Hz 스트림 → 30/60초 뒤 심박. 선제 코칭/페이스 제안. 문헌 athlete_hr_predict(LSTM)/Zhu 2025.
> - **개인 Zone2 범위 = 온라인 Bayesian(Personalization+토크테스트)**: 진짜 개인화 학습(신경망 아님). 왜 NN 아닌가 = 소수/온라인 라벨/콜드스타트/불확실성.
> - **판정 = 규칙(ZoneJudge)**: 지속심박 vs 경계+히스테리시스. 결정론.
> - **강등**: 역치 추정 NN(adr-014/spec-015)은 개인화 경로에서 제거(기록 보존). 판정 MLP(adr-005/spec-006)는 Superseded(adr-013).
> - 문헌 정직: 심박예측만 논문 연결. 역치 논문(Etxegarai/Zhu)=우리가 NN 대신 Bayesian 택한 "대조군"(우리 방법 근거로 인용 금지).
> - **Zone2 기준 = %HRmax**(상단 최대심박 70%=LT1). %HRR 재보정(실측 maxHr 과대평가 해소).
> - DP 후보(신규): 엔진/러너 분리(VirtualRunner+RunSource) = QA5 테스트가능성. 가상러너 폐루프 시뮬.
> - 사용자 방침: 엄밀 증명 불필요, 논문 근거로 그럴듯하면 됨(거짓말만 아니면). UI엔 adr/spec/%HRR/MLP 용어 노출 금지.

> **★ 2026-07-05 저녁 세션 요약 (여기서 이어가기)**
>
> **A. 토크테스트 개선 (본 앱, 배포됨)** — spec-016
> - 3단계 → **5단계**(아주편함/편함/**보통**/벅참/매우벅참). "애매"는 "보통"으로 변경. 관측 z/σ 매핑.
> - **워치도 5칩(2줄)**. 워치→폰 전송에 very_comfortable/very_hard 추가, 폰 매핑 5분기.
> - **워치 프롬프트 타이밍**: 항상표시 X → **Zone2 이상에 30초+ 머물** 때만, 3분 내 안 물었으면, 진동. 10분 폴백.
>
> **B. 음성/호흡 객관화 탐색 → 기각 → 페이스 talk test PoC** — `voice-poc/`(독립 gradle), adr-018/spec-017
> - 개인화 라벨(토크테스트)을 객관화하려 시도. **음향 VAD / ASR 완성도 / YAMNet 호흡감지 모두 온디바이스
>   신뢰 불가**(YAMNet은 폰 마이크 날숨을 Beatboxing으로 오분류) → **adr-018 Rejected**. 본 앱은 주관 5단계 유지.
> - 이후 사용자와 **VT1 감별의 올바른 프로토콜** 재정립: 수동 호흡청취는 VT2/탈진에서야 반응(부적합),
>   **말 부하(Talk Test)가 VT1에 민감**. 검증 프로토콜(PMC3772610) = 정해진 문구 낭독 "마지막 예"가 VT1.
> - **voice-poc 현재 상태 = 시간제한 카운팅(처리량) talk test** (spec-017): 20초간 숫자 이어세기 →
>   말한비율/센개수(호흡 검출 불필요, 숨쉬면 처리량↓) + **검증된 3답(예/예…근데/아니오) 라벨 + 원본 WAV 저장**.
>   `filesDir/talktest/`에 학습 데이터 축적. **다음 = 라벨 데이터 모아 소형 분류기 학습(YAMNet 임베딩 전이 or 발화특징)→검증→온디바이스**.
>   (공개 데이터 없음 확인 → 통제 낭독으로 직접 수집. HR(워치)로 자동 라벨 가능.)
>
> **C. 심박 예측 온라인 개인 보정 (본 앱)** — spec-018/adr-019
> - 기본 NN(시뮬 평균 러너) 예측이 개인과 어긋남 → "페이스 유지인데 60초뒤 예측≠실제"를 **온라인 학습(LMS)**해 보정.
>   DP2(Bayesian) 철학을 예측에 적용. 페이스 게이팅(유지분만 학습), 세션 누적(LearnedDynamics), base/corr RMSE 추적.
> - `HrPredictionLearner`/`LearnedDynamics`, RunEngine 통합. **다음 = 세션 반복하며 corr RMSE < base 확인, (선택) 오프라인 로그 재학습**.
>
> **D. 리팩토링/기타**: 중복 상수 단일화(uFrac clamp/Tanaka/mps/팔레트), 데드코드 제거. 제품명 **Zone2Runner**(공백 없음).
> 현재 배포 모델 RMSE **t+30 14.01 / t+60 20.45**. 테스트 앱 47건 / voice-poc 12건 통과. 폰(SM-S948N)/워치(SM-L330) 설치됨.
>
> **다음 우선순위**: (1) voice-poc talk test 실측 보정 + 라벨 데이터 수집 → 모델 학습, (2) ✅예측 보정 효과 검증 완료(EXPERIMENT_LOG §12: 60초 RMSE 평균 29.1→13.8bpm, 4/4 러너 개선), (3) 실주행 필드 테스트.
>
> **★ 2026-07-06 세션**: 게이지 3마커(평균/실측/예측)+범례, 코칭 프롬프트 외부화(coach_prompt.json)+맥락 수치 확장, 예측 온라인 보정 검증(§12: 60초 27→12.5bpm, 7/7), 러닝화면 스크롤, **VirtualRunner 대폭 강화(spec-019: 지형/피로/기온/서지/센서아티팩트/랜덤샘플러)**, **경사 거리창 회귀(GPS 고도 노이즈 강건, SlopeEstimator)**, WHITEPAPER 최신화(online≠인터넷 명확화).
> **★ ML 정비(2026-07-06)**: 죽은 NN 2개를 앱에서 제거 — **Zone2Classifier(판정 MLP, adr-013로 규칙 대체)** + **ThresholdEstimator(역치 NN, adr-016로 Bayesian 대체)** + 딸린 에셋(zone2_mlp/threshold_mlp.json)/테스트/죽은 메서드. **앱 로드 모델 = hr_dynamics 하나로 단일화**. 근거는 adr-013/016·EXPERIMENT_LOG·git 이력 보존. 실기기 정상 동작 확인. 아래 213~247행 등 과거 세션 기록의 Zone2Classifier 언급은 그 시점 스냅샷(현행 아님).
> **★ 프로필 관리 + 개인화 시각화 + 설명용이성(2026-07-06, spec-020/QA6)**: 사용자 요청 —
> (1) **다중 프로필**(Profiles 레지스트리, 프로필별 네임스페이스, 무손실 하위호환=기본프로필은 기존 pref 키, 생성/전환/이름변경/삭제/개인화초기화),
> (2) **개인화 진행 시각화**(PersonalizationView: 초기 공식 vs 현재 학습 Zone2 밴드 겹쳐 그려 상단 이동량±bpm + 세션별 스파크라인. LearnedZone에 uFrac 이력/말하기관측수/σ 저장),
> (3) **AI 설명(설명용이성 QA6)**: PersonalizationExplainer — 사실은 규칙이 확정(무결성), Gemini Nano가 표현만(코칭 DirectionGuard 철학), 미가용 시 규칙 폴백. spec-002에 QA6 신설(Utility Tree/우선순위/DP매핑/검증현황 일관).
> 실기기 확인: 프로필관리 카드 렌더 + Gemini Nano가 규칙 팩트를 지어낸 수치 없이 자연어 설명. 테스트 앱 60건. 폰 설치됨.
> **★ +(2026-07-06 이어서)**: 홈에 러닝 프로필 선택기(시작 전 프로필 고름). ML 정비(죽은 NN 2개 Zone2Classifier/ThresholdEstimator 제거 → 앱 모델=hr_dynamics 단일). **기온을 코칭 맥락에 반영**(더위 28℃+, 방향은 규칙·기온은 표현 재료, adr-002/008 유지, DirectionGuard 제외절).
> **★ 토크테스트 단측 관측 정정(사용자 지적)**: '편한데 미달'에서 편함이 경계를 잘못 끌어내리던 결함 → **단측**(편함=임계≥현재→올림만/벅참=임계≤현재→내림만/보통=점). 홈 문구도 정정. spec-016 FR2b. 회귀 테스트 2건. 테스트 앱 63건.
> **★ 설계 결정(DP) 종합 재작성 (report-004)**: 사용자 요청 — 기존 DP 재검토 + 신규 도출을 하나로. 두 축 = ①무엇으로 푸는가(도구선택 DP0~4) ②어떻게 믿는가(DP5 검증=폐루프 가상러너/QA5, DP6 설명=규칙사실+LLM표현/QA6, DP7 무결성 가드레일 횡단). 관통원칙 "규칙이 사실/방향/정답 확정, 학습·생성은 그 안에서". WHITEPAPER도 최신화(단측/기온/QA6/프로필관리).
>
> **★★ 2026-07-06 세션 B — 심박 예측 재설계(ODE) + 앱 설정 + 백서 factor 명시 [여기서 이어가기]**
> - **앱 설정 화면(spec-021)**: 코칭 빈도 5단계(CoachCadence, 보통=기존 20/60/40초) + 음성on-off/속도 + 선제코칭 + 더위코칭 + 화면유지. SettingsActivity + 홈 '설정' 버튼. 폰 설치됨.
> - **백서 factor 명시**: 심박예측 입력 factor 7종 계산식/표준화, Zone2 경계 prior factor(러닝수준 지배)/관측 비중=정밀도(1/σ²)/판정 히스테리시스 산술. 예측 유리성(τ 리드타임) 논거 보강.
> - **★ 심박 예측 = 시뮬-학습 MLP → 생리 ODE 재설계 (adr-020, 채택/구현완료)**: 사용자 지적 — 시뮬레이터가 이미 손으로 쓴 2상 ODE인데 그걸 흉내내는 MLP를 학습시키는 건 순환/공허(보고 RMSE=시뮬 재현충실도). 문헌(Apple/Nature npj 2023, MDPI 2024)의 생리ODE+파라미터개인화 골격을 따름. `HrOdeModel`(hSS=hNow+τ·dHR, mono-exp+드리프트, 온라인 τ/드리프트/수요맵 추정, 페이스 해석적 역산). **폐기**: HrDynamics.kt/HrPredictionLearner.kt/hr_dynamics.json/train_hr_dynamics.py(git 이력 보존). LearnedDynamics는 ODE 파라미터 5개 저장. 검증: τ 온라인추정 30→19.9 수렴, 폐루프 60초 RMSE ≈2.4bpm. **테스트 66건**. 폰 설치+푸시 완료. 문서 전면 갱신(백서 5-1/ML지도/Part0, arch, spec-014 Superseded 배너).
> - **★ 신뢰 원칙 도출(사용자 지적, 아직 백서 미반영 — 다음에 문서화)**: "학습 컴포넌트는 사용자가 정답을 직접 검증 못하는 곳에만 안전. 사용자가 곧 정답센서인 곳(토크테스트=내가 얼마나 힘들게 말했나)에 AI 두면 오판이 즉시 들통나 신뢰 붕괴." → 토크테스트 오디오 분류기 NN 폐기 확정. NN 안전지대=미래 심박 예측. **TODO: 이 원칙을 백서 설계논지로 승격.**
> - **★ 미결정 — "자체 학습 NN을 넣을까"(집에서 결정)**: 인증 필수 아님. 동기=커리큘럼에 NN 많음. 데이터 없음이 근본 제약(우리 데이터 0). 정직한 유일 길=공개 실데이터. 후보: (a) **athlete_hr_predict**(MIT, 실 Garmin 러닝) = **~50세션·단일 러너·1초·심박/속도/케이던스/경사·초소형 LSTM 149param·20초 MAE 2.21bpm**. 작지만 실데이터 → NN 생애주기 연습 + **ODE vs LSTM 벤치마크** + ODE prior 실측 보정엔 충분. 단 단일 러너라 "모집단 백본"엔 부적합. (b) **FitRec/Endomondo**(Ni WWW2019, 다중 사용자 ~25만 워크아웃) = "모집단 LSTM 백본 + 온라인 개인 어댑터(고정 백본+개인층, 파국적망각 회피)" 서사를 실데이터로 성립시키려면 이쪽 필요. **다음 단계: (a)/(b) 중 택1 → 해당 데이터셋 접근/라이선스/포맷 확인부터.** 개인화 뼈대는 백본무관(ODE든 LSTM이든 고정 모집단 + 온라인 개인층)이라 기존 구조와 합쳐짐.

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

## 3. 핵심 설계 결정 (DP) — ★ 재구성 2026-07-04 (adr-016: AI≠NN, 문제별 도구 선택)

| DP | 결정 | 도구 | 문서 |
|:---:|------|------|------|
| DP0 | Watch-Phone Hybrid (Watch=수집, Phone=AI) | - | adr-001 |
| **DP1** | **가까운 미래 심박 예측** | **NN(딥러닝, HrDynamics)** | adr-013/016, spec-014 |
| **DP2** | **개인 Zone2 범위 학습** | **온라인 Bayesian**(토크테스트 누적) | adr-004/016, spec-004 |
| **DP3** | **Zone2 판정**(범위 이탈 여부) | **규칙**(히스테리시스) | adr-013/016 |
| DP4 | LLM 코칭 = 규칙 방향 + LLM 표현 + 가드 | On-device LLM | adr-002 |

### AI 컴포넌트 — "AI≠NN, 데이터에 맞는 도구" (adr-016)
1. **심박 예측 NN (DP1)** — 교육요건 충족 실제 NN. 1Hz 심박 스트림(연속·다수) → 30/60초 뒤 심박 회귀.
   선제 코칭("곧 초과")/페이스 제안. 문헌 근거(Zhu 2025 RNN, athlete_hr_predict LSTM). `HrDynamics`.
2. **Bayesian 개인화 (DP2)** — 딥러닝 아님. 토크테스트(정답에 가장 가까운 라벨)+디커플링을 세션마다 누적,
   `LearnedZone`에 저장→다음 세션 prior. **이 앱의 진짜 개인화 학습**(데이터 쌓일수록 정확). float 산술.
3. **규칙 판정 (DP3)** — 경계 vs 지속심박. 결정론, 모순 불가.
4. **On-device LLM 코칭 (DP4)** — Gemini Nano.

### 왜 개인화에 NN을 안 썼나 (핵심 리즈닝 — adr-016)
개인 Zone2 학습은 (1) 개인당 라벨 소수→과적합, (2) 정답(토크테스트)이 온라인으로 하나씩→재학습 부적합,
(3) 콜드스타트 필요, (4) 불확실성 관리 필요 → **NN이 틀린 도구, 온라인 Bayesian이 맞는 도구.** 연속·다수
데이터(심박 예측)엔 NN. 판정은 결정론이라 ML 불필요. "AI 썼다"가 아니라 "문제별로 맞는 도구를 골랐다"가 요체.

> **폐기 이력**: 구 DP1(규칙+개인화 판정, adr-003)/구 DP4(MLP 판정기, adr-005)는 판정 NN 라벨순환 결함으로
> Superseded(adr-013). 역치 추정 NN(adr-014/spec-015)은 개인화가 Bayesian 전담이라 강등(adr-016).

## 4. 문서 현황

- spec-001~011, adr-001~011, arch/architecture-overview, report-001~002. ml/(코드+EXPERIMENT_LOG/COMPARISON/AI_EXPLAINED), llm-verify/, sensor-poc/, app/, wear/.
  - 신규(2026-07-02): spec-007(기록/리포트 FR6), spec-008(안전 C03), spec-009(프로필/RHR FR1), ml/AI_EXPLAINED.md(쉬운 AI 설명, 개인용).
  - 신규(2026-07-03): spec-010(워치 대시보드), spec-011(폰 앱), adr-009(백그라운드)/010(지도)/011(추론 런타임), **STUDY_GUIDE.md(루트 — 프로젝트 전체+AI를 바닥부터 배우는 개인 학습서, 실습/Q&A 포함)**.
  - 신규(2026-07-04~05): **WHITEPAPER.md(루트 — 바닥→상위 완전이해 백서)**, spec-013~018, adr-012~019, report-003(발표 원고).
    - spec-014/adr-013(심박예측 NN=판정 재분리), spec-016(토크 5단계), spec-017(페이스/카운팅 talk test PoC),
      spec-018/adr-019(예측 온라인 개인 보정), adr-016(AI≠NN 도구선택), adr-017(HRV 유보), adr-018(음성/호흡 객관화 기각).
  - **voice-poc/**(독립 gradle, phone+wear): 음성/호흡 talk test 실험. README에 접근 변천 기록. YAMNet 모델은 git 제외(README에 다운로드법).
- **읽기 진입점**: **WHITEPAPER.md**(전체 완전이해) → arch/architecture-overview.md → adr-016(도구선택) → 각 spec/adr. 개인 학습은 **STUDY_GUIDE.md**.
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

**워치 실앱 착수 (2026-07-02, spec-010)**: `wear/` — sensor-poc와 분리된 실제 Zone2Runner 워치 앱.
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
- 홈 타이틀 "AI Specialist / Zone2Runner"(사용자 요청). 테스트 31건 통과.
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
