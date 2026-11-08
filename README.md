# 🔋 방전알리미 (Battery Alert)

**팀 RE:VOLT**

**주차하기 전에 내일 아침 시동이 걸릴지 미리 알려주고, 12V 배터리가 위험하면 스스로 지켜주는 Pleos Connect 차량 앱**

차량 신호(공조·열선·전조등·좌석·기어)와 날씨 예보로 12V 배터리 잔량을 실시간으로 추정하고, 시동이 어려워지기 전에 원인과 해결 방법을 알려줍니다.

---

## 주요 기능

| 구분 | 기능 |
|---|---|
| 예측 | 주차 시 시동 가능 시간 예측, 시간대별 기온을 반영한 시동 한계 그래프, 추정 신뢰도 표시 |
| 보호 | 12V 보호 하한선(P단 자동 OFF, 1분 전 예고·유지 선택), 차박·대기 모드 남은 시간 + what-if |
| 기록 | 주차 에너지 영수증, 주간 리포트(절약 제안), 장소별 주차 프로필, 12V 관리 점수 |
| 안내 | 겨울 출발 브리핑, 방전 대처 가이드(3단계 + 긴급출동 전화), 정비소 공유 QR |
| 음성 | Pleos 음성 인식·안내로 질문과 차량 제어 ("내일 아침 시동 괜찮아?", "열선 다 꺼줘") |
| 절전 | 하차 2분 뒤 차량 읽기·네트워크 요청을 모두 멈춤 (앱이 주차 중 차를 깨우지 않음) |

---

## 테스트 환경

| 항목 | 버전 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (Android API 34) |
| 차량 신호 입력 | Pleos Virtual Console (localhost:8088) |
| 개발 도구 | Android Studio Rabbit 2 (2026.2.2) |
| 언어 / UI | Kotlin, Jetpack Compose |
| Pleos SDK | Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1, SpeechToText 3.3.1 |
| 외부 라이브러리 | ZXing core 3.5.3 (QR 생성) |
| 외부 API | Open-Meteo (날씨 예보, 키 불필요), Kakao Local (블루핸즈 검색, 키 필요) |

---

## 실행에 필요한 설정

### 1. Virtual Console 연결
에뮬레이터를 켤 때마다(재부팅 포함) 한 번 실행해 주세요.

```
adb forward tcp:8088 tcp:8088
```

### 2. 에뮬레이터 시간대 (재부팅 시 초기화되므로 매번 실행)
출발 시각·날씨 계산이 한국 시간 기준이라서 필요합니다.

```
adb shell settings put global auto_time_zone 0
adb shell cmd alarm set-timezone Asia/Seoul
```

### 3. 권한
차량 권한(CAR_CLIMATE, CAR_SEATS, CAR_SAFETY 등)과 음성 권한은 매니페스트에 선언되어 있습니다. 처음 음성 버튼을 누를 때 마이크 권한을 허용해 주세요.

### 4. 카카오 REST API 키 (소스에서 빌드할 때만)
보안을 위해 키는 저장소에 올리지 않았습니다. 소스에서 직접 빌드하는 경우, 프로젝트 최상위의 `local.properties`에 아래 한 줄을 추가해 주세요. 제공된 APK에는 이미 포함되어 있습니다.

```
KAKAO_REST_KEY=발급받은_REST_API_키
```

> `adb` 명령을 찾을 수 없다고 나오면 Android SDK 경로의 adb를 직접 실행해 주세요.
> 예: `"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" forward tcp:8088 tcp:8088`

---

## 앱 실행 방법

### APK로 바로 실행
1. 저장소의 `apk/revolt_battery_alert.apk`를 내려받습니다.
2. Pleos Connect 에뮬레이터를 켠 상태에서 설치합니다.
   ```
   adb install revolt_battery_alert.apk
   ```
3. 위의 "실행에 필요한 설정" 1·2번을 실행한 뒤 앱을 엽니다.

### 소스에서 빌드
1. 저장소를 내려받아 Android Studio로 엽니다.
2. `local.properties`에 카카오 키를 추가합니다.
3. Gradle Sync 후 Pleos Connect 에뮬레이터를 선택하고 실행(▶)합니다.

---

## 시연 방법

- 상단의 **🧪 시연 패널**에서 주차 시간 경과, 하한선 도달, 출발 브리핑, 주간 기록 채우기 등을 바로 재현할 수 있습니다.
- 차량 상태(기어, 좌석, 열선, 공조, 전조등)는 **Virtual Console(localhost:8088)**에서 바꿉니다.
- 에뮬레이터가 지원하지 않는 값은 시연 신호로 대체했습니다.
  - 타이어 공기압·워셔액 경고: 에뮬레이터 미지원 (`not supported`) → 시연 패널의 시연 신호 사용
  - 차대번호(VIN): 에뮬레이터에서 빈 값 → 시연 패널의 가짜 VIN 사용
  - 실제 차량에서는 SDK 값을 그대로 사용합니다.

---

## APK 다운로드

- 저장소 내 위치: [`apk/revolt_battery_alert.apk`](apk/revolt_battery_alert.apk)

---

## 개인정보

- 차대번호와 주차 위치 좌표는 기기 안에만 저장하며 외부로 보내지 않습니다.
- 정비소 공유 QR에는 위치·주행 경로를 넣지 않고, 차대번호는 뒤 6자리를 가립니다.
