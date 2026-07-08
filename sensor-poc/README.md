# sensor-poc — Watch+Phone 센서/데이터 PoC (adr-008)

adr-001 Hybrid를 실제로 검증: **Galaxy Watch 8이 실시간 HR을 측정해 폰으로 보내는지**, 그리고 폰이 **위치/고도/경사**를 얻는지. (날씨는 제외 — 모델 입력 아님)

## 모듈
- `wear/` — Galaxy Watch 앱. **포그라운드 서비스(`HrService`) + Health Services `ExerciseClient`(RUNNING)**로 실시간 HR 수집 → Data Layer(MessageClient `/hr`)로 폰 전송. **백그라운드/화면off에서도 지속**(adr-009).
- `phone/` — Android 폰 앱. **`HrReceiverService`(WearableListenerService)로 백그라운드 수신** + FusedLocation(위치/고도) + 경사 계산. 수신 시 알림 표시.
- 두 모듈 **같은 applicationId(com.zone2runner.sensorpoc) + 같은 debug 서명** → 기존 Galaxy Wearable 페어링 위에서 Data Layer 연결.

## 백그라운드 동작 (adr-009)
- 워치: `MeasureClient`(포그라운드 전용)의 백그라운드 끊김 문제를 `ExerciseClient`+포그라운드 서비스로 해결. 지속 알림이 뜬 채 화면을 꺼도 HR 전송 유지.
- 폰: Activity가 아닌 `WearableListenerService`가 수신 → 앱이 꺼져 있어도 시스템이 깨워 받음(알림으로 증빙).
- 남는 제약: 온디바이스 LLM 코칭은 폰 포그라운드 전용(adr-007) → 화면off 코칭은 후속 과제.

## 빌드
```bash
cd sensor-poc
./gradlew :phone:assembleDebug :wear:assembleDebug
# phone: phone/build/outputs/apk/debug/phone-debug.apk
# wear : wear/build/outputs/apk/debug/wear-debug.apk
```

## 설치
### 폰 (S26)
```bash
adb install -r phone/build/outputs/apk/debug/phone-debug.apk
```
### 워치 (Galaxy Watch 8) — 워치 ADB 필요
1. 워치: 설정 → 정보 → 소프트웨어 → 빌드번호 여러 번 탭 → 개발자 옵션 활성화
2. 워치: 개발자 옵션 → **ADB 디버깅** + **무선 디버깅** 켜기 (워치와 PC 같은 Wi-Fi)
3. PC: `adb pair <워치IP:페어링포트>` (워치 화면의 코드 입력) → `adb connect <워치IP:포트>`
4. `adb -s <워치> install -r wear/build/outputs/apk/debug/wear-debug.apk`
   - 또는 Android Studio에서 sensor-poc 열고 `wear` 구성으로 워치에 Run
- 폰→워치 자동 설치는 개발 빌드엔 없음(adr-008). 프로덕션은 Play가 자동 설치.

## UI (디버깅용 실시간 GUI)
- **워치**: 원형 화면 안전영역(BoxInsetLayout)에 배치. 위 = 최종 HR 큰 숫자, 아래 = 상태 3줄(센서/폰/전송)을 실시간 in-place 갱신. `측정 시작`/`측정 중지` 토글.
  - 센서 상태: `예열중`(ACQUIRING, 손목 밀착 필요) → `정상`(AVAILABLE). **첫 데이터까지 수 초 걸리는 게 정상** — 예열 표시가 뜨면 그대로 기다린다(다시 누르지 말 것, 콜백 중복 등록 방지됨).
- **폰**: 카드형 GUI(연결/HR/위치)를 실시간 in-place 갱신 + 하단 이벤트 로그(최근 40줄, 최신이 아래로 자동 스크롤). 5초 무수신 시 HR 카드가 `끊김?`(빨강) 표시.

## 검증 (실기기)
1. 폰 앱 실행 → 위치 권한 허용 (야외에서 GPS/고도 잘 잡힘). `워치 연결` 카드가 `연결됨`인지 확인.
2. 워치 앱 실행 → **측정 시작** → 심박/신체활동/알림 권한 허용. 센서 상태가 `예열중`→`정상`으로 바뀔 때까지 기다림. (지속 알림이 뜬다)
3. 확인:
   - 워치 화면: HR 숫자가 1~2초 주기로 갱신되고 `전송  N회 · Ns 전`이 오르는지
   - 폰 화면: HR 카드에 `N bpm` + `방금 수신`, `위치/고도/경사` 카드 갱신
4. 잠깐 걸으면 경사(slope) 값이 갱신됨 (오르막/내리막)
5. **백그라운드 검증(adr-009 핵심)**:
   - 워치: 홈으로 나가 화면을 끈 뒤 다시 보면 전송 카운트가 계속 오르는지(지속 알림 유지)
   - 폰: 폰 앱을 닫고(백그라운드) **HR 수신 알림**이 계속 갱신되는지 → `WearableListenerService` 동작 증빙

> **빌드 JDK**: AGP 8.7 + wear 모듈은 JDK 17+ 필요. 이 PC는 Android Studio JBR(Java 21) 사용:
> `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` 후 gradle 실행.

## 확인 포인트 (adr-008/009 PoC 목표)
- [ ] 실시간 HR이 워치에서 실제로 나오는가 (Health Services ExerciseClient)
- [ ] HR이 Data Layer로 폰까지 오는가 (기존 페어링 활용)
- [ ] 폰에서 위치/고도/경사를 얻는가
- [ ] 전송 지연이 체감상 수 초 이내인가 (QA6 수행효율성 기여)
- [ ] **워치 백그라운드/화면off에서 HR 전송이 지속되는가 (adr-009)**
- [ ] **폰 앱이 백그라운드/종료 상태여도 수신되는가 (WearableListenerService, adr-009)**

## 구성
Kotlin 2.2.0 / AGP 8.7.2 / Gradle 8.9. Health Services(androidx.health:health-services-client),
Data Layer(play-services-wearable), 위치(play-services-location).
