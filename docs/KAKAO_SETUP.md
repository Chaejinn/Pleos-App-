# 카카오 지도 연결

2026-11-14 · 로컬 구현, 앱·키 등록 전

## 등록 순서

1. https://developers.kakao.com/ 에서 팀이 관리할 앱을 만든다.
2. Android 플랫폼에 패키지명 `com.roadlog.app`과 실행 APK의 서명 키 해시를 등록한다. 이 PC debug 해시는 `.local/kakao-registration.txt`에 있다. 다른 PC debug 서명과 배포용 release 서명은 각각 해시를 추가해야 한다.
3. 개발자 콘솔에서 카카오맵 사용 설정과 해당 앱의 이용 가능 상태를 확인한다.
4. 네이티브 앱 키를 `.local/kakao.properties`에 `nativeAppKey=발급받은값`으로 저장한다. `.local/kakao.properties.example`을 복사해 사용한다. REST API 키·Admin 키를 넣지 않는다. 환경변수 `KAKAO_NATIVE_APP_KEY`가 있으면 파일보다 우선한다.
5. Gradle 동기화 후 debug APK를 다시 빌드·설치한다. 앱에서 온라인 지도 사용 안내를 확인하고 켠다. 인증 성공·경로·현재 위치·백그라운드 복귀를 검증한다.

키 파일은 Git 제외 경로에 둔다. 네이티브 앱 키는 실행을 위해 APK에 포함되므로 서버 비밀키처럼 숨길 수 있는 값은 아니다. 채팅·로그에 출력하지 않는다.

## 구현과 제한

- Kakao Maps Android SDK 2.15.2로 배경 지도, 구간별 경로선, 현재 위치 표시와 따라가기를 연결했다. 수신 공백을 가로질러 선을 잇지 않는다.
- 제공업체 변경 전 온라인 지도 동의는 이어받지 않는다. 기본 OFF에서 카카오 정보 전달 안내 후 켠다. 앱의 경로 업로드 API는 사용하지 않는다.
- 키가 비어 있으면 지도 SDK를 시작하지 않고 안내를 표시한다. 암호화 기록 저장·조회는 사용할 수 있다. 인증·네이티브 로딩 실패도 기록 기능과 분리한다.
- SDK AAR의 네이티브 라이브러리는 ARMv7/ARM64다. 현재 API 37 에뮬레이터는 `x86_64,arm64-v8a`를 보고하지만, ARM 호환 실행 성공 여부는 키 등록 후 검증해야 한다. 실제 Pleos GPU·ABI·차량 환경도 별도 확인 대상이다.
- 지도 종료 시 공개 지도 캐시를 비운다. 지도가 닫힌 상태의 전체 삭제는 캐시 삭제 요청을 저장하고, 다음 지도 초기화 성공 때 실행한다. 이 경우 타일 캐시가 즉시 지워졌다고 보장하지 않는다. 이전 MapLibre 설치의 캐시는 이번 SDK로 삭제하지 못한다. 주행 기록 DB 삭제와 타일 캐시 삭제는 별도다.
- 지도 SDK 자체가 관광지 목록·지역 경계·방문 보상을 제공하는 것은 아니다. 담당자 데이터·도메인 로직과 별도로 연결해야 한다.

## 확인 결과

- domain/app 테스트 89개, 실패 0. debug APK 빌드·lint 통과.
- API 37 에뮬레이터에 최신 APK 설치 성공. 온라인 지도 안내에서 카카오 정보 전달 문구와 키 미등록 화면을 확인했다. 앱 프로세스가 유지됐으며, 검증 후 온라인 지도는 OFF로 복원했다. 기존 저장 기록을 삭제하지 않았다.
- 앱·키 미등록으로 카카오 인증 성공과 실제 타일 표시는 아직 검증하지 못했다. 이전 지도 화면 캡처는 카카오 검증 증거가 아니다.
- 커밋·push·PR·머지는 수행하지 않았다.

## 공식 문서

- https://apis.map.kakao.com/android_v2/docs/getting-started/quickstart/
- https://apis.map.kakao.com/android_v2/docs/getting-started/
- https://apis.map.kakao.com/android_v2/docs/api-guide/routeline/
