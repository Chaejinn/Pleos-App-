# Pleos 공식 가이드 분석과 개발 재개 조건

확인일: 2026-11-13 · 문서 이슈: #5 · 상태: 분석 보고, 구현 재개 전 검토 필요

## 1. 목적과 검토 범위

[공식 시작 가이드](https://document.pleos.ai/docs/pleos-only/getting-started)에서 연결되는 설계·디자인·개발·SDK·테스트·등록 가이드 중 이 프로젝트에 관련된 주요 문서를 검토했다.
각 섹션의 링크는 확인한 공식 근거다. 모든 SDK의 개별 API와 이미지 안의 디자인 수치를 전수 검증한 문서는 아니다.
공개 문서와 저장소에서 확인할 수 없는 승인·단위·실기기 동작은 미확인으로 남긴다.

대조한 코드 기준은 main `bfcdcd0`과 [F-03 PR #4](https://github.com/Chaejinn/Pleos-App-/pull/4)의 `94352d6`이다.
main의 기능명세서·계약·구현 결정·작업 계획과 앱 입력·서비스·UI·저장·음성 코드를 확인했다.
F-03의 신규 API는 PR #4에 있으며 아직 앱에 연결되지 않았다.

이번 변경 범위는 이 분석 문서다. 코드·기존 명세·계약·SDK 버전·빌드 설정·설치 환경은 수정하지 않는다.
PR #4도 보존하며 병합하지 않는다. 아래 적용 판단은 설계 변경 승인이나 플랫폼 승인 결과를 의미하지 않는다.
기존 기능명세서 2장에도 공식 가이드가 더 엄격하면 우선하도록 명시되어 있어, 구현 재개 전에 그 적용 범위를 구체화해야 한다.

## 2. 먼저 해결해야 할 사항

| 우선순위 | 확인 결과 | 현재 프로젝트 영향 | 재개 전 필요한 근거 |
| --- | --- | --- | --- |
| 1 | Maps/Navigation 가이드는 게임 요소를 즉시 반려 항목으로 명시 | 탐험·뱃지·칭호·고스트를 포함한 앱을 지도 앱으로 자동 분류할 수 없음 | 실제 서비스 목적에 맞는 카테고리와 수집·보상 기능 허용 범위 확인 |
| 1 | FusedLocation 1.1.3 공개 모델에는 confidence가 있지만 accuracyM 필드는 없음 | 명세·PR #4의 미터 단위 정확도 50m 검증과 연결 근거 미확인 | confidence 단위·의미, 미터 정확도의 공식 원천 또는 승인된 대체 기준 |
| 1 | 주행 UX는 플랫폼 CarUxRestrictions 기준 | 현재 UI의 drive.running만으로는 규제 상태를 표현할 수 없음 | 플랫폼 규제 상태의 수신·실패 처리·기능별 제한 계약 |
| 2 | 저장 민감정보에는 앱 수준 암호화 필요 | 현재 평문 JSON 저장과 향후 Room 도입만으로 충족되지 않음 | 암호화 저장·키 관리·임시 파일·삭제·기존 데이터 이행 설계 |
| 2 | portrait/landscape·분할 window 대응 필요 | 명세의 가로형 기준과 고정 화면 가정 보완 필요 | 화면별 적응형 배치와 폼팩터 시험 결과 |
| 2 | 음성은 포커스·중단·재개 정책을 따라야 함 | 60초 간격과 사용자 ON/OFF만으로 허용 조건이 완성되지 않음 | 발화 용도에 맞는 컨텍스트, 포커스 상실·Suspend 처리와 실기기 시험 |

1~3번의 공식 근거는 각각 [지도 앱 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/navigation-app-development-guide),
[FusedLocation 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro),
[주행 규제 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)다.
위 표의 SDK 필드 확인은 아래 5절의 로컬 의존성 검사로 보완했다.

## 3. 앱 카테고리와 지도·내비게이션

### 공식 명시 사항

[앱 카테고리](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-category)는 설계 단계에서 목적을 정의하도록 요구한다.
Maps/Navigation의 핵심 목적은 위치 기반 길 안내이고, Mobility Services에는 이동 관련 위치 서비스가 포함된다.
지도를 표시한다는 사실만으로 전자를 선택할 수 있다는 근거는 없다.

[Maps/Navigation 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/navigation-app-development-guide)는 다음을 명시한다.

- 실제 기능과 등록·선언 카테고리가 일치해야 한다.
- 내비 고정 표시영역 사용을 위한 APP_MAPS 인텐트 선언을 설명한다.
- 하나의 내비 앱만 Active Route를 보유하며, Inactive Route·Background의 경로 안내를 제한한다.
- 주행 중 광고를 금지하며, 영상·게임 요소 포함과 복수 내비 동시 안내도 각각 반려 항목으로 명시한다.
- 경로 소유권·앱 포커스에는 CarAppFocusManager를 사용한다. 이는 오디오 포커스와 다른 책임이다.

### 프로젝트 적용 판단

현재 서비스는 길 안내 자체보다 주행 기록과 탐험 수집이 중심이다. Maps/Navigation 또는 Mobility Services 중 어느 분류가 승인될지는 자료만으로 확정할 수 없다.
뱃지·칭호·고스트가 심사에서 게임 요소로 판단되는지도 별도 확인이 필요하다. 정차 후 표시로 옮기면 자동 허용된다고 단정하지 않는다.
카테고리 확정 전에 APP_MAPS나 게임 카테고리를 임의 추가하지 않는다.

[NaviHelper 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)에서 확인한 역할은 기존 Navi Service에 목적지·경유지·경로·화면 요청을 보내고 정보를 받는 것이다.
현재 의존성에 NaviHelper가 있다는 사실을 앱 내부 지도 렌더링 구현 완료로 취급할 수 없다.
별도 지도 표시 수단의 차량 환경 지원, 라이선스, 오프라인 동작은 선정·검증해야 한다.

주행 기록을 백그라운드에서 저장하는 것과 백그라운드에서 경로·보상 음성을 안내하는 것을 분리한다.
Navigation의 안내 제한 문구를 모든 백그라운드 위치 수집 금지로 확대 해석하지 않는다. 기록 서비스는 별도의 수명주기·자원 검증이 필요하다.

## 4. 주행 안전과 앱 수명주기

[주행 규제 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)는 CarUxRestrictionsManager의 변경 콜백과 ActiveRestrictions를 반영하도록 한다.
[지역별 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy)은 DO 미선언 앱의 주행 중 사용 제한과 지역별 차이를 설명한다.
DO 선언 자체가 모든 기능의 허용을 뜻하지 않는다.

현재 호출 흐름은 RoadLogService의 수집 상태 → DriveStatus → ExplorerApp의 `restricted = drive.running`이다.
앱 소스와 매니페스트에서 CarUxRestrictions 연동·DO 선언은 확인되지 않았다.
수집 시작·종료 상태와 플랫폼 UI 규제 상태를 별도 입력으로 관리하고, 차량 기어·속도 판정은 Trip 기록 책임에 사용하도록 설계를 구체화해야 한다.
규제 연동 오류를 UI 허용으로 취급하지 않는 조건도 함께 정해야 한다.

[앱 구조 및 동작 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model)은 두 window, 방향·크기 변경, foreground/background 전환과 상태 복원을 요구한다.
현재 매니페스트에는 screenOrientation이 별도 선언되지 않았다. 공식 설명에 따르면 기본 portrait 동작이므로 차량 전체 화면이 가로라는 이유만으로 앱도 가로라고 가정할 수 없다.
기능명세서의 가로형 화면 기준은 portrait·분할 window 지원을 포함하도록 보완할 대상이다.

화면에서 사라짐, Activity 재생성, 서비스 중단, 프로세스 재시작, Suspend를 각각 시험한다.
PR #4의 세션 resume는 프로세스 재시작 경로 단절을 위한 도메인 계약이며, 플랫폼 전원·서비스 수명주기 연결까지 구현된 것은 아니다.

## 5. SDK·필드·권한 계약

### 버전과 환경

[SDK 릴리즈 노트](https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note)의 2026-10-19 조합은
Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1, FusedLocation 1.1.3이다.
현재 gradle/libs.versions.toml의 네 버전과 일치한다. 시작 예제의 Vehicle 2.3.1로 되돌릴 근거는 없다.
릴리즈 노트에는 VehicleSpeedListener 제거와 NaviHelper 좌표 생성자 순서 변경도 있으므로 과거 샘플을 그대로 복사하지 않는다.

[설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)는 minSdk 28 이상,
Gradle 8 이상, API 34 이미지, Cold boot·RAM 4GB를 안내한다. [에뮬레이터 릴리즈](https://document.pleos.ai/docs/pleos-only/release-note/emulator-release-note)는 2.1.0을 명시한다.
기존 환경을 초기화하거나 재설치할 필요가 있다는 근거는 이번 분석에서 발견되지 않았다. 현재 실행 상태를 새로 시험한 것은 아니다.

### 실제 위치 입력과 F-03 계약

[위치 데이터 접근](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access)은 위치 변경 콜백과 nullable 결과, 오류 콜백을 설명한다.
마지막 위치 조회는 새 위치를 계산하지 않으므로, 반복 조회를 새 1초 관측으로 위장해서는 안 된다.

현재 Gradle 캐시의 공식 FusedLocation-1.1.3.aar에서 classes.jar를 추출해 javap로 공개 API를 확인했다.
이 검사는 소스 수정이나 SDK 교체 없이 수행했다. [LocationData API 페이지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/location-data/entry)는 확인했으나 웹 추출 본문에 필드별 설명이 노출되지 않아 단위 근거로 사용하지 않았다.

| SDK 공개 접근자 | 확인한 타입 | 기존 명세·도메인과의 관계 |
| --- | --- | --- |
| getLatitude / getLongitude | Double | 좌표 입력 |
| getAltitude | Double | 현재 F-03 계약에 없음 |
| getHeading | Float | bearingDeg와 기준·단위·유효 범위 확인 필요 |
| getConfidence | Float | 신뢰도. 미터 정확도로 변환할 공식 근거 미확인 |
| getTimestampMillis | Long | 원본 관측 시각 의미 확인 후 timeMs 매핑 |
| speed / accuracy 접근자 | 해당 모델에서 확인되지 않음 | 별도 속도·정확도 원천 검증 필요 |

registerFusedLocationCallback은 리스너만 받으며 공개 시그니처에 요청 간격 인자가 없다.
기능명세서의 1초 요청은 SDK에서 설정 가능한지 별도 확인해야 한다. 문서만으로 1Hz 수신을 보장하지 않는다.

현재 앱은 FusedLocation 콜백 → PleosVehicleSource.emitPosition → legacy VehicleSignal.Location으로 전달한다.
속도 미수신 시 위치도 전달하지 않으며, confidence와 원본 timestamp는 새 LocationSample로 매핑되지 않는다.
PR #4의 검증기는 accuracyM이 null이면 거절하도록 구현되어 있으므로, 정확도 원천이 확인되지 않은 채 연결하면 승인 스트림을 만들 수 없다.
confidence를 임의로 5m·50m로 바꾸거나 결측 정확도를 0으로 채워 통과시키지 않는다.

### API 선택과 지원 여부

[SDK API 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api)은 표준 기능에 AAOS API를 우선한다.
[가용성 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability)는 Vehicle의 checkXXXCapabilities,
FusedLocation의 isSupported와 AAOS property별 가용성 확인을 요구한다.
현재 앱의 위치 지원 검사와 효율 capability 검사는 확인되지만, 이것만으로 모든 차량 신호가 검증된 것은 아니다.

[권한 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission)의 함수별 요구 권한과
[SDK 사용 신청](https://document.pleos.ai/docs/pleos-only/create-my-project)은 매니페스트 선언과 별도로 확인할 대상이다.
공개 안내는 Vehicle State·NaviHelper를 신청 없이 사용 가능으로, FusedLocation·TextToSpeech 등을 준비 중 예시로 구분한다.
모든 SDK가 동일하게 승인 신청을 요구한다고 해석하지 않는다. 프로젝트의 현재 제공·신청·승인 상태는 저장소에서 확인할 수 없었다.
에뮬레이터에서 호출 가능한 사실만으로 실제 배포 접근 권한까지 승인되었다고 가정하거나 API 접근 범위를 임의 확장하지 않는다.

## 6. 디자인과 입력

### 6.1 공식 디자인 원칙과 수치

다음은 [공식 UI Design Guide](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro)에 명시된 기준이다.
프로젝트 적용안·현재 검증 결과와 구분한다.

| 항목 | 공식 기준 | 프로젝트의 확인 방법 |
| --- | --- | --- |
| 정보량 | 핵심 정보를 1~2초 안에 파악할 수 있도록 구성 | 위치·기록 상태와 상세 통계를 분리하고 화면별 우선순위 검토 |
| 입력 피드백 | 입력 응답 0.25초 기준, 지연 시 처리 중임을 표시 | 탭·기록 시작·삭제의 즉시 피드백과 중복 입력 방지 확인 |
| 대비 | 텍스트·아이콘·이미지 대비 4.5:1 이상 | 최종 주·야간 색 조합과 지도 위 오버레이를 각각 측정 |
| 글자 높이 | 중요 정보 5mm 이상, 보조 정보 3mm 이상 | 대상 디스플레이에서 실제 렌더링 크기·글꼴 배율 확인 |
| 문장 길이 | 문장·단락은 3줄 이내 등 간결한 텍스트 지침 | 좁은 window에서 동의·오류·안내 문구의 줄바꿈 검토. 언어별 글자 수 규칙을 한국어에 임의 환산하지 않음 |
| 주·야간 | 주변 조명에 맞는 밝기·대비 조정 | 지도·앱 배경·팝업·선택/비활성 상태를 양쪽 테마에서 검토 |
| 조작 | 한 손으로 가능한 단순한 상호작용 | 주요 기능의 터치 경로와 접근 가능한 닫기·뒤로 동작 확인 |
| 주의 분산 | 주행 중 시선을 끄는 움직임·자동 스크롤 억제, 운전자 필요와 관련된 알림 | 보상·고스트 연출의 노출을 규제 상태와 연결. 카테고리 허용 여부는 별도 확인 |

현재 ActionButton의 76dp·24sp는 팀 명세 구현값이며 공식 컴포넌트의 확정 치수라는 근거는 없다.
[Typography](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/typography)의 12sp 이상 권장 설명만으로 차량 중요 정보의 물리적 높이를 충족했다고 판단하지 않는다.
폰트 계층은 제목·본문·보조·버튼 라벨의 역할별로 정리한다. Asta Sans는 공식 문서가 제공하는 시스템 폰트 참조이며, 다른 폰트가 일괄 금지된다는 의미는 아니다.

### 6.2 레이아웃·시각 자산·컴포넌트

| 공식 문서 | 확인한 규격·의미 | 적용 시 결정할 사항 |
| --- | --- | --- |
| [Display Layout](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout) | 앱 window와 Status bar·Driving view·GNB를 구분, 가로·세로 대응 | 차량 전체 해상도 대신 앱의 실제 가용 영역을 사용하고 시스템 패널에 가려질 때 상태 유지 |
| [Window Policy](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/window) | Connect-S/W의 분할·실행·이동 정책과 window 전환 | 특정 분할 비율이나 처음 표시된 위치를 고정 전제로 삼지 않고 변경 후 재배치 시험 |
| [Colors](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/colors) | 기본·투명·정적 색상 및 배경·버튼·텍스트 등 역할별 색상 | 배경·표면·텍스트·선택·비활성·경고의 역할을 테마에 매핑. 최종 색상값은 자산 확인 후 확정 |
| [Iconography](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/contents-creation/iconography) | 기본 아이콘 제작 그리드 48×48dp, Filled/Outlined 표현 | 숫자 탭 대신 기능을 알아볼 수 있는 아이콘·라벨 적용 검토. 아이콘 그리드를 터치 영역 최소값으로 해석하지 않음 |
| [Basic button](https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/basic-button) | 라벨·선택적 아이콘 구조, Pressed·Disabled·Loading 상태 | 비동기 작업 중 피드백과 중복 실행 차단. 버튼 종류·여백·모서리·터치 영역은 자산과 대조 |
| [Tabs](https://document.pleos.ai/docs/connect/guide/docs-design/components/segmented-menu) | Enabled·Pressed·Selected, Box/Line, 균등 배치와 왼쪽 정렬 옵션 | 콘텐츠 내부 탭의 역할과 선택 상태 정의. 이 문서를 모든 하단 내비게이션의 강제 대체 규격으로 해석하지 않음 |
| [Page Navigation](https://document.pleos.ai/docs/connect/guide/docs-design/components/page-navigation) | 주요 메뉴와 하위 페이지의 Back 상태 구분 | 세 주요 화면과 상세·설정의 깊이를 구분하고 뒤로 복귀 상태를 보존 |
| [System Popup](https://document.pleos.ai/docs/connect/guide/docs-design/components/popups/system-popup) | 제목·설명·본문·확인·취소, Large/Medium 및 버튼 정렬 옵션 | 획득·상세·동의·삭제 목적에 맞춰 내용과 액션 수를 제한하고 좁은 window에서 검증 |
| [Toast](https://document.pleos.ai/docs/connect/guide/docs-design/components/toast) | 일시 알림의 배치·최대 폭 설명 | 지속 보관할 보상과 구분. 문서의 24·792·712 표기에는 단위가 명시되지 않아 dp로 확정하지 않음 |
| [Animation](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/animation) | 상태 전달용 로딩 표현과 옵션 | 일반 로딩 가이드를 주행 중 보상·고스트 애니메이션 허용 근거로 사용하지 않음 |

공식 [버튼 크기 SVG](https://document.pleos.ai/assets/images/basic_size-033b09c8bbef4d1776a3f4c7c70a49d0.svg)를 시각 확인했다.
Large·Medium·Small 예시는 있으나 숫자 치수·단위가 표시되지 않아 이미지 픽셀을 Android dp로 옮기지 않는다.
텍스트로 확인한 컴포넌트 상태와 이미지에서 확인하지 못한 수치의 검증 상태를 구분한다.

공식 소개 페이지는 [display guide .fig](https://document.pleos.ai/img/Pleos_Connect_display_guide_215.fig)와
[design assets .fig](https://document.pleos.ai/img/Pleos_Connect_design_assets_215.fig)를 제공한다.
링크 위치까지 확인했으며 Figma에서 파일을 열어 토큰·치수·간격을 추출하는 검증은 수행하지 않았다.
이 파일들의 버튼·팝업·내비게이션 세부 수치를 확인한 뒤 팀의 디자인 기준을 확정해야 한다.

### 6.3 화면별 적용안과 현재 구현 대조

아래 배치는 기능명세서의 세 주요 화면과 팝업을 유지한 프로젝트 적용안이다. 공식 가이드가 이 앱의 화면 구성을 직접 지정한 것은 아니다.
주행 중 노출은 3~4절의 카테고리·CarUxRestrictions 검토를 선행한다. 이 표는 구현 완료나 정책 허용 판정이 아니다.

| 화면 | 현재 코드에서 확인한 상태 | portrait·좁은 window 적용안 | landscape·넓은 window 적용안 | 제한 상태와 검증 항목 |
| --- | --- | --- | --- | --- |
| S-01 탐험 지도 | MapScreen은 준비 문구·시작 버튼, RecordingScreen은 기록 중 안내 | 현재 위치·경로와 기록 상태를 우선하고 보조 조작을 최소화 | 동일한 핵심 정보를 유지하고 허용된 보조 정보만 확장 | 지도 조작 범위와 미확인 배지 허용 여부 확인, 주행 중 상세·연출 제한, 지도 대비·시스템 패널 가림 시험 |
| S-02 뱃지 도감 | CollectionScreen은 준비 문구, 새 지역 그리드·칭호 UI 미구현 | 요약·지역/대학 구분·선택한 항목을 세로 흐름으로 제공 | 그리드와 선택 항목 상세를 함께 배치하는 안 검토 | 제한 전환 시 화면을 닫고 선택 상태 보존, 긴 라벨·미획득/선택 상태와 주야간 대비 확인 |
| S-03 주행 기록 | ArchiveScreen은 기존 주행 목록·텍스트 상세·삭제 확인 | 목록→상세→비교의 Back 경로와 핵심 요약 우선 | 목록·요약·경로의 분할 배치를 검토하되 같은 정보 접근 보장 | 제한 중 상세·목록 접근 차단, 지도 로딩/기록 없음/삭제 실패 상태, 확대 글꼴에서 잘림 확인 |
| P-01 획득 카드·P-02 뱃지 상세 | 기능명세에 정의되어 있고 신규 화면 구현은 대기 | 한 항목의 제목·핵심 내용·확인/닫기를 우선 | 불필요한 확대 없이 팝업 계층 유지 | 정차·규제 허용 조건에서만 노출하는 적용안, 제한 전환 시 카드 닫기와 미확인 큐 보존 시험 |
| P-03 권한·동의 | ConsentScreen에 목적 설명·동의 버튼이 있음 | 목적·보관·철회 설명을 짧은 단위로 나누고 실제 줄 수 확인 | 같은 동의 내용을 유지하고 화면 폭만으로 긴 문장을 만들지 않음 | 규제 상태와 권한 거절·철회 시 흐름 검증. 첫 실행이라는 이유로 주행 제한을 우회하지 않음 |
| P-04 설정·삭제 확인 | SettingsScreen·ExplorerApp에 스위치·철회·삭제 확인이 있음 | 라벨·스위치·확인/취소를 구분하고 터치 가능 폭 확보 | 관련 항목을 묶되 상호작용과 상태 의미 유지 | 설정 접근 제한, 처리 중 중복 입력 차단, 취소·실패·완료 피드백 검토 |

현재 공통 UI는 MaterialTheme, 숫자 아이콘의 NavigationBar, 고정 여백과 글자 크기를 사용한다.
ActionButton에는 비활성 인자가 있지만 별도 Loading 표현은 없으며, 오류 AlertDialog는 restricted 여부와 무관하게 표시된다.
따라서 컴포넌트의 로딩·규제 전환·알림 노출, 테마·아이콘·반응형 배치를 구현 시 확인해야 한다.
이 항목은 현재 코드에 대한 정적 확인이며 실제 화면의 대비·가독성·터치 적합성을 측정한 결과가 아니다.

### 6.4 디자인 검증 산출물과 완료 조건

1. 화면별 portrait/landscape 및 주·야간 설계와 적용 자산·토큰·버전·출처를 기록한다.
2. 버튼·탭·팝업의 기본·눌림·선택·비활성·로딩과 빈 결과·실패·권한 거절 상태를 대조표로 남긴다.
3. 대상 폼팩터별 실제 앱 window 크기·밀도·글꼴 배율, 글자 높이·대비·터치 영역·간격을 확인한다. mm와 dp/sp의 일괄 환산값은 가정하지 않는다.
4. resize·시스템 패널·키보드·Back·규제 변경 후 콘텐츠와 입력 상태를 확인하고 화면별 검증 결과를 남긴다.
5. 입력 피드백 지연과 장시간 지도 표시도 측정한다. 정책·기술·디자인 검증 결과를 각각 기록한 뒤 구현 완료로 판단한다.

현재 완료된 범위는 공식 본문·버튼 크기 예시 확인과 코드·명세 대조다.
Figma 자산 내부 수치 추출, 최종 토큰 결정, 화면 시안 제작, Emulator 실측은 미수행이다.
디자인 분석을 보강하는 이번 문서 변경으로 UI 구현 작업을 재개하지 않는다.

### 6.5 차량 입력·키보드

[CCP 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/ccp-rotary-controller-development)에 따르면 사용자 설치 앱의 CCP 지원은 선택이다.
미지원 시 필수 기능을 터치로 완료할 수 있어야 하며 미지원 선언을 검토해야 한다. 최상위 Back의 숨김 처리 등 별도 동작 기준도 있다.
기본 탑재 앱의 CCP 필수 조건을 이 프로젝트에 무조건 적용하지 않는다.

[IME 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/ime-window-resize-handling-guide)는 입력 필드 유무에 따른 resize·insets 처리를 구분한다.
검색·로그인 등을 추가할 때 적용하며, 주행 중 입력 허용 여부는 플랫폼 규제가 우선이다.

## 7. 음성과 앱 연동

[오디오 포커스 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development)는
재생 전 AudioAttributes·AudioFocusRequest, 포커스 변경·거절·중단 처리를 요구한다.
현재 SpeechGate는 동의·설정·주행 여부와 60초 간격을 확인하지만 플랫폼 포커스 상태는 받지 않는다.
PleosSpeaker는 tts.speak를 호출하며 앱 코드에서 포커스 요청·상실 처리는 확인되지 않았다.
SDK 내부 처리까지 없다고 단정하지 않고, 앱과 SDK 중 누가 어떤 책임을 담당하는지 확인해야 한다.
보상 안내를 NAVIGATION·SAFETY 컨텍스트로 임의 분류하지 않는다.

[선행 묵음 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/voice-guidance-preroll-silence-guide)는 150~200ms를 권장한다.
단순 호출 지연보다 오디오 데이터 수준 패딩을 우선하며, 포커스·출력 경로 준비를 대체하지 않는다.
[Suspend 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/suspend-mode-audio-focus)는 출력 중단·포커스 반납과 재평가 후 Resume을 설명한다.
기존 ko-KR 재생 성공 기록만으로 이 정책 검증까지 완료되었다고 볼 수 없다.

[Gleo Interface](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts)는 App Schema와 Action/Callback에 의한 음성 명령 연동이다.
TextToSpeech로 안내하는 것과 별개다. 현재 명세에 Gleo 명령 기능이 없으므로 이 분석만으로 구현 범위에 추가하거나 모든 앱의 필수 요건이라고 단정하지 않는다.

## 8. 보안·저장·개인정보

[앱 보안 가이드](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-security-guide)는 민감정보에 앱 수준 암호화를 적용하도록 한다.
내부 저장소·SQLite·SharedPreferences·캐시·임시 사본에도 같은 원칙이 적용된다.
안전한 암호와 IV/nonce 관리, Keystore 등 키 보호, 최소 권한, 정식 배포 서명과 배포 디버깅 비활성화도 확인해야 한다.

현재 TripStore는 주행 격자 경로를 JSON 문자열로 파일에 쓰고 읽는다. 위치에서 파생한 경로 이력의 평문 저장을 재검토해야 한다.
PR #4의 TrackRecordingStore는 저장 인터페이스이므로 암호화 구현이 존재하지 않는다. Room의 트랜잭션 보장과 암호화는 별도 요구다.
원본 경로뿐 아니라 snapshot·보상 표시 경로·임시 파일·이행 중 사본까지 보호·삭제 경계를 정해야 한다.

[개인정보 설계 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/security-privacy)는 최소 수집, 목적 설명, 로그 보호와 공용 차량의 사용자 전환을 다룬다.
현재 동의·삭제 UI는 기반으로 유지하되, 운전자 교체 시 기록 분리·노출 정책과 보관 기간 이행을 확인한다.
로컬 전용이라는 이유로 처리방침·위치정보 관련 제출 자료가 면제된다고 해석하지 않는다.

## 9. 테스트 기준과 현재 증거의 한계

[폼팩터 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide)는 별도 창의 Emulator,
Go 1.22 이상 Host Display Bridge와 FormFactorConsole을 사용한다. 8095/8096 포트와 여러 화면·방향을 검증한다.
[Virtual Console 설정](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)은 8088 포워딩을 사용하며,
[사용 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)는 도메인별 상태 조작과 앱 반응 확인을 설명한다.
웹 접속 성공과 값 변경이 앱 콜백으로 전달되는 성공을 별도 증거로 남긴다.

[성능 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt)의 기준은
주행 중 Navigation CPU 15% 이하, 비내비 앱 5% 미만, 정차 중 모든 앱 25% 이하, RAM 400MB 이하다.
CPU 측정 방식·기간은 시험 계획에서 명시하며, 단위 테스트 시간이나 개발 PC 사용량으로 대체하지 않는다.

| 검증 묶음 | 필요한 시나리오 | 이번 분석 상태 |
| --- | --- | --- |
| 도메인 | 정확도·도약·간격·공백·중복·저장 실패·복구 | PR #4의 기존 테스트 결과 참조. 실제 SDK 매핑 검증과 별개 |
| 규제·카테고리 | 제한 상태 변경, UI 축소, DO 선언과 앱 동작, 거절·연결 오류 | 설계·플랫폼 검증 필요 |
| SDK | capability·권한·콜백 시각·수신 주기·정확도 단위·리스너 해제 | 가이드와 정적 코드/아티팩트 확인만 수행 |
| 폼팩터 | Connect-S/W 계열, portrait/landscape, 분할·resize, 시스템 패널·IME | 실행하지 않음 |
| 수명주기 | 다른 내비 전면, 백그라운드 기록, 재생성·강제 종료·복구·Suspend | 실행하지 않음 |
| 음성 | 통화·음악·Gleo·안전 안내와 충돌, 거절·상실, 재개·첫 음절 | 실행하지 않음 |
| 저장·보안 | 암호화·키·취소·원자성·삭제·보관 정리·이행 실패 | 저장 설계와 통합 시험 필요 |
| 성능 | 장시간 입력과 지도 표시, 메모리·CPU·불필요한 폴링 | 측정하지 않음 |

기존 PR의 빌드·단위 테스트 통과를 Pleos 정책 준수·실차 호환·등록 가능 판정으로 확대하지 않는다.
분석 문서만 변경하므로 이번에는 빌드·앱 실행·환경 설치를 다시 수행하지 않았다.

## 10. 등록·심사 준비

[심사 제출 가이드](https://document.pleos.ai/docs/pleos-only/distribute/app-submission-guide)는 APK, 카테고리·권한·개인정보·위치정보,
처리방침 URL·해당 시 위치정보 약관, 아이콘·스크린샷과 심사용 자료를 요구한다.
정식 마켓 등록 요건과 공모전의 접수·영상 제출 요건은 동일하다고 가정하지 않는다. 공모전 전용 공고·예외 자료는 별도 확인해야 한다.

[앱 리뷰 가이드](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide)는 실제 기능·안전·보안·메타데이터 일치를 심사한다.
기본 가이드를 따랐다는 사실만으로 승인이 보장되지는 않는다.
[태그 가이드](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-tagging-guide)는 외국어 공식 앱명 사용 시 원문 태그 규칙을 정한다.
[버전 정책](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-version-management-policy)은 배포·심사 시 변경 버전을 요구한다.
[업데이트 가이드](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-update-guide)는 자동 업데이트 미지원과 개발사 구현 항목을 설명한다.
디자인 소개의 OTA 고려 문구만으로 자동 업데이트가 제공된다고 단정하지 않는다.

## 11. 개발 재개 순서

1. 카테고리와 탐험·보상·고스트의 허용 범위부터 확인한다. 플랫폼 또는 공모전 운영 측의 공식 근거가 필요하다.
2. FusedLocation confidence·heading·timestamp의 의미, 정확도 원천, 수신 주기와 차량 신호 지원을 확인한다.
3. 결과에 따라 기능명세서의 적용 결정, 입력 계약, 플랫폼 UX 제한·음성·저장·화면 요구를 수정안으로 정리한다.
4. 기존 PR #4에서 유지할 부분과 수정할 부분을 확정한다. 도메인 계산 분리는 유지 가능하나, 정확도 계약과 실제 저장 연결은 재검토 대상이다.
5. 사용자 검토 후 각 수정 작업의 이슈를 먼저 생성하고 해당 이슈 브랜치에서 구현한다. 채진·동현·현민의 소유 경계를 유지한다.
6. 실제 SDK 입력 → 검증 → 암호화 저장 → 허용된 화면의 최소 흐름을 연결하고, 위 테스트 행렬과 제출 자료를 준비한다.

현재 필요한 외부 확인은 카테고리·게임 요소 허용, confidence의 단위·정확도 대응, 프로젝트 SDK 사용 승인,
공모전 전용 제출 기준이다. 저장소와 공개 가이드에서 확인되지 않은 답을 임의로 채우지 않는다.
이번 분석 완료를 기능 구현 재개 지시로 해석하지 않으며, PR #4와 후속 기능 작업은 검토 전까지 진행하지 않는다.
