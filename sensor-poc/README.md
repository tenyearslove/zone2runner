# sensor-poc — Watch+Phone 센서/데이터 PoC (adr-008)

adr-001 Hybrid를 실제로 검증: **Galaxy Watch 8이 실시간 HR을 측정해 폰으로 보내는지**, 그리고 폰이 **위치/고도/경사**를 얻는지. (날씨는 제외 — 모델 입력 아님)

## 모듈
- `wear/` — Galaxy Watch 앱. Health Services로 실시간 HR 측정 → Data Layer(MessageClient `/hr`)로 폰 전송.
- `phone/` — Android 폰 앱. HR 수신 표시 + FusedLocation(위치/고도) + 경사 계산.
- 두 모듈 **같은 applicationId(com.zone2runner.sensorpoc) + 같은 debug 서명** → 기존 Galaxy Wearable 페어링 위에서 Data Layer 연결.

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

## 검증 (실기기)
1. 폰 앱 실행 → 위치 권한 허용 (야외에서 GPS/고도 잘 잡힘)
2. 워치 앱 실행 → **측정 시작** → BODY_SENSORS 권한 허용
3. 확인:
   - 워치 화면: `HR: N bpm` 이 1~2초 주기로 갱신되는지
   - 폰 화면: `HR ← 워치: N bpm` 수신 + `위치/고도/경사` 표시
4. 잠깐 걸으면 경사(slope) 값이 갱신됨 (오르막/내리막)

## 확인 포인트 (adr-008 PoC 목표)
- [ ] 실시간 HR이 워치에서 실제로 나오는가 (Health Services)
- [ ] HR이 Data Layer로 폰까지 오는가 (기존 페어링 활용)
- [ ] 폰에서 위치/고도/경사를 얻는가
- [ ] 전송 지연이 체감상 수 초 이내인가 (QA4 기여)

## 구성
Kotlin 2.2.0 / AGP 8.7.2 / Gradle 8.9. Health Services(androidx.health:health-services-client),
Data Layer(play-services-wearable), 위치(play-services-location).
