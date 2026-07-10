# Spec-025: 관측 데이터 분석 엔진 (FR3) — 완전 구현 설계

- **상태**: Implemented (2026-07-10 — 10단계 완전 구현, 단위+통합 테스트 106건 통과, APK 빌드 성공. AC-1~9 충족)
- **날짜**: 2026-07-10
- **관련 ADR**: `arch/adr-024-fr3-hr-prediction-drop-to-observation-analysis-engine.md`(예측 드롭→분석 엔진), `arch/adr-025-ai-method-selection-no-nn.md`(AI≠NN, 분석=통계·회귀)
- **관련 Spec**: spec-001 §FR3/§FR4/§FR5-6, spec-004(개인화), spec-005(코칭), spec-007(리포트), spec-008(안전 가드)
- **강의 매핑**: 강의 AI System 아키텍처의 **분석 서비스(Analysis Service)** 컴포넌트 = 서빙 로그→지표 산출→모니터링. `framework/ai-system-and-quality.md §1-3`.

## 목표

라이브 관측 신호(1Hz 심박/페이스/케이던스/고도)와 FR1 프로필을 입력으로, **참값 없이 온디바이스로 자기참조 파생지표를 도출**하는 엔진을 구현한다. 구 심박 예측(HrOdeModel)을 대체한다. 지표별 작은 단일책임 모듈을 레지스트리에 등록해 조립하며(OCP), 판단선은 개인 k·σ(마법상수 금지 ★, CLAUDE.md).

## 범위

**포함**: 분석 엔진 코어(인터페이스/레지스트리/조립), 파생지표 5모듈, 정속 게이트/노이즈 게이팅, 개인 k·σ, RunEngine 결선, 소비처 계약(FR4/5/6), SafetyGuard(spec-008 결선), 예측 코드 완전 제거, 리팩터(RollingLinearRegression/SignalWindow), 시뮬 폐루프 검증.

**제외**: 코칭 문구 튜닝(spec-005 소관), 리포트 UI 상세 디자인(spec-007), 개인화 Bayesian 수식(spec-004 유지), Zone 경계 산정(FR1/FR4), LLM 런타임(adr-007).

---

## 1. 아키텍처 — 모듈 분해 (SOLID/GRASP)

새 패키지 `pipeline/analysis/`. 각 지표는 공통 인터페이스를 구현해, **지표 추가 = 레지스트리에 한 줄 등록(OCP, 엔진 무수정)**.

```kotlin
// 엔진이 매 평가 시 각 지표에 넘기는 불변 입력 스냅샷
data class AnalysisInput(
  val tSec: Int,
  val profile: Profile,
  val boundary: Zone2Boundary,
  val window: SignalWindow,          // hr/pace/spm/slope 롤링 뷰(FeatureExtractor 어댑터)
)

// 파생지표 = 種類A(도출값). 도메인 타입 재사용, 타입 있는 결과 산출.
interface AnalysisMetric {
  val id: String                     // "drift_slope" | "submax_hr" | "hrr" | "gap" | "cadence_stability"
  val mode: MetricMode               // REALTIME | SESSION_END | BOTH  (§FR3 시간모드)
  fun onTick(input: AnalysisInput): MetricSample?          // 실시간(null=미준비/게이트아웃)
  fun onSessionEnd(series: List<AnalysisInput>): MetricSample?  // 세션 범위
}

enum class MetricMode { REALTIME, SESSION_END, BOTH }
enum class Trend { UP, FLAT, DOWN, UNKNOWN }
data class MetricSample(
  val id: String, val value: Double,
  val se: Double? = null, val r2: Double? = null,
  val trend: Trend = Trend.UNKNOWN, val gated: Boolean = false, val note: String = ""
)

// GRASP Controller — 낮은 결합, 등록된 지표만 실행
class AnalysisEngine(private val metrics: List<AnalysisMetric>) {
  fun onTick(input: AnalysisInput): List<MetricSample>       // REALTIME/BOTH만
  fun onSessionEnd(history: List<AnalysisInput>): List<MetricSample>  // SESSION_END/BOTH
  fun latest(id: String): MetricSample?                     // 최근 실시간 값 조회(코칭용)
}
```

**공유 원시모듈(DRY/DIP)**:
- `RollingLinearRegression` — `SlopeEstimator.regressionSlope()`의 OLS(slope/intercept/SE/R²)를 추출한 순수 계산 모듈. 경사(거리창)와 드리프트(시간창)가 공유. `SlopeEstimator`는 이걸 사용하도록 리팩터.
- `SignalWindow` — `FeatureExtractor`의 hr/pace/spm/slope 버퍼 위 얇은 읽기 뷰(mean/CV/window slice). 지표가 FeatureExtractor 내부에 결합되지 않게 경계 제공.

**강의 AI System 매핑**: `AnalysisEngine` = 분석 서비스(서빙 로그→지표). `SignalWindow`+`OutlierGuard`+정속 게이트 = 입력 가드레일. 지표 결과가 설명 서비스(FR6)/제어(FR5 트리거)로 흐른다.

---

## 2. 파생지표 5모듈 (전부 구현)

| 지표(모듈) | mode | 입력 | 계산 | 출력 | 게이트 | 문헌 | 소비처 |
|---|---|---|---|---|---|---|---|
| `DriftSlopeMetric` | BOTH | 정속창 hr vs t | RollingLinearRegression(hr~t) | 기울기 bpm/min, SE, R², Trend | 정속 게이트 | Coyle 2001 | FR5 트리거 + FR6 |
| `GapMinettiMetric` | REALTIME | pace, slope% | Minetti 5차 다항식 정규화 | 경사보정 페이스 min/km | slope∈[-45%,+45%] | Minetti 2002 | FR5 컨텍스트 + FR6 |
| `CadenceStabilityMetric` | BOTH | spm 창 | σ(spm) | 케이던스 σ, MDC 위반 flag | MDC 2.53spm | JOSPT 2016 | FR5 트리거 + FR6 |
| `SubmaxHrMetric` | SESSION_END | 고정페이스 빈 hr | 빈별 평균 HR | 대표 빈 submax HR@pace | 빈 최소 체류 | Sports Med-Open 2023 | FR4 추세 + FR6 |
| `HrrMetric` | SESSION_END | 노력종료 후 hr | 종료시 HR − 60s 후 HR | 회복폭 bpm | 노력종료 감지 | Daanen 2012 | FR6 + FR4 |

**세부**:
- **DriftSlopeMetric**: REALTIME은 최근 `W_DRIFT_RT`(180s) 정속 롤링창 회귀 기울기; SESSION_END는 세션 최장 정속 구간 기울기+R². `slope > k·SE`면 "유의 상승"(FR5 트리거). 디커플링(HR/pace 후반/전반 비)은 부가 note로만(Conconi 편향, spec-004).
- **GapMinettiMetric**: `Cr(i)=155.4i⁵−30.4i⁴−43.3i³+46.3i²+19.5i+3.6` (i=경사 소수). 평지 대비 비로 페이스 정규화. 種類C 측정 다항식(발명 상수 아님).
- **CadenceStabilityMetric**: 창 표준편차. `σ > MDC(2.53)`면 불안정(피로 신호 후보, FR5 트리거).
- **SubmaxHrMetric**: 페이스를 `PACE_BIN`(0.25 min/km) 빈으로, `MIN_BIN_SEC`(120s) 이상 체류한 빈만 대표 평균 HR 산출. 세션 간 동일 빈 비교로 체력 추세(FR4). 정규화는 %HRR(FR1 안정·최대심박) 옵션.
- **HrrMetric**: 노력종료(§3 감지) 후 `HRR_WINDOW`(60s) 심박 하강폭. 감지 없으면 산출 안 함(null).

---

## 3. 설계 선택 상수 (種類C — 문헌 위 선언, 필드 튜닝)

마법상수가 아니라 **문헌 근거 있는 출발점**으로 정직히 선언. 코드엔 `AnalysisConfig`로 모아 상수 하드코딩 금지.

| 상수 | 출발값 | 근거/의미 | 튜닝 |
|---|---|---|---|
| `CV_STEADY` (정속 판정 페이스 변동계수) | 0.05 | 정속=드리프트 유효 조건, 문헌 정속 프로토콜 관례 | 필드 |
| `W_DRIFT_RT` (드리프트 롤링창) | 180s | 드리프트는 10~20분 규모(Coyle), 실시간 기울기는 3분 창 | 필드 |
| `HR_SLOPE_BAND` (노이즈 게이트 |ΔHR/Δt| 상한) | 종모수 | 급변=손목PPG 부정확/비정속 → 게이트아웃 | 필드 |
| `PACE_BIN` / `MIN_BIN_SEC` | 0.25 min/km / 120s | 서브맥시멀 동일 강도 비교 해상도 | 필드 |
| `HRR_WINDOW` / 노력종료 임계 | 60s / pace<0.7×평균 10s 지속 | HRR 1분 관례(Daanen), 연속 러닝 종료 대리 | 필드 |
| `MDC_CADENCE` | 2.53 spm | JOSPT 2016 최소검출변화 | 기기별 재보정 |
| `k` (판단선 배수) | 2.0 | k·σ 오경보 상수 | 필드 |

**손목 PPG 노이즈 게이팅(adr-024)**: 지표는 (i) OutlierGuard 통과 + (ii) 정속 게이트(CV_STEADY, HR_SLOPE_BAND) 를 만족하는 표본만 사용. 고강도/급변 구간은 `gated=true`로 제외(정속 러닝서만 정확).

---

## 4. 개인 k·σ 노이즈플로어 (種類B — 온라인 학습)

각 지표는 자기 **잔차/단기변동의 σ̂를 EWMA로 온라인 추정**(중립 prior 출발, 그 사람에게 수렴). 판단선 = `k·σ̂`, bpm 상수 아님 → 사람마다 자가 보정. 세션 간 `LearnedZone`에 지표별 σ̂ 저장(프로필 네임스페이스). 이는 CLAUDE.md 種類B(학습값)이자 강의 기능적응성.

---

## 5. 소비처 계약 (FR4/5/6) + SafetyGuard

### 5-1. FR5 반응형 코칭 (예측 선제 경로 대체)
- 트리거: `DriftSlopeMetric` 유의 상승(`slope > k·SE`) 또는 `CadenceStabilityMetric` MDC 위반 → `maybeReactiveCoach`(기존 overdue 로직과 `cadence.minGapSec` 공유).
- 컨텍스트: `GapMinettiMetric`(오르막 보정 페이스)은 트리거 아닌 사실 컨텍스트.
- `CoachContext`: `preemptive/predictedHr60` 제거 → `driftSlope/gapPaceMinKm/cadenceSigma` + `reason(DRIFT_RISING/GAP/CADENCE/ABOVE/BELOW)`. DirectionGuard/폴백/LLM 구조 불변(방향 아닌 절로 삽입).

### 5-2. FR6 리포트
- 5지표를 세션 리포트에 그래프/요약. 기존 `RunReport.cardiacDriftPct`+`Charts.kt`(TimeSeriesChartView) 재활용, 지표 카드 추가. 설명 서비스(spec-023): 드리프트→코칭 인과, 판정 왜.

### 5-3. FR4 관측 채널(약)
- `SubmaxHrMetric`/`DriftSlopeMetric` 세션 추세 → 개인화 보조 입력. 경계 앵커는 말하기 테스트가 지배(spec-004). 디커플링은 Conconi 편향으로 다운웨이트.

### 5-4. SafetyGuard (spec-008 결선) — 별도 모듈, LLM 우회
- `pipeline/SafetyGuard.kt`: 결정론 규칙. `HR ≥ SAFETY_PCT(0.95)·profile.maxHr`가 `SAFETY_HOLD_SEC` 지속 → 즉시 중단/감속 권고(음성+화면), 코칭 LLM보다 우선. `profile.maxHr`/`lastValidHr`만 의존(분석 엔진/LLM 무결합). `LiveState.safetyAlert`로 표면화. 이상치 필터(OutlierGuard) 통과값에만 발동.

---

## 6. RunEngine 결선 + 도메인 변경

- 삭제된 예측 블록 자리에 `val samples = analysis.onTick(input)`; 최근값 보관 → `LiveState`.
- 세션 종료: `finalizeSession`에서 `analysis.onSessionEnd(history)` → `RunReport`에 지표 접기.
- `SafetyGuard`는 `onSample`에서 `maybeCoach`와 병렬/선행.
- `domain.LiveState`: `predictedHr60/recommendedPaceMinKm/predictionWhy` 제거 → `analysis: Map<String,MetricSample>` + `safetyAlert`.
- `domain.RunReport`: `usedModel` 재용도(또는 하위호환 유지) + 지표 필드.
- **RunEngine God-object 축소**: 누적기 분리(`SessionAccumulator` 추출, 선택), 분석은 인라인 아닌 collaborator.

---

## 7. 예측 제거 (완전)

**통삭제**: `pipeline/HrOdeModel.kt`, `data/LearnedDynamics.kt`, `FeatureExtractor.dynFeaturesAt`, `test/HrOdeModelTest.kt`, `test/HrOdeClosedLoopTest.kt`.
**디와이어**: RunEngine 예측블록/`buildPredWhy`/`maybePreemptiveCoach`/`odeParams·predRmse·predUpdates·odeTau` accessors/`usingModel`; `Coach.CoachContext.preemptive·predictedHr60` + `RuleCoach` preemptive 분기; `CoachPrompt` `*_preemptive`·`{pred}` + `assets/coach_prompt.json`; `RunActivity` LearnedDynamics 읽기/쓰기·ODE 메타/자막; `AppSettings.preemptiveEnabled` + Settings 토글; `CoachPromptTest` 예측 단언.

---

## 8. 검증

- **시뮬 폐루프**: `SimulatedRunSource`/`ManualVirtualRunnerSource`(spec-019/022)로 워치 없이 전 파이프라인 구동. 시뮬=구조 디버거(짜고치기 아님, 정확도 심판 아님).
- **단위 테스트**: 각 지표 모듈(경계/게이트/k·σ), AnalysisEngine 레지스트리, RollingLinearRegression(합성 데이터 slope/SE/R²), SafetyGuard(임계/지속), 반응형 코칭 트리거.
- **회귀**: 기존 판정/개인화/코칭가드 테스트 무회귀(예측 테스트만 제거).

## 수락 기준 (AC)

- [ ] AC-1: `AnalysisMetric` 인터페이스로 지표 추가가 `AnalysisEngine` 코드 수정 없이 레지스트리 등록만으로 된다(OCP — 테스트로 6번째 더미 지표 등록 검증).
- [ ] AC-2: 5개 지표(Drift/Gap/Cadence/Submax/HRR)가 각각 정의된 mode로 동작하고 게이트아웃 시 `gated=true` 또는 null을 반환한다.
- [ ] AC-3: 판단선이 하드코딩 bpm이 아니라 개인 `k·σ̂`(온라인 EWMA)로 표현되고, 세션 간 σ̂가 프로필별로 저장/복원된다.
- [ ] AC-4: 예측 코드(HrOdeModel/LearnedDynamics/dynFeaturesAt/예측 테스트)가 완전 제거되고 빌드/기존 테스트가 통과한다.
- [ ] AC-5: FR5 코칭이 예측 선제 경로 없이 드리프트/케이던스 반응 트리거로 발화하고, DirectionGuard/템플릿 폴백이 유지된다.
- [ ] AC-6: SafetyGuard가 위험 심박 지속 시 LLM을 거치지 않고 5초 이내 규칙 권고를 낸다(spec-008 AC-1/2 충족).
- [ ] AC-7: 세션 종료 시 5지표가 RunReport에 담겨 리포트 화면에 표시된다(FR6).
- [ ] AC-8: 시뮬 폐루프로 워치 없이 수집~판정~분석~코칭~리포트 전 구간이 실행된다(QA5).
- [ ] AC-9: Minetti GAP가 경사 -45%~+45%에서 비선형(측정 다항식)으로 계산되고 범위 밖은 클램프한다.

## 구현 순서 (모듈 단위, 1 PR = 1 모듈)

1. `RollingLinearRegression` 추출 + `SlopeEstimator` 리팩터(무회귀).
2. 예측 통삭제 + 디와이어(빌드 통과 유지).
3. `AnalysisMetric`/`MetricSample`/`AnalysisInput`/`SignalWindow`/`AnalysisEngine` 코어 + 테스트.
4. 지표 모듈 5개 각각(모듈+테스트): Drift → Gap → Cadence → Submax → HRR.
5. 개인 k·σ 온라인 추정 + LearnedZone 저장.
6. RunEngine 결선(analysis.onTick/onSessionEnd) + LiveState/RunReport 변경.
7. `SafetyGuard` + 결선.
8. FR5 반응형 코칭 재배선(Coach/CoachPrompt/RuleCoach).
9. FR6 리포트 표시(ReportActivity/Charts).
10. FR4 관측 채널 결선 + 시뮬 폐루프 통합 검증.

## 미해결 사항 (구현 중 확정)

- [ ] HRR 노력종료 감지의 연속 러닝 대리 지표 정밀화(쿨다운 없을 때).
- [ ] 서브맥시멀 빈 대표값 선택(최빈 빈 vs 표준 빈) + %HRR 정규화 on/off.
- [ ] 정속 게이트 `HR_SLOPE_BAND` 구체값(필드 데이터 전 합성으로 초기).
- [ ] `SessionAccumulator` 추출 여부(RunEngine 축소 범위).
- [ ] RunReport `usedModel` 재용도 vs 신규 필드(하위호환).
