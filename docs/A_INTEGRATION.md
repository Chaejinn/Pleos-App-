# 이전 데모 통합 작업 기록

이 문서는 규격 전환 전의 실행 기록입니다. 현재 구조·동작·미구현 범위는 [규격 전환 기록](A_MIGRATION.md)을 따릅니다.
Android Studio는 이후 사용자 요청으로 제거했으며 Mac 에뮬레이터는 종료했습니다.

## 작업 범위

A 담당: `app/`, SDK 및 빌드 설정, 에뮬레이터 통합, APK, 화면 녹화.
B 담당의 `core/`와 C 담당의 `landmarks.json`은 이번 SDK 연결 작업에서 수정하지 않았습니다.
기존 GitHub main 기록을 유지한 `feature/sdk` 브랜치에서 작업합니다.

## SDK 연결

- Vehicle 2.3.2: 핸들 각도, 브레이크 페달, 방향지시등, 기어, 연비/전비.
- Android Automotive `PERF_VEHICLE_SPEED`: m/s 차량 속도. FusedLocation에는 속도 필드가 없습니다.
- FusedLocation 1.1.3: 위치와 heading. 지원 여부를 확인한 뒤 구독합니다.
- NaviHelper 3.0.5: 다음 안내 거리 조회와 회전 안내·경로 종료 콜백.
- TextToSpeech 3.3.1: ON_DEVICE 모드. 서버 자격 증명을 사용하지 않습니다.

모든 신호는 메인 스레드에서 `elapsedRealtime()` 시각으로 core에 전달합니다.
속도 또는 위치가 오래됐거나 사용할 수 없으면 위치 신호 생성을 보류합니다.
연비와 전비를 한 기록에 동시에 섞지 않습니다. 미지원 효율 값은 생성하지 않습니다.
주행 종료 시 등록했던 동일한 리스너로 구독을 해제하고 각 SDK를 release합니다.

출발 후 P단으로 들어와야 도착 처리합니다. 연결 직후 P단은 주행을 끝내지 않습니다.
주행 중 재시작 버튼은 비활성화하며 도감과 리포트를 숨깁니다.

## 로컬 개발 환경

- JDK 17: `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- Android SDK: `$HOME/Library/Android/sdk`
- Android Studio 2026.2.2.9 (Rabbit 2 Patch 1) 설치 완료. 이번 검증은 명령줄 도구로 수행했습니다.
- Pleos AVD: `RoadLog_Pleos_2_1`, API 34, arm64-v8a, RAM 4096MB.
- 전체 화면 1920×720, 160dpi. Pleos 런처가 앱을 오른쪽 분할 영역에 표시합니다.
- 설치 이미지 SHA-1: `24d28eb2f18eba5e87c861a7eb2bdccebb049419` (공식 XML과 일치).

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

이 세션은 Gradle 캐시로 `/private/tmp/roadlog-gradle`을 사용했습니다.
동일 캐시를 재사용하려면 `--gradle-user-home /private/tmp/roadlog-gradle`을 추가합니다.

```bash
"$ANDROID_HOME/emulator/emulator" -avd RoadLog_Pleos_2_1 -no-snapshot
adb devices
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 shell am start -n com.roadlog.app/.ui.ReportActivity
```

실제 device serial은 `adb devices` 결과를 사용합니다. 이번 검증은 `-port 5556`으로 실행했습니다.
Pleos는 운전자 사용자(이번 AVD에서는 10)에서 앱을 실행하므로 파일 확인 시 사용자 번호도 지정합니다.

```bash
adb -s emulator-5556 shell run-as com.roadlog.app --user 10 cat files/trips.json
adb -s emulator-5556 forward tcp:8088 tcp:8088
```

Virtual Console: `http://127.0.0.1:8088/`.

## 검증한 시나리오

1. 최초 위치 권한 요청 → 허용 → 데모 1 실행.
2. 광안대교 수집, 도착 후 리포트 자동 갱신.
3. 데모 1: 2구간, 급제동 1회, 깜빡이 누락 1회, 효율 11.
4. 데모 2: 같은 경로 2구간, 급제동·깜빡이 누락 0회, 효율 13.
5. 실제 SDK 모드에서 핸들·브레이크·방향지시등·기어 PARK·속도·위치·내비 콜백 수신.
6. 첫 PARK 수신 후 서비스가 도착 처리되지 않고 출발을 기다리는 동작 확인.

기존 Android 기본 TTS는 이 이미지에서 엔진 연결이 실패했습니다.
이를 해결하기 위해 Pleos ON_DEVICE TTS를 추가했습니다.
첫 발화에서 `ON_DEVICE_ENGINE_ERROR`가 발생했고 서비스 로그에서 `unsupported locale: ko-`를 확인했습니다.
지역 없는 `Locale.KOREAN` 대신 `Locale.KOREA`(ko-KR)를 지정하도록 수정했습니다.
수정본의 서비스 로그에서 `getLocale ... ko_KR`, `UpdateTtsStart`, `UpdateTtsDone`을 확인했습니다.
한국어 고스트 중계의 ON_DEVICE 재생 완료 콜백을 검증했습니다.
호스트에서 실제 소리를 청취하는 검증과 오디오가 포함된 영상 녹화는 별도입니다.

## 알려진 에뮬레이터 제약

- Vehicle 효율 API는 현재 이미지에서 `ERROR_NOT_SUPPORTED_PROPERTY`를 반환합니다.
- Virtual Console 웹 조회는 되지만 제어 요청은 HTTP 503을 반환했습니다.
  `/vehicle/state`에서 `gear.bridge_ready=false`, `controls.dds_ready.all=false`를 확인했습니다.
  따라서 Virtual Console의 D단·브레이크 조작을 통한 동적 통합 검증은 아직 미완료입니다.
- Pleos 런처의 투명 오버레이가 터치를 가로채는 경우가 있었습니다.
  앱을 전면에 둔 뒤 Tab으로 버튼 포커스를 이동하고 Enter로 실행하면 서비스가 정상 시작됐습니다.
- `screenrecord` 녹화는 시스템 오디오를 포함하지 않습니다.
- `.local/videos/`에 두 데모의 초기 무음 녹화본과 최신 통합본 녹화본을 저장했습니다.

## release 서명 및 제출 산출물

`.local/release-signing.properties`가 있으면 `assembleRelease`가 해당 키로 서명합니다.
이 컴퓨터에 `.local/roadlog-release.jks`와 서명 속성 파일을 생성했습니다.
두 파일은 Git에서 제외하며 비밀번호를 소스 코드에 넣지 않습니다.
업데이트 빌드에도 같은 서명키가 필요하므로 키와 속성 파일을 별도로 백업합니다.

```properties
# .local/release-signing.properties의 형식 예시 (실제 값은 로컬 파일 사용)
storeFile=.local/roadlog-release.jks
storePassword=YOUR_LOCAL_PASSWORD
keyAlias=roadlog
keyPassword=YOUR_LOCAL_PASSWORD
```

```bash
./gradlew :app:assembleRelease
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --verbose app/build/outputs/apk/release/app-release.apk
```

서명 설정이 없는 컴퓨터의 release 산출물은 unsigned입니다. 제출용으로 사용하기 전에 서명을 확인합니다.
제출 후보 APK는 `apk/RoadLog_release.apk`에 복사합니다. 팀명 확정 후 제출 규칙에 맞춰 이름을 변경합니다.

## 공식 문서

- [Vehicle 설정](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup)
- [FusedLocation 공식 샘플](https://github.com/Pleos-Playground/pleos-sample-fusedlocation-app)
- [NaviHelper 설정](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk)
- [TextToSpeech 설정](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/TextToSpeech/importing-sdk)
- [에뮬레이터 설치](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [Virtual Console 설정](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)
