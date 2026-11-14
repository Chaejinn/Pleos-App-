# A 담당 규격 전환 기록

## 반영 기준

- 문서 PR: https://github.com/Chaejinn/Pleos-App-/pull/1
- 원격 main 기준: 126ce3f. feature/sdk에 로컬 병합, README는 기존 내용과 문서 안내를 보존해 해결.
- 개발 브랜치: `feature/app-integration`. 원격 main 기준으로 코드·개발 문서를 반영하며 README와 이전 빌드 APK는 제외.
- Mac 에뮬레이터와 Android Studio는 실행하지 않음. JVM 테스트·컴파일·lint만 수행.

## 이번 구현

- `:core` → `:domain`, `app → domain` 의존성 유지.
- 문서 네임스페이스 `com.pleos.explorer` 적용. applicationId는 기존 설치 식별자 `com.roadlog.app`을 유지하며 배포명 확정과 분리.
- 기존 도메인 코드는 `model/legacy`·`port/legacy`·`engine/legacy`·`geo/legacy`, 테스트는 `engine/legacy`로 이동. 알고리즘과 기준값은 바꾸지 않음.
- Gradle 버전 카탈로그 추가. 검증된 AGP 8.5.2·Kotlin 2.0.21·API 34 조합 유지.
- Compose 컴파일러는 Kotlin과 같은 2.0.21, Compose BOM 2024.09.03·Activity 1.9.2·Lifecycle 2.8.6 사용.
- 앱 코드를 platform / data / presentation / service / di로 분리. 위치 구독·저장 파일·카탈로그 로더·기능 화면·Application을 각각 실제 파일로 분리.
- Compose 3개 탭의 화면 기반, ViewModel·StateFlow, 생성자 주입·수동 AppGraph 구성.
- 지도·지역 배지 화면은 준비 상태만 표시. 실제 지도나 판정 완료 화면을 흉내 내지 않음.
- Android 런타임 권한과 별도로 위치 기록 저장 동의 추가. 초기값 false.
- 서비스 직접 시작도 동의·위치 권한을 검사. 동의 전 SDK 구독·기록 파일 생성 차단.
- 동의 철회 시 수집 중단. 기존 기록은 유지.
- 음성 설정 초기값 false. OFF일 때 음성 SDK를 생성하지 않으며 재생 중 OFF/철회 시 종료.
- 음성 간 최소 60초. 간격 안의 새 음성 요청은 생략하고 정차 시 발화 종료.
- 기존 주행 스타일의 주행 중 중계와 도착 후 자동 요약 발화 제거.
- 기존 JSON 기록 전체 삭제·확인창. 주행 종료 후 또는 미동의 화면에서 실행 가능.
- 삭제 중 신규 주행을 차단하고 동의·음성 설정은 보존. 자동 시연 seed 복구·재수집 없음.
- 가상 주행 버튼과 서비스 모드는 debug에서만 허용.

실제 경로와 공통 필드 변경 절차: [현재 코드 구조](ARCHITECTURE.md).
명소 파일은 `assets/catalog/legacy/landmarks.json`으로 이동했으며 기존 사용자 JSON 필드는 유지.

## 새 계약 연결 상태

문서의 SettingsRepository를 구체화한 최소 API를 추가함.

- `UserSettings(recordingConsentGranted = false, ttsEnabled = false)`
- `settings: StateFlow<UserSettings>`
- `suspend setRecordingConsent(granted: Boolean)`
- `suspend setTtsEnabled(enabled: Boolean)`

설정 영속화는 PreferenceSettingsRepository가 담당한다. DB 사용자 주행 데이터와 설정 저장소를 분리한다.
다른 공통 모델·엔진·포트는 아직 생성하지 않았으며 동현의 구현과 중복 정의하지 않는다.
기존 VehicleSignal 입력과 JSON 저장소는 호환 데모용이며 새 계약의 LocationSource/VehicleSource/DriveStore 구현이 아니다.
기존 속도-위치 결합·정확도 미제공 등의 문제를 해결했다고 주장하지 않는다.

## 검증

표준 명령:

```bash
./gradlew :domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

이 컴퓨터:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
./gradlew --gradle-user-home /private/tmp/roadlog-gradle \
:domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

새 테스트는 설정 초기값·재생성 후 영속성·철회, 서비스 직접 실행 시 미동의 기록 차단,
음성 OFF·정차·60초 경계·시계 역행, 전체 삭제 후 설정 보존을 검증한다.
Robolectric은 JVM 테스트이며 Android Emulator를 실행하지 않는다.

검증 결과: domain 12개 + app 13개 = 총 25개 테스트 통과, 실패·오류 0개.
debug APK 빌드 성공, lint 오류 0개·경고 32개. 경고는 고정 버전 업데이트 안내,
기존 XML 미사용 리소스, 아이콘·백업 설정 등에 관한 것이며 경고를 숨기지 않았다.

기기 UI 조작·주행 중 제한·권한 철회·실제 TTS 재생은 데스크톱 검증이 필요하다.
`apk/RoadLog_release.apk`는 이전 통합본이므로 이번 소스의 제출용으로 사용하지 않는다.

## 다음 연결 작업

1. 동현: domain/model·port·engine의 공통 계약 실제 구현. 이번 SettingsRepository 이름을 검토해 동일 타입을 중복 생성하지 않음.
2. 채진: 새 LocationSample/VehicleSample 포트에 SDK 어댑터 연결. 정확도 단위·센서 관측 시각·null 의미 검증.
3. 채진: DriveStore·ArchiveRepository·RewardRepository의 Room 구현, transaction·삭제·복구.
4. 채진: 실제 탐험 지도·지역 배지 도감·아카이브·보상 큐 UI 연결.
5. 현민: catalog·입력·seed·expected 파일 공급. 이번에는 빈 경로만 준비했으며 데이터를 만들지 않음.
6. 데스크톱: SDK 지원 확인·Virtual Console DDS 문제·화면 상태·서비스 지속성·시연 검증.

`legacy`는 새 계약 완료를 뜻하지 않는다. 신규 도메인 코드에서 legacy 모델을 공통 계약처럼 사용하지 않는다.

## Windows 검증 및 첫 보완 (2026-11-12)

- origin fetch 후 확인한 원격 브랜치: main, docs/team-conventions, feature/app-integration. 새 domain 구현 브랜치는 관측되지 않음. 기존 공통 모델을 임의로 중복 정의하지 않음.
- 사용자 PC: JDK 17.0.2 적용 후 Gradle sync 및 assembleDebug 성공. 일반 API 37 전화 에뮬레이터에 adb 설치·ReportActivity 실행 성공. Pleos 차량 신호 검증은 아님.
- 기존 데모 아카이브에 최근순 목록·선택 상세·확인 후 개별 삭제 추가. 수집 명소는 유지.
- 주행 종료 뒤 기록 탭으로 전환. 파일 저장 방식은 기존 JSON을 유지하며 Room 전환은 별도 작업.
- 기존 JSON에는 종료 시각·정밀 거리·실제 신호 지원 정보가 없어 표시값을 추정하지 않음. 화면에 기존 데모 집계임을 표시.
- 남은 작업: 공통 계약 구현 수령·통합, Room transaction 및 보상 큐, 승인 위치 경로, 실제 지도 공급자 검증, 지역 카탈로그 연결, Pleos 환경 검증.

## 2026-11-14 로컬 앱 연결

- Android GPS 진단 입력을 F-03에 연결. 미터 정확도·관측 시각·선택적 속도/방향을 보존하며 차량 속도에 위치 입력을 종속시키지 않는다. 실제 Pleos 공급자 지원 검증과 별개다.
- 입력 종류 metadata와 지도/위치 구독·설정·캐시 정리를 추가했다. 별도 위치 입력 문서: MAP_AND_LOCATION.md.
- 초기 MapLibre/OpenFreeMap 온라인 배경 지도와 저장 경로 표시 검증 후 카카오 SDK로 교체했다. 현재 등록·검증 상태는 KAKAO_SETUP.md 참고. 온라인 지도 기본 OFF, 별도 선택·고지, 인터넷 장애 시 기록 기능 유지.
- 기존지도 준비 문구를 대체했으나 지역 배지·고스트·보상 큐 데이터/엔진 통합은 담당자 작업을 기다린다.
- domain 공통 모델을 중복 구현하거나 미확인 SDK 정확도를 가짜 값으로 채우지 않았다. 실제 경계 데이터·획득 완료 UI를 임의 생성하지 않았다.
- 로컬 파일만 수정하며 커밋·push·PR·머지는 하지 않았다.
