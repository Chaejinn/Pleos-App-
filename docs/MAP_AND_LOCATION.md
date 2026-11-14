# 지도·Android 위치 입력 연결

2026-11-14 · 로컬 구현

## 현재 지도 제공업체

카카오로 확정하여 SDK와 경로 표시 코드를 교체했다. 등록 방법과 현재 검증 범위는 [KAKAO_SETUP.md](KAKAO_SETUP.md)를 따른다. 아래 지도·화면 검증 기록은 교체 전 MapLibre 구현의 이력이다. 카카오 실제 타일 표시 완료를 뜻하지 않는다. 위치 입력 구현은 유지한다.

## 입력

- debug 전용 위치 입력 기록 확인 모드에서 Android LocationManager의 GPS_PROVIDER를 구독한다. 정확한 위치 권한과 GPS 활성 여부를 검사한다.
- time은 관측 UTC ms, elapsedRealtimeNanos는 ms로 변환한다. hasAccuracy의 미터 값, hasSpeed의 m/s, hasBearing의 방향만 매핑한다. 없는 필드는 null이다.
- Pleos confidence·heading·timestamp의 의미를 확정하거나 임의 변환한 구현이 아니다. Pleos/AAOS에서 표준 GPS_PROVIDER 제공 여부는 별도 검증 대상이다.
- 최대 64개 입력을 단일 처리 루프에서 순서대로 F-03에 전달한다. 큐 초과·공급자 비활성·DB 오류 시 이후 입력을 중단한다. 저장 동의·권한을 재확인하고, 정상 종료는 현재까지의 기록만 닫는다.
- 재생/Android 위치/미확인의 입력 종류를 암호화 metadata에 저장한다. 기존 출처 없는 기록을 실제 주행으로 표시하지 않는다.
- 이 모드는 수동 시작·종료의 개발 검증용이며 D/R/P 자동 Trip 상태기계나 실제 차량 연동 완료를 뜻하지 않는다.

## 지도

- MapLibre Native Android 11.11.0 + OpenFreeMap liberty 스타일. 별도 API key를 코드에 넣지 않았다.
- 온라인 지도는 별도 사용자 선택으로 기본 OFF. 켤 때 IP와 표시 영역이 제공업체에 전달될 수 있음을 안내한다. 기록 동의 없이 지도 화면을 만들지 않는다.
- 주행 경로를 GeoJSON 로컬 source에 넣고 과거·오늘 경로 색을 구분한다. segment별 Feature를 만들어 공백을 선으로 연결하지 않는다. route upload API는 사용하지 않는다.
- 입력 확인 중에는 최신 위치 marker·최근 승인 경로 최대 500개를 표시하며 위치를 따라간다. 확대·축소만 허용하고 pan·회전·기울기를 제한한다. 차량 플랫폼에서 UI 접근이 제한되면 지도를 만들지 않는다.
- 정차 화면에서는 탐색을 허용한다. OpenStreetMap·OpenMapTiles·OpenFreeMap 출처를 표시한다.
- Compose 수명주기에서 native MapView의 start/resume/pause/stop/destroy 및 low-memory 콜백을 전달한다. 지도 초기화·로딩 실패는 기록 저장과 분리하고 재시도를 제공한다.
- MapLibre의 공개 지도 타일 캐시는 기록 암호화 DB와 별개다. 전체 삭제 시 ambient cache도 제거하도록 연결했다. 타일 캐시의 차량 보안 정책 적합성·배포 조건은 실기기 검토가 필요하다.
- 온라인 지도 선택은 설정으로 유지한다. 재생·GPS·미확인 종류가 섞인 경로를 도로 탐험 성과나 실제 방문으로 간주하지 않는다. 지역 배지·고스트는 담당자 구현을 기다린다.

## 근거

- https://developer.android.com/reference/android/location/Location
- https://developer.android.com/reference/android/location/LocationManager
- https://maplibre.org/maplibre-native/android/examples/getting-started/
- https://openfreemap.org/quick_start/
- https://openfreemap.org/privacy/

## 남은 범위

- 실제 Pleos 위치·차량 신호 검증과 SDK 정확도 원천 확정.
- 동현·현민의 방문·보상·고스트 구현과 지역 데이터 연결. 이번 작업에서 중복 생성하지 않았다.
- 지역 마커·보상 카드·칭호 표시, AAOS 폼팩터·GPU·성능·오프라인·앱 정책 검증.
- 지도 캐시를 포함한 최종 개인정보·출처·배포 문서 검토.

## 검증 결과

- domain/app 전체 테스트 84개, 실패 0. debug 빌드·lint 통과.
- 일반 API 37 에뮬레이터를 Android Studio Run으로 실행했다. 온라인 지도 선택 후 실제 타일·기존 경로가 표시되는 것을 화면으로 확인했다. 확인 후 온라인 지도 설정은 OFF로 복원했다.
- GPS 진단 모드에서 adb emu geo fix로 31개 합성 위치를 주입했다. Android 위치 콜백으로 334m·1개 경로 구간이 저장됐고, 입력 출처 Android 위치 입력과 시작·종료 시각이 상세에 표시됐다. 실제 차량 속도 입력은 사용하지 않았다.
- GeoJSON 좌표 순서·공백 분리·빈 marker, 지도 기본 OFF와 설정 재생성, 최근 경로 500개 제한·공백 분리를 회귀 테스트로 검증했다.
- 지도 색은 주행 시작일의 서울 날짜 기준으로 구분한다. 자정을 가로지르는 Trip 내부를 날짜로 쪼개는 기능은 아직 없다.
- MapView 높이는 창 높이에 맞춰 제한하지만 실제 Pleos 가로형·분할 창의 전체 UI 및 GPU 성능 검증은 남아 있다.
- 전체 삭제의 지도 캐시 제거는 구현했으나, 기존 기록을 보존하기 위해 에뮬레이터에서 전체 삭제 버튼은 실행하지 않았다.
- 커밋·push·PR·머지는 수행하지 않았다.
- 최종 APK에서도 합성 재생 중 파란 현재 위치 marker·진한 실시간 경로·카메라 따라가기를 화면으로 확인했다. 재생 완료 후 합성 경로 재생 입력 출처로 저장됐고, 검증 후 온라인 지도를 OFF로 다시 복원했다.
- 지도 화면 캡처는 .local/map-preview.png와 .local/map-live.png에만 보관하며 저장소 산출물에 포함하지 않는다.
