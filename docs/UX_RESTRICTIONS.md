# 플랫폼 주행 UX 제한 연결

2026-11-13 · 로컬 구현·검증

## 기준과 선택

- [Pleos 주행 규제 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide): CarUxRestrictionsManager 콜백과 ActiveRestrictions를 반영한다.
- [CarUxRestrictions](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictions), [Manager](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictionsManager): 최초 상태 조회, 변경 리스너 등록·해제 API를 확인했다.
- API 34 android.car.jar의 실제 시그니처를 javap로 확인했다.
- 현재 앱은 상세 도감·기록·설정이 중심이고 DO가 선언되지 않았다. 이번 구현은 최적화 필요 상태 또는 하나라도 있는 제한 플래그에서 모든 상세 UI를 잠그는 보수적 정책이다. 개별 기능 허용 범위를 확정한 것이 아니다.
- appCategory·DO 선언·SDK 권한은 추가하지 않았다. 카테고리 승인·정확도 원천·오디오 포커스는 별도 미확인 사항으로 유지한다.

## 구조

- platform/ux/UxAccessGate: 수집 상태와 독립된 연결 상태·규제 상태 StateFlow. 최초·연결 중·미확인·연결 오류·백그라운드 상태에서는 잠근다.
- CarUxRestrictionSource: 자동차 환경에서만 생성한다. 비동기 Car 연결, 최초 조회와 listener, 5초 최초 연결 timeout, 리스너 해제·disconnect를 담당한다. 재연결과 이전 Activity에서 늦게 온 콜백을 별도 세대 값으로 거절한다. 폴링하지 않는다.
- NonAutomotiveUxSource: 일반 휴대폰 데모 실행을 분리한다. 차량 API를 호출하지 않는다. 이 상태의 성공은 Pleos 규제 검증이 아니다.
- ReportActivity의 onStart에서 새 관측을 시작하고 onStop에서 해제한다. 복귀하면 이전의 허용 상태를 재사용하지 않는다.
- AppViewModel은 UI 관측값을 제공하고, 삭제·복구·음성 ON·동의 ON을 실제 실행할 때도 접근 상태를 검사한다. 동의 철회와 음성 OFF는 제한 중에도 허용한다.
- ExplorerApp은 플랫폼 상태 또는 수집 중 상태에서 상세 UI·스크롤·탭·설정·동의 입력·대화상자를 숨긴다. 규제 진입 시 삭제 확인과 설정 화면을 닫는다.
- 화면 제한으로 수집 서비스를 중단하지 않는다. Trip 판정·기어·속도와 플랫폼 UX 상태를 서로 대체하지 않는다.

## 검증 범위

- fake source: 초기·불완전 상태 차단, 최적화 플래그와 ActiveRestrictions 차단, 연결 상실·복귀 재확인, 늦은 콜백, Activity 교체, 구독 실패 cleanup, 중복 시작·해제, 일반 전화 데모 상태를 단위 테스트한다.
- Robolectric: 수집 서비스가 꺼져 있어도 제한 중의 삭제·복구·설정 ON이 실행되지 않는지, 제한 중에도 동의 철회·음성 OFF가 가능한지 검증한다.
- 실제 Pleos/AAOS에서 규제 콜백·서비스 단절·정차 전환·화면 이동을 받는 통합 시험은 남아 있다. 일반 API 37 전화 에뮬레이터의 실행을 그 증거로 쓰지 않는다.

## 결과

- 전체 테스트 73개, 실패 0. debug APK 빌드·lint 통과.
- Android Studio Run으로 API 37 전화 에뮬레이터 실행. 기존 탭·검증 경로 재생이 유지됨을 확인했다.
- 재생 도중 홈 화면 전환 후 앱 복귀에서 저장 완료·기록 목록 조회를 확인했다. 이는 Activity가 비활성화돼도 주행 서비스의 재생 저장이 계속되는 검증이다.
- 실제 AAOS/Pleos 규제 콜백 및 기어·주행 규제 전환은 미검증이다.
- 이번 작업은 로컬 파일 변경만 수행했다. 커밋·push·PR·머지는 하지 않았다.
