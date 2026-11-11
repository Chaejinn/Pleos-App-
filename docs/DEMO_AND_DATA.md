# 공통 데이터·시연·검증 규격

계약 버전: 1 · 데이터 담당: 현민 · 로더: 채진 · 판정·기대 결과 검증: 동현

이 문서는 세 사람이 같은 데이터를 전달하고 실행하기 위한 규격이다. 아래 파일 목록·시나리오는 제작 기준이며 실제 경계 데이터·재생 경로·seed가 이미 준비되었다는 의미는 아니다.

## 1. 디렉터리와 파일

| 경로 | 내용 |
| --- | --- |
| `app/src/main/assets/catalog/manifest.json` | 버전·파일 목록·출처·라이선스 |
| `app/src/main/assets/catalog/regions.geojson` | 시·군·구 Polygon/MultiPolygon |
| `app/src/main/assets/catalog/universities.geojson` | 선택 대학 지정 구역 |
| `app/src/main/assets/catalog/coast-points.json` | 동·서해 칭호 지정 지점 |
| `app/src/main/assets/catalog/badges/` | 지역·대학 자체 제작 일러스트 |
| `test-data/demo-v1/manifest.json` | fixture 버전·고정 시각·실행 시나리오 |
| `test-data/demo-v1/regions.geojson` | 실지역과 구분한 합성 영역 |
| `test-data/demo-v1/inputs.jsonl` | 위치·차량·타이머·동의 이벤트 |
| `test-data/demo-v1/seed.json` | 과거 경로·29개 뱃지 등 초기 영구 상태 |
| `test-data/demo-v1/expected.json` | 이벤트·영구 결과·UI 접근 기대값 |
| `app/src/debug/assets/demo/` | 선택 fixture의 동일 사본, debug에서만 포함 |

test-data가 원본이다. debug 사본을 독립 수정하지 않고 채진이 복사 단계에서 SHA-256을 비교한다. 배포용 catalog와 합성 demo는 분리하고 `DEMO_` 지역 코드를 실제 시·군·구로 표시하지 않는다. 실제 지역 지도 시연은 출처와 좌표를 검증한 별도 fixture 버전으로 만든다.

## 2. Manifest

각 manifest에는 다음 필드를 둔다.

| 필드 | 타입·내용 |
| --- | --- |
| `schemaVersion` | 정수 1 |
| `dataVersion` | 문자열, 예: `demo-v1` / `regions-2026-11-11` |
| `coordinateSystem` | 문자열 `WGS84` |
| `files` | `{path, sha256}` 목록. manifest 자신은 제외 |
| `sources` | `{name, url, retrievedAt, license, attribution, modifications}` 목록 |
| `synthetic` | 합성 fixture이면 true, 실제 자료이면 false |
| `baseTimeMs` | fixture에서만 사용, 고정 UTC epoch ms |
| `timeZone` | fixture에서 `Asia/Seoul` |
| `scenarioIds` | fixture 시나리오 ID 목록 |

출처·라이선스가 확인되지 않은 실제 자료에는 `license: "UNVERIFIED"`를 기록하고 배포 catalog로 승격하지 않는다. 빈 값을 '사용 가능'으로 해석하지 않는다. SVG 원본이 있다면 권리 기록과 함께 보존하고 앱 표시 자산은 프로젝트에서 정한 변환 형식으로 공급한다. 공식 교표·마스코트는 사용하지 않는다.

## 3. GeoJSON 계약

- 최상위는 FeatureCollection, 좌표 순서는 `[longitude, latitude]`다. Kotlin GeoPoint 생성자의 위도·경도 순서와 혼동하지 않는다.
- 지역 geometry는 Polygon 또는 MultiPolygon이다. ring은 닫고 hole·섬을 유지한다. 다른 좌표계를 WGS84라고 표기하지 않는다.
- 지역 properties 필수: `code`, `name`, `provinceCode`, `provinceName`, `representativePoint`, `badgeAssetPath`.
- representativePoint도 `[longitude, latitude]`이며 해당 지역 표시용 대표점이다. 폴리곤 centroid를 무조건 사용하지 않는다.
- code는 문자열로 보존한다. 앞자리 0을 잃는 정수 변환을 하지 않는다. 공급원 코드 체계·기준일을 manifest에 기록한다.
- 대학은 `code` 대신 `universityId`를 사용하고 이름·대표 좌표·에셋 경로를 둔다. 차량 접근 가능한 지정 구역을 검증하고 300m 기준과의 대응을 기록한다.
- coast-points는 `id`, `coast: EAST/WEST`, `name`, `point: [lon, lat]`, `radiusM: 2000` 목록이다.
- 로더는 bbox를 미리 계산·캐시하고 후보 인덱스를 만든다. 위치마다 전체 파일을 다시 읽거나 JSON 파싱하지 않는다.

데이터 납품 검증은 code 중복, 좌표 범위·finite, 닫힌 ring, geometry 타입, 에셋 파일 존재, 출처·라이선스, 시연 경로 내부 판정을 포함한다. 잘못된 파일을 조용히 빈 지역 목록으로 대체하지 않고 데이터 오류 상태로 전달한다.

## 4. 재생 입력

JSONL 한 줄은 한 이벤트다. 공통 필드는 `scenarioId`, `sequence`, `offsetMs`, `type`이다. sequence는 시나리오 안에서 고유하고 증가한다. offsetMs는 0 이상이며 같은 시각의 이벤트는 sequence로 순서를 정한다.

| type | payload |
| --- | --- |
| `consent` | `granted: Boolean` |
| `location` | `latitudeDeg`, `longitudeDeg`, `accuracyM`, `speedMps`, `bearingDeg` |
| `vehicle` | 계약의 gear·parkingBrakeEngaged·speedMps·odometerKm·센서 값. 해당 이벤트에서 관측한 필드만 포함 |
| `tick` | 별도 payload 없이 타이머 입력 |

센서 null은 미지원·결측을 뜻한다. 필드 생략은 이 이벤트에 갱신이 없다는 뜻이다. 생략된 필드를 이전 값으로 무기한 연장하지 않고 필드별 관측 시각과 신선도 규칙을 적용한다.

timeMs=`baseTimeMs + offsetMs`, elapsedMs=`offsetMs`로 변환한다. 기본 위치는 1초 간격, 정확도 5m, 주행속도 10m/s다. 차량 신호와 Tick도 기본 1초 간격으로 공급해 신선도와 타이머 처리를 검증한다.

재생속도를 높일 때도 offsetMs를 바꾸지 않는다. 처리 대기 시간만 단축한다. 엔진·TTS 간격·방문 타이머는 주입한 재생 시계를 사용한다. 실제 벽시계를 사용해 빠른 재생에서 체류 판정이 달라지지 않게 한다.

SDK 어댑터를 우회하는 전용 UI 코드를 만들지 않고 LocationSource·VehicleSource의 debug 구현을 바꾼다. seed 로딩은 debug 전용 관리 동작이며 일반 사용자 전체 삭제 이후 자동으로 복구하지 않는다.

## 5. Seed 규격

seed는 계약 모델의 필드를 그대로 쓴다. `schemaVersion`, `scenarioId`, `trips`, `trackPoints`, `exploredRoads`, `regionBadges`, `achievementTitles`, `drivingMetrics`, `ghostResults`, `tripRegionVisits`, `achievementProgress`, `pendingRewards`를 포함한다. 없는 목록은 빈 배열, nullable 값은 null이다. 순환 참조·존재하지 않는 필수 Trip 참조를 만들지 않는다.

핵심 시나리오에는 다음 상태를 넣는다.

1. DEMO_A를 포함한 서로 다른 합성 지역의 REGION 뱃지 29개. 새로 방문할 DEMO_B는 미획득이며 시연 도감의 전체 지역 목록에는 30개가 모두 존재한다.
2. 현재 재주행할 도로와 같은 방향의 과거 Trip 한 개. 해당 도로의 마지막 통과는 현재 기준 31일 전이다. 원본 180일 범위 안이다.
3. 고스트 구간 5km를 충분히 포함하는 과거 포인트와 격자 기록. 신규 거리 검증용 이탈 도로는 과거 기록에서 제외한다.
4. 칭호·미확인 큐는 비어 있다. 권한 상태는 Android 테스트 환경에서 별도로 설정하고, 기록 동의는 입력 이벤트로 시작한다.
5. 입력은 30일 전 과거 기록과 29개 획득 상태를 준비한 시연이라는 사실을 설명한다. 현재 주행 한 번으로 30개 지역을 방문한 것처럼 표시하지 않는다.

별도 시나리오는 seed를 독립 로딩한다. 이전 실행의 뱃지·큐·방문 횟수가 다음 결과를 바꾸지 않게 한다.

## 6. 접수용 공통 시나리오 DEMO-01

합성 공간에 DEMO_A·DEMO_B를 만들고 현재 경로를 A → B 내부 1km → A의 과거 경로 5km 이상 → A의 신규 경로 순서로 제작한다. 과거 고스트 경로는 이미 뱃지가 있는 A 안에 둬 과거 5km 주행 기록과 B 미획득 상태가 모순되지 않게 한다. 지역 경계 통과는 명확하게 구분하고, 거리는 생성된 fixture에 공통 거리 함수를 적용한 값으로 검산한다.

| 순서 | 입력·상태 | 기대 결과 |
| --- | --- | --- |
| 1 | seed 초기화, 권한 허용·동의 false | 기록·Trip·보상 0건 추가 |
| 2 | 동의 true, P에서 D 진입 | Trip 1개 시작, UI 제한 |
| 3 | A를 지나 B에 진입, B 내부 유효 이동 1km | DEMO_B 최초 획득, REGION 30개, REGION_EXPLORER 최초 획득 |
| 4 | 과거 경로와 같은 방향으로 연속 500m | 고스트 시작 1회, 이전 499m 구간에서는 비활성 |
| 5 | 같은 과거 경로로 누적 5km 이상 | 재방문 칭호 조건 충족 후보, 마지막 통과 31일 전 근거 유지 |
| 6 | 기록에 없는 경로로 200m 이상 이탈 | 고스트 종료 1회, ROAD_REVISITOR 획득, matchedDistance에 이탈 거리 제외 |
| 7 | 새 도로를 더 주행한 뒤 P | Trip 종료, GhostResult.newRoadM 확정, 정차 접근 허용 |
| 8 | P-01·도감·최소 결과 조회 | 신규 뱃지 1개, 칭호 2개, 고스트 결과 1개. 큐 4건을 확인 순서대로 처리 |
| 9 | 앱 재시작 | 획득·주행·결과 유지, 확인 완료 카드 재표시 없음 |

주행 중에는 카드·고스트 애니메이션·도감 상세를 표시하지 않는다. 숫자 배지와 음성은 플랫폼 허용 검증을 통과한 경우에만 사용한다. TTS 기본값은 OFF다.

500m 활성 시점은 입력이 임계값을 넘는 첫 위치다. 5km 임계값도 fixture의 실제 거리로 판정한다. 경계값 단위 테스트와 시연 영상의 대략적인 지도 거리를 혼동하지 않는다.

## 7. 필수 별도 검증

| ID | 조건 | 기대 결과 |
| --- | --- | --- |
| V-01 | 동의 false, 위치·차량 다수 입력 | 사용자 기록·진행·보상 증가 없음 |
| V-02 | 정확도 50 / 50 초과, NaN, 역순, 중복 | 50 허용, 나머지 해당 사유로 거절 |
| V-03 | 10초 수신 공백·200km/h 초과 도약 | 공백 segment 분리, 도약 거절, 거리 과다 누적 없음 |
| V-04 | 이동 19/20m, 경과 4/5초 | 20m 또는 5초부터 저장, 판정 스트림은 계속 처리 |
| V-05 | 지역 999/1000m, 체류 179/180초 | 각 임계값 미만 미획득, 도달 시 한 번 획득 |
| V-06 | 경계 접촉·hole·섬·재진입 | 잘못된 포함 없음, 한 진입 한 번, 재방문 큐 추가 없음 |
| V-07 | 진행 방향 반대, 차이 45/45 초과, 359↔1 | 반대·초과 거절, 45 허용, 원형 각도 정상 |
| V-08 | 같은 격자 평행 도로·서로 다른 과거 Trip 조각 | 격자만으로 승인하지 않음, 500m 조각 합산 없음 |
| V-09 | 고스트 499/500m, 불일치 199/200m | 시작·종료 임계값 정확 적용 |
| V-10 | 마지막 통과 29일 / 30일, 첫 통과만 오래됨 | 30일 이상만 허용, 최초 통과일로 오지급 없음 |
| V-11 | N·0속도·신호 결측·모순·오래된 P | 잘못된 상세 UI 허용 없음, 신호 fallback 규칙 적용 |
| V-12 | 주차 브레이크 체결·해제 | Trip 유지·기록 일시중지·재개, 동일 정차점 반복 저장 없음 |
| V-13 | 199m / 200m Trip 종료 | 199m 주행 종속 정보 제거, 200m 보존, 기존 보상 유지 |
| V-14 | 뱃지 저장 중 실패·동일 입력 재시도 | 유령 큐 없음, 상태 선행 진행 없음, 중복 획득·거리 없음 |
| V-15 | 카드 중 앱 종료·다시 주행 | 미확인 상태 유지, 제한 전환 때 즉시 카드 닫힘 |
| V-16 | 주행별 삭제·전체 삭제 | 보상 유지 규칙·참조 해제 정상, 전체 삭제 후 모든 기록 0 |
| V-17 | 180일 전 원본 정리 | 과거 지도·보상·고스트 결과 조회 가능, 원본 없는 정밀 매칭 없음 |
| V-18 | 차량 지표 전부 미지원 | null, 위치 기록·방문·고스트 위치 비교 정상 |
| V-19 | 연속 급제동·급조향 표본, 지표 결측 | 표본 수만큼 중복 카운트 없음, 결측을 0으로 표시하지 않음 |
| V-20 | 0회전·방향 불일치·HAZARD·다른 효율 단위 | 비율 null 또는 정의대로 계산, 잘못된 비교 없음 |
| V-21 | 새 격자 안 되돌아감·Trip 재실행 | 신규 거리 중복 누적 없음 |
| V-22 | 서울 자정 전후 동·서해 진입 | 같은 서울 날짜만 같은 날 조건 성립 |
| V-23 | TTS ON, 획득 다수·60초 경계·재시작 | 최소 간격 유지, 과거 큐 재낭독 없음 |

단위 테스트는 판정 자체를 검증하고, Room 통합 검증은 원자성·삭제·복구를 검증하며, 에뮬레이터 검증은 차량 신호·UI 정책·서비스 지속성을 검증한다. 한 종류의 통과로 다른 종류를 완료 처리하지 않는다.

## 8. Expected와 실행 기록

expected.json은 scenarioId별로 `expectedTripCount`, `expectedBadgeIds`, `expectedTitleIds`, `expectedGhostCount`, `expectedPendingRewardCount`, `checkpoints`를 가진다. checkpoints에는 입력 sequence, 기대 UiAccess·RecordingState·이벤트 종류를 기록한다. 거리는 `valueM`과 허용 오차 `toleranceM`를 함께 둔다. 시간·거리 임계값 테스트에서는 경계가 뒤바뀌는 큰 허용 오차를 주지 않는다.

실행자는 별도 검증 기록에 다음을 남긴다.

- 날짜, 수행자, 커밋 ID, catalog/fixture 버전·해시
- 실제 SDK 또는 재생 입력 여부, 에뮬레이터·SDK·기기 환경
- 실행 명령·시나리오 ID, 기대 결과와 실제 결과, 실패 시 최초 차이 sequence
- 위치 한 건의 지역+고스트 판정 시간 p50/p95/max, 후보 수·포인트 수·측정 환경
- 영상·캡처 위치, 미검증 항목

50ms 목표는 대상 환경과 후보 규모를 함께 기록한다. 지도 렌더링·파일 파싱·DB commit 시간은 별도로 측정해 판정 시간과 혼합하지 않는다. UI의 ms 표시만 보고 성능 요구를 충족했다고 판단하지 않는다.
