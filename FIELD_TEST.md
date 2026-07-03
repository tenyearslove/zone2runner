# FIELD_TEST — 실주행 테스트 런북 (2026-07-04 아침)

워치+폰을 차고 실제로 달리면서 전체 파이프라인(워치HR → 폰 AI → 코칭)을 처음으로 끝까지 검증한다.
목적: (1) 동작 확인, (2) **원시 데이터 수집**(spec-012) — 뛴 후 로그를 뽑아 분석/수정의 근거로 쓴다.

## 0. 전제

- 폰: Galaxy S26 Ultra, 워치: Galaxy Watch 8 (블루투스 페어링 완료 상태).
- **앱 2개 모두 설치 완료**(2026-07-03 밤 기준 최신 — 폰 adb, 워치 무선 adb로 설치/검증 끝).
  §1 설치는 코드가 바뀌었을 때만 필요. 워치는 권한 3종도 이미 허용됨.
- 워치 사전 검증 완료: 세션 시작/일시정지/종료, 포그라운드 알림, **화면off에도 세션 지속**(adr-009).
  미확인은 착용 상태 HR 스트림(→ 폰 수신)뿐.

## 1. 설치

```bash
# 폰 (USB 연결)
cd app  && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

# 워치 (무선 디버깅: 워치 설정 > 개발자 옵션 > 무선 디버깅 > 페어링/연결, 같은 Wi-Fi)
cd wear && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
~/Library/Android/sdk/platform-tools/adb pair <워치IP:페어링포트>   # 최초 1회
~/Library/Android/sdk/platform-tools/adb connect <워치IP:포트>
~/Library/Android/sdk/platform-tools/adb -s <워치serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

## 2. 출발 전 체크리스트 (집에서)

1. 폰: Zone2 Runner → 프로필에 나이/안정심박 확인(Zone2 경계가 여기서 나옴).
2. 워치: Zone2 Runner 실행 → "시작" → 권한 3개 허용(심박/위치/알림) → HR 숫자 뜨는지.
   - **지속 알림("Zone2 Runner")이 떠 있는지 확인** — 이게 백그라운드 유지의 증거(adr-009).
3. 폰: "실센서 러닝 (GPS+워치)" → 위치 권한 허용 → 시작 → **-- bpm이 워치 HR 숫자로 바뀌는지**.
   - 안 바뀌면: 워치 앱이 "시작" 상태인지, 블루투스 연결인지 확인. 그래도 안 되면 §5 폴백.
4. 둘 다 확인되면 일단 양쪽 다 "종료" 하고 출발(짧은 세션은 저장 안 됨).

## 3. 러닝 프로토콜 (10~30분, 무리하지 말 것)

1. **워치 먼저 시작**, HR 뜨는 것 확인 → **폰 "실센서 러닝" 시작**.
2. 폰은 손에 들거나 암밴드 — 러닝 중 화면이 자동으로 꺼지지 않게 해뒀음(코칭 LLM/GPS 때문).
3. 달리면서 자연스럽게 아래 상황을 만들어 주면 데이터 가치가 큼:
   - **손목 내리고 1~2분 달리기** (워치 화면 꺼짐 → HR 전송이 유지되는지가 핵심 검증)
   - 오르막/내리막 구간 포함 (경사 특징 + 코칭 문맥 검증)
   - 일부러 페이스를 올렸다 내렸다 (존 전환 → 코칭 트리거 + 판정 안정성)
4. 음성 코칭이 나오는지, 문구 방향이 상황과 맞는지 기억(또는 나중에 로그로 확인).
5. 종료: 폰 "정지 · 저장" → 리포트 확인. 워치도 "종료".

## 4. 러닝 후 — 데이터 회수/분석 (Claude가 함)

```bash
adb pull /sdcard/Android/data/com.zone2runner.app/files/runlogs ./fieldlogs
python3 ml/analyze_runlog.py fieldlogs/run-*.jsonl
```
Claude에게 "필드 로그 분석해줘"라고 하면: 워치HR 끊김 구간, 이상치 가드 동작, 판정 채터링,
개인화 uEst 궤적, LLM 코칭 지연/방향 적합성, GPS 페이스 노이즈를 분석해 수정 목록을 만든다.
분석 결과는 EXPERIMENT_LOG/report에 환류(개인 위치정보는 repo에 올리지 않음 — spec-012 프라이버시).

## 5. 문제 시 폴백

| 증상 | 조치 |
|------|------|
| 폰에 워치 HR 안 옴 | 워치 앱 재시작(시작 버튼) → 그래도 안 되면 sensor-poc 앱으로 /hr 경로 진단 |
| 워치 HR 자체가 안 뜸 | 워치를 손목에 밀착, 1분 대기(센서 예열). 권한 재확인 |
| GPS 페이스 이상 | 개활지에서 1~2분 대기 후 시작 |
| 전부 실패 | 폰 단독 "가짜 라이브 러닝"으로라도 완주 — 코칭/TTS/리포트/로그 파이프라인은 검증됨 |

어떤 경우든 **로그는 남는다**(모든 모드에서 기록). 실패 자체도 수집 대상이니 그대로 종료하고 로그를 가져오면 된다.

## 6. 이 테스트로 확인하려는 것 (우선순위)

1. 워치 백그라운드 HR 전송 지속(adr-009 RunService — 이번에 새로 이식, **미검증**)
2. 실 GPS 페이스/경사 품질 → 특징 입력의 현실 노이즈 수준
3. MLP 판정이 실데이터에서 안정적인지(채터링), 코칭 타이밍/방향 적합성
4. Gemini Nano 야외 실사용(지연 1~2.7초는 시뮬로 실측 완료, 방향 적합성은 관찰 필요)
5. 개인화 uEst가 실세션에서 어떻게 움직이는지(QA3)
