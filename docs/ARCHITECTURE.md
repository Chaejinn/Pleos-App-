# 현재 코드 구조와 변경 규칙

기준일: 2026-11-11 · 기준: 팀 문서 PR #1

## 경로 기준

사용자가 제안한 역할별 디렉터리는 유지하고, 경로가 다른 부분은 동현의 문서 규격을 따른다.
Android Kotlin 소스도 `app/src/main/java`에 두며 패키지는 `com.pleos.explorer`다.
applicationId `com.roadlog.app`은 기존 설치 식별자로 유지한다.

```text
app/src/main/java/com/pleos/explorer/
  platform/
    location/PleosLocationSubscription.kt   FusedLocation 구독·해제
    vehicle/                               차량 SDK·기존 입력 어댑터·가상 입력
    speech/                                음성 인터페이스·Android/Pleos 구현·발화 제한
  data/
    local/FileDexStore.kt                  기존 명소 수집 JSON 저장
    local/TripStore.kt                     기존 주행 JSON 저장
    catalog/LandmarkLoader.kt              기존 명소 카탈로그 로딩
    repository/                            설정 포트 구현·기존 리포트 조회/삭제
  presentation/
    map/                                   지도 준비 화면·기록 중 제한 화면
    collection/                            지역 배지 준비 화면
    archive/                               기존 주행 리포트 화면
    settings/                              기록 동의·음성·삭제·철회
    components/ActionButton.kt             공통 버튼
    ExplorerApp.kt                         화면 선택·상태 구독·공통 대화상자
    AppViewModel.kt                         화면 상태·사용자 작업
    ReportActivity.kt                      Android 진입점·권한 요청
  service/                                 주행 서비스·출발 판정·주행 상태
  di/AppGraph.kt                           객체 생성·연결
  di/ExplorerApplication.kt                Application 진입점
app/src/main/assets/catalog/legacy/landmarks.json
app/src/debug/assets/demo/                  데이터 대기 경로

domain/src/main/kotlin/com/pleos/explorer/domain/
  model/UserSettings.kt                    동의·음성 공통 모델
  model/legacy/                            기존 신호·주행·명소·수집 이벤트
  port/SettingsRepository.kt               설정 조회·변경 계약
  port/legacy/DexStore.kt                   기존 수집 저장 계약·메모리 구현
  engine/legacy/                           기존 주행·도감·고스트·말하기 판정
  geo/legacy/                              거리·격자·경로 지문
  usecase/                                 동현 구현 대기 경로
domain/src/test/                          기존 판정 회귀 테스트
test-data/                                공통 fixture 대기 경로
docs/                                     기준·계약·구현 및 검증 기록
apk/                                      배포 산출물
```

## 현재 범위

`legacy`는 이전 명소 데모의 호환 코드다. 파일 이동·타입 분리 과정에서 판정 기준값과 알고리즘을 변경하지 않았다.
새 지역 배지·폴리곤·누적 신규 도로·고스트 계약과는 별도다.
JSON 저장 파일 이름과 기존 필드를 보존하므로 이번 이동에 데이터 변환은 필요하지 않다.
기존 명소 파일은 `catalog/legacy/landmarks.json`으로 옮기고 로더 경로도 함께 바꿨다.
이 파일은 새 지역 GeoJSON 규격을 충족하는 데이터가 아니다.

`data/local`에 파일이 생겼다고 Room 구현이 완료된 것은 아니다.
지도·지역 도감은 준비 화면이며, Room·새 도메인 입력·보상 큐·새 아카이브는 후속 구현이다.
`platform/location`은 SDK 구독 수명만 분리한 것으로, 아직 `LocationSource` 포트를 구현하지 않는다.
속도가 없으면 기존 위치 신호가 전달되지 않는 결합도 이번 정리에서 변경하지 않았다.

## 모델·필드 변경 시 함께 수정할 것

1. 목적과 단위·nullable 의미·기본값을 `CONTRACTS.md`에 기록하고 공통 타입을 한 곳에서 수정한다.
2. 생산자인 SDK/카탈로그 매퍼와 소비자인 엔진·저장·UI를 함께 수정한다. 누락값을 0이나 가짜 정확도로 채우지 않는다.
3. 저장 필드 변경은 기존 기록 호환성 및 Room 도입 후 마이그레이션을 검토한다. 삭제 필드는 더 이상 읽지 않는지 확인한다.
4. JSON/fixture 필드가 바뀌면 `DEMO_AND_DATA.md`와 데이터·예상 결과도 함께 갱신한다.
5. 해당 동작의 테스트를 실행하고 `A_MIGRATION.md`의 구현 상태·검증 결과를 갱신한다.
6. 공통 계약 변경은 팀원이 확인할 수 있게 같은 변경 묶음에 문서와 코드를 포함한다. 현재 작업을 자동으로 push하지 않는다.

동현 담당 엔진을 앱 쪽에 복제하지 않는다. 엔진이 아직 없으면 대기 상태를 명시하고,
연결 과정에서 필요한 필드·포트 변경은 위 순서로 반영한다.
