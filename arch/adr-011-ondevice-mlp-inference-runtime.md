# ADR-011: 온디바이스 MLP 추론 런타임 선택 — 순수 Kotlin 순전파

- **날짜**: 2026-07-03
- **상태**: Accepted (순전파 런타임 결정 유효. 단 대상 모델은 adr-005 판정 MLP → adr-013/016으로 심박예측 NN(spec-014)로 교체)
- **결정자**: 성시원
- **보고서 매핑**: 설계 - Architectural Decision (adr-005/DP4 후속, 구현 사후 문서화)

---

## 맥락

adr-005(DP4)에서 Zone2 실시간 판정기를 **다변량 MLP 분류기**로 정했다. 이 MLP를 안드로이드 폰에서 온디바이스로 추론해야 하는데, 어떤 런타임으로 순전파를 돌릴지가 남는다.

이 판정 MLP는 매우 작다.

- 구조: 입력 7 -> 은닉 32 -> 은닉 16 -> 출력 3, ReLU.
- 파라미터 수는 수천 개 수준이다(7*32 + 32*16 + 16*3 + bias).

학습과 배포는 분리되어 있다. 논문/실험 canonical 학습은 PyTorch(`ml/train_mlp.py`)로 하고, **배포용으로는 동일 설계를 scikit-learn MLPClassifier로 재적합**(`ml/export_model.py`)해 가중치와 StandardScaler(mean/scale)를 `zone2_mlp.json`으로 export한다. 앱은 이 JSON을 로드해 추론한다.

또한 이 프로젝트는 상용화가 아니라 **AI 설계 교육과정 수료 프로젝트**이므로, 추론 로직의 **설명가능성과 경량성**이 표준 프레임워크의 최적화보다 우선한다. "이 작은 모델을 어떻게 굴리는가"가 그대로 설명 자산이 된다.

---

## 결정

### 대안 비교

| 기준 | A. TensorFlow Lite | B. 순수 Kotlin 순전파 | C. ONNX Runtime Mobile |
|------|------|------|------|
| 런타임 의존성 | 수 MB 추가 | **0(표준 라이브러리만)** | 수 MB 추가 |
| 변환 파이프라인 | .tflite 변환 필요 | **JSON export만** | .onnx 변환 필요 |
| 이 규모(수천 파라미터) 적합성 | 과함 | **적합** | 과함 |
| 하드웨어 가속 이점 | 이 크기에선 미미 | 불필요 | 이 크기에선 미미 |
| 추론 로직 투명성 | 낮음(블랙박스 런타임) | **높음(코드로 보임)** | 낮음 |
| 검토 / 재현 용이성 | 중 | **높음(JSON 가중치)** | 중 |
| 큰 모델로 성장 시 | 우수 | 부적합 | 우수 |

### 대안 A: TensorFlow Lite 런타임
- 장점: 온디바이스 추론의 표준이고 최적화/하드웨어 가속을 제공한다.
- 단점: 수 MB 런타임 의존성이 APK에 붙고 .tflite 변환 파이프라인이 추가된다. **파라미터 수천 개짜리 MLP엔 이 무게가 과하며**, 이 크기에선 하드웨어 가속 이점도 미미하다.

### 대안 B: 순수 Kotlin 순전파 *(채택)*
- JSON의 가중치/bias/스케일러를 로드해 이중 루프로 행렬곱 + ReLU + softmax를 직접 수행한다(`app/.../pipeline/Zone2Classifier.kt`).
- 장점: **의존성 0**(표준 라이브러리 + org.json만), APK가 가볍다. 추론 로직이 코드로 그대로 드러나 **교육/설명 가치**가 높다. JSON 가중치라 사람이 열어 검토하고 재현하기 쉽다. 콜드스타트/모델 미로드 시 규칙 폴백(`ruleClassify`)도 같은 파일에 둔다.
- 단점: 모델이 커지면 수작업 순전파와 최적화 부재가 부담이 된다.

### 대안 C: ONNX Runtime Mobile
- 장점: 프레임워크 중립적이고 범용성이 좋다.
- 단점: A와 마찬가지로 런타임 의존성과 .onnx 변환이 붙어 이 규모엔 과하다.

### 채택: 대안 B

**"모델이 작다 + 설명가능성/경량성 우선 + 교육 프로젝트"** 라는 세 근거가 순수 Kotlin 순전파를 정당화한다. 수천 파라미터 MLP의 순전파는 이중 루프 몇 줄이면 충분하고, 런타임/변환 도구를 도입할 때 얻는 최적화 이점이 이 크기에선 실질적으로 없다. 오히려 추론 과정이 코드로 투명하게 보이는 점이 설계 산출물로서 더 가치 있다.

- export: `ml/export_model.py`가 sklearn 가중치와 StandardScaler를 `zone2_mlp.json`으로 저장.
- 추론: `app/app/src/main/java/com/zone2runner/app/pipeline/Zone2Classifier.kt`가 JSON 로드 후 순수 Kotlin 순전파 + softmax로 3분류. 규칙 폴백 제공.

---

## 결과 / 트레이드오프

- APK에 ML 런타임 의존성이 없어 가볍고, 빌드/배포가 단순하다.
- 학습(PyTorch canonical)과 배포용 재적합(sklearn export)이 분리되어, 실험 정본은 유지하면서 배포는 JSON 가중치라는 가볍고 검토 가능한 형태로 고정된다.
- 대가로 최적화된 런타임의 이점(연산자 융합, 하드웨어 가속)은 포기한다. 현 모델 크기에선 무의미하지만, **향후 모델이 커지거나 구조가 복잡해지면 TFLite/ONNX Runtime로의 전환을 재검토**한다(교체 지점은 `Zone2Classifier`의 추론부로 국한된다).

---

## 관련 문서
- ADR: `arch/adr-005-zone2-classifier-nn.md` (DP4, 왜 MLP인가 — 본 결정의 상위 근거)
- 코드: `ml/export_model.py`, `ml/train_mlp.py`, `app/app/src/main/java/com/zone2runner/app/pipeline/Zone2Classifier.kt`
- 자산: `app/app/src/main/assets/zone2_mlp.json`
