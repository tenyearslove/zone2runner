# wear — Zone2 Runner 워치 앱 (spec-010)

sensor-poc(검증용)와 **분리된 실제 워치 앱**. 러닝 중 워치 한 화면에서 심박/존/페이스/거리/속도/시간을 보고 세션을 조작한다.

- 독립 Gradle 프로젝트(applicationId `com.zone2runner.wear`). sensor-poc와 무관하게 단독 빌드/설치.
- 명세: `../spec/spec-010-wear-running-dashboard.md`

## 화면 (원형, 무스크롤)
- 상단: 경과시간 `mm:ss` (일시정지 반영)
- 중앙: HR 큰 숫자 + 현재 존 색상, 존 라벨(`Z2 지방연소 · 목표 유지`)
- 하단 3열: 페이스(min/km) / 거리 / 속도(km/h)
- 베젤: 5구간 존 게이지(아크) + 현재 HR 마커, 하단 개방부에 조작 버튼
- 버튼: 대기[시작] → 진행[일시정지][종료] → 일시정지[재개][종료]

## 존 (경량 %HRmax)
Z1<60% / **Z2 60~70%(목표)** / Z3 70~80% / Z4 80~90% / Z5>=90%. 기본 HRmax=190.
정밀/개인화 판정은 폰(MLP, adr-005). 워치는 즉시 피드백용 경량 존만.

## 빌드
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"  # JDK 17+ 필요(AGP 8.7)
.\gradlew.bat :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## 설치 (Galaxy Watch 8, 무선 디버깅)
```powershell
adb -s <워치IP:포트> install -r app\build\outputs\apk\debug\app-debug.apk
```
1. 워치 앱 실행 → **시작** → 심박(BODY_SENSORS) + 위치(ACCESS_FINE_LOCATION) 권한 허용
2. HR 예열 수 초 후 숫자/존 갱신. 야외에서 걸으면 페이스/거리/속도 갱신.

## 구성
Kotlin 2.2.0 / AGP 8.7.2 / Gradle 8.9 / minSdk 30.
HR = Health Services, 위치/속도 = play-services-location, 원형 배치 = androidx.wear BoxInsetLayout.
UI는 프로그래매틱 View + 커스텀 `ZoneGaugeView`(Canvas 아크).

## 미검증 / 다음
- 실기기 시각 튜닝(폰트/여백/게이지 두께), 예열 문구, 손목 내림 시나리오.
- 폰 Data Layer 전송(spec-003), 개인 HRmax/RHR(spec-009), 세션 저장/요약(spec-007) 통합.
