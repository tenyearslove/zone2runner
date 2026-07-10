# 강의 요약 — AI 시스템 설계와 품질 (인증 필수 참고)

> 강의 PDF 2개를 읽기 쉽게 재구성한 요약. **원본이 정본**:
> - `framework/lecture-pdfs/0-AI-시스템-개요.pdf` (28p) — AI 시스템을 어떻게 구성/표현하는가
> - `framework/lecture-pdfs/1-AI-품질-개요.pdf` (17p) — AI 품질모델과 **AI 8대 QA**
>
> **인증 필수 규칙(CLAUDE.md 반영)**: (1) 우리 AI 시스템을 아래 "AI System 아키텍처" 구성으로 표현할 것.
> (2) 우리 AI QA는 아래 "AI 8대 QA" 중에서 선택할 것(과제 규정: 품질속성 4개 이상, 그중 AI 특화 2개 이상).

---

## 1부. AI 시스템 개요 (PDF 0) — 우리 시스템을 이 구성으로 표현한다

### 1-1. AI 시스템의 큰 뼈대
AI 시스템은 세 덩어리로 본다: **AI Task(무슨 문제) → AI Model(무엇으로) → Model/Software 배포(어떻게 서빙)**.

### 1-2. AI Application vs AI System (기능 뷰)
- **Application 계층**: User, AI Application(사용자가 쓰는 앱).
- **AI System 계층**: Model 개발 / 추론(서빙) 서비스 / 분석 서비스 / 운영 서비스 / 학습데이터셋 구축 / 설명 서비스.
- 주변: AI System Administrator, External Data Source.

### 1-3. ★ AI System 아키텍처 (표준 구성 — 우리 시스템을 여기에 매핑)

두 서브시스템(MLOps의 Operation 단계 / Development 단계)으로 나뉜다.

```
[AI Operation Subsystem]                        [AI Development Subsystem]
─ 운영 서비스(Operation Service)                 ─ 데이터셋 구축 → Data Lake
  · Operation UI                                    (학습/테스트 데이터 선정, 재학습 데이터셋 추출)
  · Inference Control / Infra Control             ─ Model Construction
  · Explanation 분석 / 운영조치(FA,C) 구동           데이터준비 → 입력가드레일 → 모델학습 → 출력가드레일
  · HITL / HOTL Control triggering                  (Feature Store, Model Artifact, ML Metadata store)
─ 추론 서비스(Inference Service)                  ─ 모델 테스팅
  데이터준비 → 입력가드레일 → 모델서빙 → 출력가드레일    (테스트 로그/결과 지표 → 모델 등록/승격)
  (Feature Store, 모델 배포/평가)                  ─ Retraining triggering(운영→데이터셋 구축)
─ 설명 서비스(Explanation): 설명 생성 → 설명 결과
─ 분석 서비스(Analysis): 서빙 로그 → 지표 산출 → 추론 모니터링 지표

           [공유 레지스트리]  Model Registry(Model Artifact) / Container Registry(Packaged Model)
           Development에서 학습·승격한 모델을 레지스트리로 배포 → Operation이 추론 서비스로 서빙
```

핵심 관례:
- **입력 가드레일 / 출력 가드레일**이 학습(Model Construction)과 추론(Inference) 양쪽에 명시적으로 들어감.
- **설명 서비스**가 독립 컴포넌트로 존재(설명용이성 QA와 직결).
- **HITL(Human-in-the-loop) / HOTL(Human-on-the-loop)** 제어 트리거(제어가능성 QA와 직결).
- **Retraining 루프**(운영 모니터링 → 재학습)로 기능적응성 QA를 구조로 담음.

### 1-4. AI Model 기본 용어
- **ML Model**: 입력 데이터로 추론/예측을 내는 수학적 구성물.
- **Training**: ML 알고리즘으로 학습 데이터를 써서 모델 파라미터를 정하거나 개선하는 과정.
- **Parameter(파라미터)**: 모델의 내부 변수(출력 계산에 영향). **배포 필수 산출물**.
- **Hyperparameter(하이퍼파라미터)**: 학습을 제어하는 설정값. **학습 후 폐기(구조 설정만 배포)**.

| 예: 심층신경망 | 파라미터(배포 필수) | 하이퍼파라미터(학습 제어) |
|---|---|---|
| | 가중치(Weight), 편향(Bias) | 은닉층수/노드수, 학습률, 배치크기, 드롭아웃 |
| 예: 랜덤포레스트 | 분할변수/임계점, 리프노드 값 | 학습기 개수, 최대깊이, 최소분할샘플수, max_features, 부트스트랩 |

### 1-5. 배포(Model/Software)
- AI 모델 형식(Format), **Model Artifact vs Model Package** 구분.
- Model Registry(Artifact) / Container Registry(Packaged Model)로 배포.

---

## 2부. AI 품질 개요 (PDF 1) — 우리 QA를 여기서 고른다

### 2-1. 품질모델 계보
- **ISO/IEC 25010:2011** (SW 제품 품질 8특성): Functional Suitability, Performance Efficiency, Compatibility, Usability, Reliability, Security, (+Maintainability, Portability).
- **ISO/IEC 25010:2023**: 위 + Safety, Interaction capability 등 개편.
- **ISO/IEC 25059:2023 (AI 품질모델)** = 25010의 **AI 특화 확장**. 추가/수정된 AI 특화 특성:
  - **Functional adaptability(기능적응성)**, **Robustness(강건성)**, **User controllability(제어가능성)**,
    **Transparency(투명성/설명)**, **Intervenability(개입가능성)**.
- **Trustworthy AI(신뢰가능 AI)** 흐름: NIST AI RMF, EU AI Act, 한국 AI 기본법이 각각 주요 QA를 규정 →
  이를 종합해 강의는 **AI 시스템 8대 QA**로 정리.

### 2-2. ★★ AI 시스템 8대 QA (여기서 우리 QA를 선택)

"AI 시스템의 생애주기 전반에서 관리되어야 할 품질 속성. 8대 QA를 기반으로 시스템의 **신뢰성과 책임성**을 체계적으로 확보."

3개 관점으로 묶인다.

#### 기능 측면 (Functional Excellence) — "기대대로 올바르게 + 변화에 진화"
| # | QA | 핵심 역할 | 목표(정의) |
|:--:|------|------|------|
| 1 | **기능 정확성** | 일반적 상황의 기본 품질 | 사용자가 의도한 정답을 요구 정밀도로 제공(가장 기초 능력) |
| 2 | **강건성** | 악조건에서의 견고성 | 노이즈/비정상 입력에도 무너지지 않고 기능정확성 유지하는 '방어력' |
| 3 | **프라이버시** | 프라이버시 보호 | 학습 데이터의 민감정보가 예측값/인터페이스로 노출되지 않게 하는 안전장치 |
| 4 | **공정성** | 공정성 제공 | 성별/인종 등 부당 차별 없이 기확립된 규범 존중하는 안전장치 |
| 5 | **기능 적응성** | 지속적 진화 | 환경/대상/규범 변화를 감지하고 재학습으로 진화하는 '생존 능력' |

#### 신뢰성 측면 (Trustworthiness) — "믿고 쓰게 만드는 거버넌스"
| # | QA | 핵심 역할 | 목표(정의) |
|:--:|------|------|------|
| 6 | **제어 가능성** | 신뢰성 확보 | 정상 동작이 아닐 때 제어 주체(인간/외부 에이전트)가 개입해 의도한 동작 완료/안전상태로 전환하는 '통제권' |
| 7 | **설명 용이성** | 신뢰성 소통 | 판단 근거를 사람이 이해할 형태로 표현해 결과에 '신뢰'를 부여하는 '소통 창구' |

#### 효율성 측면 (Efficiency) — "자원을 효율적으로"
| # | QA | 핵심 역할 | 목표(정의) |
|:--:|------|------|------|
| 8 | **수행 효율성** | 수행 최적화 | 추론속도/처리량/자원점유율 등 서비스 환경에서 제시간 응답하게 하는 모든 QA의 '물리적 제약 조건' |

### 2-3. 우리 프로젝트로의 시사점 (재정렬 필요 — 후속 결정)
- 과제 규정: **품질속성 4개 이상, 그중 AI 특화(위 8대) 2개 이상 필수.**
- 우리 현행 QA(spec-002)와 8대 QA 대응:
  - 기능정확성 = 기능정확성 ✅ / 강건성 = 강건성 ✅ / (우리)적응성 → **기능적응성** ✅ / (우리)효율성 → **수행효율성** ✅ / 설명용이성 = 설명용이성 ✅
  - **(우리)테스트가능성** = 8대에 없음(ISO 유지보수성 하위). "AI 특화 QA"로는 인정 애매 → 재검토.
  - **제어가능성** — 우리 무결성 가드레일(DirectionGuard/규칙이 방향·경계·사실 확정)이 강한 후보인데 **QA로 명명 안 함** → 추가 검토.
  - 프라이버시 — 온디바이스(데이터 이탈 0)로 서사 가능. 공정성 — 단일 사용자라 약함.
- **다음 작업**: spec-002의 QA 명칭을 8대 QA 공식 용어로 정렬 + AI 특화 2개 이상 명시 + architecture-overview를 위 AI System 아키텍처(서브시스템/서비스) 구성으로 매핑.
