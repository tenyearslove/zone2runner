# ADR-017: HRV(RR간격/IBI) 도입 보류 — 심박 절대값 기반 유지

- **상태**: Accepted
- **날짜**: 2026-07-05
- **결정자**: coolove + Claude

## 맥락

Galaxy Watch8에서 HRV(심박변이도)의 원신호인 RR간격(= IBI, Inter-Beat Interval)을 앱으로 가져올 수 있는지 조사했다. 결론은 "기술적으로 가능하나 조건부"다. Samsung Health Sensor SDK가 Galaxy Watch4 이상(워치8 포함, Wear OS powered by Samsung)에서 IBI(1Hz, status 플래그 동반)를 제공하지만, 이를 쓰려면 (1) Wear OS 워치 앱 개발, (2) Samsung Partner Program 승인(package name/SHA-256 등록, 미승인 시 SDK_POLICY_ERROR)이 필요하다. 현재 우리 파이프라인은 모든 판정/개인화/역치 추정을 심박 절대값(bpm) + 페이스/케이던스/경사 위에서 수행하며(adr-016), RR간격은 쓰지 않는다.

이 조사 결과, HRV를 도입할지 여부를 결정한다.

## 결정

### 대안 비교

| 기준 | 대안 A: 미도입(현행 유지) | 대안 B: Samsung SDK IBI + DFA α1 | 대안 C: Health Services RMSSD |
|------|--------------------------|----------------------------------|-------------------------------|
| 핵심 이득 | 없음(현행 유지) | 유산소 임계(LT1) 실측 근사 → 개인 경계 ground-truth 대용 | 계산된 RMSSD 값 수신(회복/준비도 지표) |
| 실시간 판정 개선 | 해당 없음 | 미미(HR 절대값이 이미 직접 신호) | 미미 |
| 신호 품질 리스크 | 없음 | 높음(달리는 중 손목 PPG의 RR은 모션 아티팩트에 취약, DFA α1은 특히 민감) | 중간(기기 노출 여부/품질 불확실) |
| 계산 부담 | 없음 | 큼(윈도우별 로그-로그 회귀, 온디바이스 실시간) | 작음(값이 이미 계산됨) |
| 개발 비용 | 없음 | 큼(워치 앱 + Partner 승인 필수) | 중간(워치 앱 필요, 지원 여부 실기기 확인 선행) |
| 지원 확실성 | 확실 | 확실(SDK 문서화됨) | 불확실(OEM 노출 보장 안 됨) |

### 대안 A: 미도입(현행 심박 절대값 유지)
- 장점: 추가 개발/승인/계산 부담 0. 좋은 신호(HR)에 품질 나쁜 신호(달리는 중 RR)를 섞는 리스크 회피. 현 파이프라인(adr-016) 그대로.
- 단점: 개인 Zone2 상단(LT1) 추정을 여전히 공식(%HRmax 0.70) + factor + HR-vs-페이스 회귀로만 근사 → 실측 생리 신호 부재.

### 대안 B: Samsung Health Sensor SDK IBI + DFA α1
- 장점: RR간격 시계열의 DFA α1이 ~0.75 하향 교차하는 지점으로 유산소 임계를 실시간 근사 관측 가능. 우리 앱의 가장 약한 고리(진짜 Zone2 상단이 어디냐)를 정면으로 겨냥. 랩 검사 없이 개인 경계의 ground-truth 대용이 될 잠재력.
- 단점: 달리는 중 손목 PPG의 RR 품질이 최대 리스크(필터 후 유효 비트가 충분히 남는지 실기기 실측 전 불명). Wear OS 워치 앱 + Samsung Partner Program 승인 필수. DFA α1 실시간 계산 부담. 여기서 신호 품질이 안 나오면 전부 무용지물.

### 대안 C: Android Health Services RMSSD
- 장점: 이미 계산된 RMSSD 값을 받으므로 계산 부담 작음. 안드로이드 표준 API.
- 단점: `HEART_RATE_VARIABILITY_RMSSD` 데이터 타입을 갤럭시 워치가 실제 노출하는지 보장 안 됨(실기기 getCapabilities 확인 필요). RMSSD는 회복/준비도 지표에 가깝고, 실시간 Zone2 판정 개선과는 거리가 있음(별도 기능=스코프 확대).

### 채택: 대안 A (미도입, 현행 유지)
현재 핵심 판정 루프에는 HRV가 이득이 거의 없고(HR 절대값이 이미 직접·강건한 신호), 달리는 중 RR 신호 품질이라는 결정적 리스크와 워치 앱 + Partner 승인이라는 큰 개발 비용이 걸린다. 다만 대안 B의 잠재 가치(유산소 임계 실측)는 유효하므로 폐기가 아니라 **보류**한다. 재검토 트리거는 명확하다 — Galaxy Watch8 실기기에서 가볍게 달리며 IBI를 수집해 status 필터링 후 유효 RR 비율을 측정하고, 그 품질이 DFA α1을 지탱할 수준이면 그때 정식 ADR로 도입을 재검토한다.

## 결과

- 긍정: 현행 파이프라인(adr-016) 유지. 개발/승인/계산 리스크 회피. "왜 HRV를 안 쓰는가"에 대한 근거가 문서로 남음(AI≠NN 철학과 동일 맥락 — 도구/신호를 문제 특성에 맞춰 선택).
- 부정/비용: 개인 Zone2 상단 추정은 계속 공식+factor+HR 관측에만 의존(실측 생리 신호 부재). 대안 B의 잠재 이득은 실현 유예.
- 이행: 코드 변경 없음. 재검토 선행 조건 = 워치8 실기기에서 IBI 수집 → status 필터 후 유효 RR 비율 측정(최소 spec 필요 시 `/spec`). 이 수치가 도입 가/부의 단일 판단 근거.

## 관련 문서

- ADR: `arch/adr-016`(AI 방법 선택 — 문제별 도구, 본 결정과 동일 철학), `arch/adr-004`(Bayesian 개인화 — LT1 추정의 현행 경로), `arch/adr-012`(콜드스타트 prior)
- Spec: `spec/spec-014`(심박 동역학 NN), `spec/spec-004`(개인화)
- 참고: Samsung Health Sensor SDK(IBI 1Hz, Partner Program), Android Health Services(RMSSD), zone2-physiology-and-estimation.md(참값 부재/토크테스트=라벨)
