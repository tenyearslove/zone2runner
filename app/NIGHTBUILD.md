# NIGHTBUILD — 야간 자율 빌드 로그 (2026-07-03)

> 목적: 사용자가 자는 동안(00:05~07:00) `app/`(폰) + `wear/`(워치)를 실제로 써볼 수 있는
> 러닝 코칭 앱으로 진전시킨다. 중단되면 이 문서 + 잦은 커밋으로 이어간다.
> **이어가는 법**: 이 파일의 체크리스트에서 `[~]`(진행중) 또는 첫 `[ ]`(미착수)부터.

## 세션 목표 (사용자 지시 요약)
- 폰+워치 둘 다, 실제 러닝 코칭 앱을 "전체 플로우를 한 번 쭉 써볼 수 있게" 완성도 올리기.
- 우리가 만든 AI 모델(MLP 판정 + Bayesian 개인화) 적용 — 이미 반영됨(포팅 완료).
- sensor-poc(실시간 HR/GPS/경사) + llm-verify(Gemini Nano 코칭) 성과 활용.
- 폰: 실시간 지도, 결과 지도, 러닝중 측정 데이터 뷰, 유산소 존 분석.
- 완전한 완성 아님 — 아침까지 할 수 있는 만큼. git이 안전망.

## 시작 시점 상태 (baseline, 미커밋)
`app/` 폰 모듈이 시뮬레이터 기반 단일 데모로 이미 존재:
- 파이프라인 완성: OutlierGuard, FeatureExtractor, Zone2Classifier(순수 Kotlin MLP 순전파),
  Personalization(Bayesian), RunEngine(오케스트레이터), RuleCoach, RunSimulator.
- 모델 자산: `assets/zone2_mlp.json`(export_model.py 산출). MLP 로드 → 순전파 추론.
- UI: MainActivity(라이브 대시보드+지도+코칭), ReportActivity(요약/존분포/개인화/경로지도/코칭로그).
- 지도: osmdroid(OSM, API키 불필요). 빌드 설정 AGP 8.7.2 / Kotlin 2.2.0 / Gradle 8.9 / minSdk26.
- 한계: 시뮬레이터 전용(실센서 없음), 화면 2개(홈/히스토리/프로필 없음), 영속화 없음,
  차트 없음, LLM 코칭 미연동, 코드가 참조하는 adr-010/011 문서 미작성.

## 빌드/툴
- JDK: Android Studio JBR (JDK 21) at `C:\Program Files\Android\Android Studio\jbr`.
- SDK: `D:\Android\Sdk`. 빌드: `app/`에서 `JAVA_HOME=<JBR> ./gradlew.bat assembleDebug --no-daemon`.
- 검증 = 컴파일 성공(실기기 테스트 불가). 큰 변경마다 assembleDebug 재실행.

## 아키텍처 결정(야간, 사후 ADR화 대상)
- 화면 네비게이션: Home 진입 → Run(라이브) → Report, Home ↔ History ↔ Profile.
- HrSource/LocationSource 추상화로 시뮬/실센서 교체 (기본=시뮬, 실센서는 옵션).
- 세션 영속화: RunReport를 JSON으로 filesDir에 저장(경량, DB 미도입).
- 코칭: RuleCoach 기본, LlmCoach(Gemini Nano, llm-verify) 있으면 사용 + 실패 시 폴백.

## 체크리스트 (진행 표시: [ ]=미착수 [~]=진행중 [x]=완료)
- [x] 기존 코드 전체 파악
- [x] 진행상황 문서(이 파일) 작성
- [~] app/ baseline 빌드 검증 (백그라운드 실행중)
- [ ] adr-010 (지도: osmdroid/OSM 채택 근거), adr-011 (온디바이스 추론: 순수 Kotlin vs TFLite)
- [ ] 도메인 확장: RunReport에 HR/페이스 시계열 추가, 세션 저장 모델
- [ ] SessionStore(JSON 영속화) + HomeActivity + HistoryActivity
- [ ] ProfileActivity (spec-009: 나이/안정HR/최대HR, 저장)
- [ ] RunActivity: 시뮬/실센서 모드 선택, 라이브 대시보드 유지
- [ ] 실센서 소스: LocationSource(FusedLocation 실 GPS), HrSource(시뮬 + 워치 DataLayer 대비 훅)
- [ ] 리포트 강화: HR/페이스 시계열 차트(TimeSeriesChartView) + 유산소 존 분석 섹션
- [ ] LlmCoach (ML Kit GenAI Gemini Nano) + 폴백, 코칭 소스 토글
- [ ] wear: Data Layer로 HR 폰 송신(HrForwarder), 개인 HRmax 반영 훅
- [ ] 최종 빌드 검증 + HANDOFF 갱신 + 커밋 정리

## 진행 로그(시간순, 최신 위)
- 00:05 시작. 코드 파악 완료, baseline 빌드 착수, 이 문서 작성.
