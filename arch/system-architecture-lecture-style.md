# 시스템 아키텍처 (강의 AI System 표현방식으로 그린 우리 시스템)

- **상태**: 검토용 v1 (2026-07-14)
- **참조**: `framework/lecture-pdfs/0-AI-시스템-개요.pdf` p6 "AI System 아키텍처" 도식의 표현방식을 그대로 따른다 — 좌 **AI Operation Subsystem**(운영/추론/설명/분석 서비스), 우 **AI Development Subsystem**(데이터셋 구축 → Data Lake → Model Construction → 모델 테스팅 → 등록), 하단 **공유 레지스트리**(Model/Container Registry). 실선=데이터 흐름, 점선=제어 트리거(HITL/HOTL/Retraining), 원통=저장소.
- **읽는 법**: 각 상자의 **윗줄 = 강의 표준 컴포넌트명**, 아랫줄 = **우리 구현**. 표준에는 있으나 우리에겐 **없거나 온라인으로 대체된** 컴포넌트는 점선/회색으로 표시해 정직하게 드러낸다.

```mermaid
flowchart TB
  %% ================= AI Operation Subsystem =================
  subgraph OPSUB["AI Operation Subsystem (MLOps Operation 단계)"]
    direction TB

    subgraph OPSVC["운영 서비스"]
      direction TB
      OUI["Operation UI<br/>대시보드/워치 게이지/리포트/지표 터치 설명"]
      INFC["Inference Control<br/>RunEngine 오케스트레이션"]
      INFRA["Infrastructure Control<br/>RunService 포그라운드"]
      EXPA["Explanation 분석<br/>리포트/팝업 표시"]
      ACT["운영 조치(FA, C) 구동<br/>SafetyGuard/코칭 트리거"]
      OUI --> INFC
      OUI --> INFRA
      OUI --> EXPA
    end

    subgraph INFSVC["추론 서비스 (Inference)"]
      direction LR
      IPREP["데이터 준비<br/>FeatureExtractor/RunSource(1Hz)"]
      IGUARD["입력 가드레일<br/>OutlierGuard 40~220/신선도/정속 게이팅"]
      SERVE["모델 서빙<br/>규칙 판정 + 베이지안 경계 + 분석엔진 + 온디바이스 LLM(Nano)"]
      OGUARD["출력 가드레일<br/>DirectionGuard/SafetyGuard"]
      IPREP --> IGUARD --> SERVE --> OGUARD
    end
    FS1[("Feature Store<br/>롤링 신호 버퍼(SignalWindow)")]
    SERVE -.- FS1

    subgraph EXPSVC["설명 서비스"]
      direction TB
      EGEN["설명 생성<br/>SessionExplainer/사유 태그(값 출처 A/B/C 읽음)"]
      ERES[("설명 결과<br/>세션 스토리 저장")]
      EGEN --> ERES
    end

    subgraph ANASVC["분석 서비스"]
      direction TB
      SLOG[("서빙 로그<br/>RunLogger 세션 로그")]
      MCALC["지표 산출<br/>SessionAnalytics/세션 추세"]
      MMON[("추론 모니터링 지표<br/>EF/드리프트/Zone2% 추세")]
      SLOG --> MCALC --> MMON
    end

    OGUARD --> OUI
    OGUARD --> EGEN
    MMON --> EGEN
    OGUARD --> SLOG
  end

  %% ================= AI Development Subsystem =================
  subgraph DEVSUB["AI Development Subsystem (MLOps Development 단계) — 오프라인 학습 없음, 온라인 개인화로 축소"]
    direction TB
    DSET["데이터셋 구축<br/>(오프라인 없음)"]:::absent
    DLAKE[("Data Lake<br/>SessionStore/LearnedZone (소형 개인 온디바이스)")]
    REEX["재학습 데이터셋 추출<br/>(오프라인 없음 → 온라인 개인화 대체)"]:::absent

    subgraph MC["Model Construction — 오프라인 대신 온라인"]
      direction LR
      ONADAPT["온라인 개인화(Retraining 대응)<br/>베이지안 μ/σ 갱신 / k×σ EWMA / 프로필 갱신"]
    end
    MART[("Model Artifact<br/>개인 파라미터 μ/σ, k×σ, 프로필")]
    MMETA[("ML Metadata store<br/>값 출처 태그(A/B/C) / uFrac 이력")]
    MTEST["모델 테스팅<br/>(오프라인 없음) → 시뮬 폐루프 검증(VirtualRunner)"]:::absent
    MPROM["모델 등록/승격<br/>(온라인 파라미터라 승격 절차 없음)"]:::absent

    DSET --> DLAKE
    DLAKE --> ONADAPT
    ONADAPT --> MART
    ONADAPT --> MMETA
  end

  %% ================= 공유 레지스트리 =================
  MREG["Model Registry (Model Artifact)<br/>LearnedZone 개인 파라미터 + AICore Nano 모델(시스템 제공)"]
  CREG["Container Registry (Packaged Model)<br/>앱 패키지(APK — 폰 + 워치)"]

  %% ---- 서브시스템 간 / 제어 트리거 ----
  ACT -. "Retraining triggering (온라인: 세션 관측/말하기 테스트가 즉시 갱신)" .-> DLAKE
  ACT -. "HITL Control (말하기 테스트 라벨)" .-> OUI
  INFC -. "HOTL Control (코칭 무시/규칙 감독)" .-> ACT
  MART --> MREG
  MREG -. "배포(다음 세션 시작값 prior)" .-> SERVE
  DLAKE --> ONADAPT
  ONADAPT -. "온라인 갱신" .-> SERVE

  classDef store fill:#eeeeee,stroke:#888,color:#111
  classDef absent fill:#f5f5f5,stroke:#bbb,color:#888,stroke-dasharray:4 3
  class FS1,ERES,SLOG,MMON,DLAKE,MART,MMETA store
```

## 표준 ↔ 우리 (있는 것 / 없는 것)

| 강의 표준 | 우리 | 상태 |
|---|---|---|
| 운영 서비스: Operation UI / Inference Control / Infra Control / Explanation 분석 / 운영조치 | 대시보드/리포트 / RunEngine / RunService / 설명 표시 / SafetyGuard+코칭 | 있음 |
| 추론 서비스: 데이터준비 → 입력가드레일 → 모델서빙 → 출력가드레일 | FeatureExtractor → OutlierGuard → (규칙판정+베이지안+분석+LLM) → DirectionGuard/SafetyGuard | 있음(핵심) |
| Feature Store(추론) | SignalWindow 롤링 버퍼 | 경량 |
| 설명 서비스: 설명 생성 → 설명 결과 | SessionExplainer → 세션 스토리 저장 | 있음 |
| 분석 서비스: 서빙로그 → 지표산출 → 모니터링 지표 | RunLogger → SessionAnalytics → EF/드리프트/추세 | 있음 |
| HITL / HOTL 제어 | 말하기 테스트 라벨 / 코칭 무시/규칙 감독 | 있음 |
| Data Lake | SessionStore/LearnedZone(소형 개인) | 축소 |
| Model Construction(오프라인 학습) | **없음 → 온라인 개인화(베이지안/k×σ)로 대체** | 대체 |
| Model Artifact / ML Metadata | 개인 파라미터 / 값 출처 태그·uFrac 이력 | 있음(소형) |
| 모델 테스팅 | **오프라인 없음 → 시뮬 폐루프(VirtualRunner)로 검증** | 대체 |
| 모델 등록/승격 | **없음(온라인 파라미터)** | 없음 |
| Model Registry / Container Registry | LearnedZone+AICore Nano / APK | 있음 |

## 이 도식이 말하는 우리 아키텍처의 정체성 (정직)

- **Operation 서브시스템이 거의 전부**다. 표준의 Development(오프라인 데이터셋 → 대량 학습 → 테스팅 → 승격 → 배포)가 우리에겐 **비어 있고**, 그 자리를 **운영 안의 온라인 개인화**(베이지안 경계 갱신 + k×σ)가 대신한다. → 콜드스타트/개인 소량 데이터/온디바이스/참값 없음 조건에 따른 **설계 선택**(대량 사전학습은 검증 불가/과적합 위험).
- **입력/출력 가드레일이 추론 경로에 명시적**이고, 약한 온디바이스 LLM(Nano)을 출력 가드레일+폴백으로 감싼 것이 제어가능성의 실체.
- **값 출처 태그(ML Metadata) → 설명 서비스** 흐름이 "모든 수치에 출처"(제1원칙)를 아키텍처로 드러낸다.
- 모델 검증은 오프라인 테스트셋이 아니라 **시뮬 폐루프(VirtualRunner)** 로 한다(참값 없는 조건의 우회).

---

> 관련: `framework/ai-system-and-quality.md §1-3`, `arch/architecture-overview.md`, `arch/system-architecture-diagram.md`(이전 버전), `arch/adr-025`.
