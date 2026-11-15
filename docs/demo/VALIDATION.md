# 서울 시연 검증 기록

검증일: 2026-11-15 (Asia/Seoul) · 작업 브랜치: `jhmin`

## 빌드와 자동 테스트

JDK 17.0.21, Android SDK 34, Gradle Wrapper 8.14.3.

```powershell
./gradlew.bat :domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --offline
```

| 항목 | 결과 |
| --- | --- |
| domain 테스트 | 109개 통과 |
| app 테스트 | 18개 통과 (시연 경로/소스 테스트 4개 포함) |
| 전체 테스트 | 127개, 실패·오류 0 |
| lintDebug | 오류 0, 경고 33 |
| assembleDebug | 성공, `com.roadlog.app.demo` |
| assembleRelease | 성공, 기존 `com.roadlog.app`, 서명 없는 APK |
| release 격리 | DEX에 시연 `DemoActivity` 미포함 확인 |

첫 오프라인 실행에서 누락된 Kotlin 라이브러리는 온라인 빌드로 받은 뒤 최종 오프라인 검증을 통과했다. SDK 도구 XML 버전, 기존 Pleos SDK deprecated API, 네이티브 심볼 관련 경고는 남아 있다. lint 경고를 숨기거나 baseline을 추가하지 않았다.

새 테스트는 두 명소가 순서대로 한 번씩 수집되는지, 다시 재생할 때 동일하게 수집되는지, 약 1.2km 코스가 서울 종로 범위에 있는지, 모든 도로 회전점이 보간 과정에서 보존되는지, 프레임 간 도약이 없는지, 일시정지·종료·재시작을 검증한다. 재생 타이밍은 절대 예정 시각으로 예약하여 콜백 처리 시간이 계속 누적되는 지연을 방지한다.

## 에뮬레이터 확인

- Android 14 / API 34, Google APIs x86_64 `RoadLog_API_34`.
- 화면 1280×800, density 160. GPS 위치 입력 및 런타임 위치 권한 요청 없이 시연 앱 실행.
- 가로 탐험 지도 → 도로 이동 → 광화문/국립민속박물관 뱃지 → 탐험 완료 `2 / 2` → 도감 → 기록 순서 확인.
- [초기 화면](explore.png), [광화문 획득](badge-gwanghwamun.png), [박물관 획득](badge-museum.png), [완료](completed.png), [도감](collection.png), [기록](history.png).
- [화면 녹화](roadlog-demo.mp4)는 동일 에뮬레이터의 실제 실행 화면이다. 별도 합성 앱 화면을 사용하지 않았다.
- `AndroidRuntime` 오류 로그 없이 실행. 콜드 부팅 시 기존 AVD 데이터를 삭제하지 않았다.

## 설치 파일

[Roadlog-Seoul-Demo.apk](../../apk/Roadlog-Seoul-Demo.apk) · [SHA-256](../../apk/Roadlog-Seoul-Demo.apk.sha256)

디버그 서명된 시연 전용 APK다. 실제 서비스 배포용으로 서명하거나 실차 승인을 받은 파일이 아니다.

## 브랜치 보존

작업 시작 시 `jhmin`과 `origin/jhmin`은 `7b4008f59388342b5e14a2f4a6f9c8fa5796e789`였고 작업 디렉터리는 깨끗했다. `main`이 7커밋 앞선 공통 이력을 사용하고 있어 파일들이 유사하게 보이는 상태였다.

아래 로컬·원격 브랜치 시작값을 확인했으며 해당 브랜치로 checkout, merge, reset, push하지 않았다.

- `main`: `1acedb9bbc127fce90ca543d3613051484914d16`
- `merge_jhmin`: `1152fb8151f1e80cb1467ee24592b4d28c1846cd`

실제 Pleos 기기에서의 GPS, 차량 신호, 지도 SDK, 실차 UX 정책 및 주행 중 표시 승인은 미검증이다. 시연용 오프라인 지도와 실제 위치 기능을 구분한다.
