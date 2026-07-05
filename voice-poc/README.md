# voice-poc — 음성/호흡 토크테스트 PoC

본 앱(spec-016 Tier2, adr-018) 통합 전에 "숨참을 어떻게 측정할까"를 실험하는 독립 PoC.
phone + wear 2모듈, appId `com.zone2runner.voicepoc` 공유(Data Layer 라우팅).

## 접근 변천(실측으로 배운 것)

1. **음향 VAD**(완독시간/끊김/발화량) — 고정 녹음창에서 완독시간이 무의미, 숨참을 못 잡음.
2. **ASR 완성도**(어디까지 읽었나) — 짧은 문장은 힘들어도 완독되어 "편함"으로 오판.
3. **호흡 직접 감지(현재)** — 온디바이스 **YAMNet**(AudioSet)으로 숨소리(Breathing/Gasp/Pant)와
   말소리(Speech)를 분류해 숨참 정도를 5단계로. 토크테스트의 본질(내용 아님, 호흡)에 부합.

## 모델 준비 (필수, git 미포함)

`*.tflite`는 저장소에서 제외된다. 빌드 전 YAMNet 모델을 받아 assets에 둔다:

```bash
curl -L -o voice-poc/phone/src/main/assets/yamnet.tflite \
  https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite
```

(약 4.1MB, MediaPipe 호스팅 YAMNet float32. 헤더가 `TFL3`면 정상.)

## 빌드/설치

```bash
cd voice-poc
JAVA_HOME=<Android Studio jbr> ./gradlew :phone:assembleDebug :wear:assembleDebug
# 폰/워치는 같은 appId라 주소로 구분해 설치
adb -s <phone> install -r phone/build/outputs/apk/debug/phone-debug.apk
adb -s <watch> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

## 구성

- `BreathClassifier` — YAMNet 추론(MediaPipe AudioClassifier), 숨/말 라벨 점수 집계
- `BreathJudge` — 숨/(숨+말) 비율 → 5단계(TalkLevel). GAIN은 실기기 점수로 보정
- `PhoneActivity` — 5초 낭독 → 판정 + 점수 + 상위 감지 라벨(보정용)
- (탐색 이력, 보존) `VoiceAnalyzer`/`TalkJudge`(음향), `SpeechTalkTest`/`Completeness`(ASR)
- 워치(`WearActivity`) — 낭독 PCM을 ChannelClient로 폰에 전송(음향 경로)

## 정직한 한계

- 러닝 소음이 최대 오차원(문헌) → 상시측정보다 잠깐 멈춘 "토크테스트 순간"용.
- YAMNet의 Breathing/Pant/Gasp는 일반 AudioSet 학습 → 피트니스 특화 검증/파인튜닝 여지.
- 더 정확한 호흡 파운데이션 모델(Google HeAR)은 무거워 온디바이스 부적합(서버급).
