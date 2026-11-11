# 공통 데이터·인터페이스 계약

계약 버전: 1 · 소유자: 동현 · Android·Room 구현자: 채진

이 문서의 타입·이름·단위는 구현 기준이다. 표의 `?`는 Kotlin nullable, `List<T>`는 불변 목록이다. 아래 시그니처는 구현할 API 규격이며 앱 소스가 아니다.

## 1. 공통 표현

| 항목 | 계약 |
| --- | --- |
| ID | UUID 문자열. 지역·대학·칭호 코드는 카탈로그의 안정적인 문자열 ID |
| 시각 | `Long`, UTC epoch 밀리초, 접미사 `AtMs` 또는 `timeMs` |
| 경과 시간 | `Long`, 단조 증가 시계의 ms, `elapsedMs`; 체류·수신 공백·TTS 간격에 사용 |
| 날짜 조건 | `Asia/Seoul`의 날짜. '30일'은 30 × 24시간의 경과 시간 |
| 좌표 | WGS84 위도·경도 `Double`, `latitudeDeg`, `longitudeDeg` |
| 거리·속도 | `Double`, `distanceM`, `speedMps`. 적산거리만 명세대로 `odometerKm` |
| 방향 | 진북 0°, 시계 방향 [0, 360), `bearingDeg`; 미수신이면 null |
| 정확도 | `Double`, 0 이상 m, `accuracyM`; 미확인이면 유효 위치로 승격하지 않음 |
| 비율·효율 | 비율 0..1. 효율은 값과 `KM_PER_L` / `KM_PER_KWH` 단위를 함께 저장 |
| 유효 숫자 | 모든 실수는 finite. NaN·Infinity·범위 밖 좌표를 거절 |
| 격자 | 아래의 `GridKey` 규칙으로만 생성. 문자열을 기능별로 다르게 계산하지 않음 |

SDK 속도가 km/h이면 어댑터에서 `/ 3.6` 변환한다. 저장·계산에는 m/s를 쓰고 표시 직전에만 km/h로 바꾼다. 원문의 `startedAt`, `lat`, `distanceM` 등은 아래 이름에 대응하며 단위와 의미는 유지한다.

### GridKey

공통 `GridProjector`가 로컬 평면 투영으로 계산한다. 기준 위도 36°, 기준 경도 127°, R=6,371,008.8m를 사용한다. x=R×(경도−127°의 라디안)×cos(36°), y=R×(위도−36°의 라디안). 각 축의 `floor(값 / 100)`을 `column`, `row`로 삼는다. 직렬화는 `v1:{column}:{row}`다. 음수에 정수 나눗셈을 쓰지 않는다.

100m는 후보 조회용 근사 격자다. 도로 ID가 아니며 동일 격자의 다른 도로를 구분하지 못한다. 고스트 최종 판정은 미터 거리·방향·경로 연속성을 사용한다. 경계 누락 방지를 위해 현재 격자와 인접 8개를 조회한다. 정밀 거리는 구면 거리 공통 함수를 쓴다.

## 2. 입력·판정 공통 타입

| 타입 | 필드 |
| --- | --- |
| `GeoPoint` | `latitudeDeg: Double`, `longitudeDeg: Double` |
| `LocationSample` | `timeMs: Long`, `elapsedMs: Long`, `point: GeoPoint`, `speedMps: Double?`, `bearingDeg: Double?`, `accuracyM: Double?` |
| `ValidatedLocation` | LocationSample의 필드. 단 accuracyM은 `Double`, 위치 검증·도약 검증 통과 후에만 생성 |
| `VehicleSample` | `timeMs: Long`, `elapsedMs: Long`, `gear: Gear?`, `parkingBrakeEngaged: Boolean?`, `speedMps: Double?`, `odometerKm: Double?`, `steeringAngleDeg: Double?`, `brakePedalPressed: Boolean?`, `turnSignal: TurnSignal?`, `fuelUsedL: Double?`, `energyUsedKwh: Double?` |
| `Gear` | `P`, `R`, `N`, `D`, `OTHER` |
| `TurnSignal` | `OFF`, `LEFT`, `RIGHT`, `HAZARD` |
| `Observation<T>` | `value: T`, `observedAtElapsedMs: Long`; 차량 필드는 관측 시각을 함께 유지 |
| `RecordingState` | `IDLE`, `RECORDING`, `PAUSED`, `ERROR` |
| `UiAccess` | `RESTRICTED`, `PARKED`, `UNKNOWN` |
| `LocationRejection` | `MISSING_ACCURACY`, `INVALID_VALUE`, `LOW_ACCURACY`, `OUT_OF_ORDER`, `JUMP` |
| `LocationValidation` | `Accepted(location: ValidatedLocation)` 또는 `Rejected(reason: LocationRejection)` |

VehicleSample은 같은 시각의 관측 묶음이다. 서로 다른 시각에 온 신호를 묶을 때는 내부 Snapshot의 필드별 Observation을 보존한다. 샘플 시각을 모든 필드의 최신 시각으로 덮어쓰지 않는다. 미지원 신호와 오래된 신호를 0 또는 OFF로 만들지 않는다.

시계·ID는 `AppClock`과 `IdGenerator`로 주입한다. SDK에서 단조 시각을 제공하지 않으면 수신 시점 단조 시각을 채진의 어댑터에서 붙인다. DB 복구 후 타이머를 벽시계 차이로 이어 붙이지 않고 새 관측부터 재시작한다.

## 3. 영구 모델

| 모델 | 필드 |
| --- | --- |
| `Trip` | `id: String`, `startedAtMs: Long`, `endedAtMs: Long?`, `distanceM: Double`, `odometerStartKm: Double?`, `odometerEndKm: Double?`, `simplifiedPath: List<PathSegment>` |
| `PathSegment` | `segmentId: String`, `points: List<GeoPoint>`; 수신 공백 전후는 다른 segment |
| `TrackPoint` | `id: String`, `tripId: String`, `timeMs: Long`, `point: GeoPoint`, `speedMps: Double?`, `bearingDeg: Double?`, `accuracyM: Double`, `gridKey: String`, `pathSegmentId: String`, `sequence: Long` |
| `ExploredRoad` | `gridKey: String`, `firstTripId: String?`, `firstVisitedAtMs: Long`, `lastVisitedAtMs: Long`, `visitCount: Int`, `firstExploredDistanceM: Double` |
| `RegionBadge` | `badgeId: String`, `regionCode: String`, `type: BadgeType`, `firstTripId: String?`, `acquiredAtMs: Long`, `visitCount: Int` |
| `BadgeType` | `REGION`, `UNIVERSITY` |
| `AchievementTitle` | `titleId: String`, `conditionText: String`, `acquiredAtMs: Long`, `tripId: String?` |
| `DrivingMetrics` | `id: String`, `tripId: String`, `segmentIndex: Int`, `startPointId: String?`, `pathSegmentId: String`, `startDistanceM: Double`, `endDistanceM: Double`, `harshBrakes: Int?`, `harshSteers: Int?`, `turnSignalRate: Double?`, `avgEfficiency: Efficiency?`, `turnCount: Int?`, `signaledTurnCount: Int?` |
| `Efficiency` | `value: Double`, `unit: EfficiencyUnit` |
| `GhostResult` | `id: String`, `tripId: String`, `pastTripId: String?`, `matchedDistanceM: Double`, `firstPassedAtMs: Long`, `previousLastPassedAtMs: Long`, `newRoadM: Double`, `metricsDiff: MetricsDiff?`, `currentPath: List<PathSegment>`, `pastPath: List<PathSegment>` |
| `MetricsDiff` | `harshBrakesDelta: Int?`, `harshSteersDelta: Int?`, `turnSignalRateDelta: Double?`, `efficiencyDelta: Efficiency?` |
| `TripRegionVisit` | `id: String`, `tripId: String`, `regionCode: String`, `entrySequence: Long`, `qualifiedAtMs: Long` |
| `PendingReward` | `id: String`, `deduplicationKey: String`, `tripId: String?`, `kind: RewardKind`, `subjectId: String`, `occurredAtMs: Long`, `acknowledgedAtMs: Long?` |
| `RewardKind` | `BADGE`, `TITLE`, `GHOST_RESULT` |

타입 해석을 통일하기 위해 다음 규칙도 적용한다.

- `badgeId`는 `REGION:{regionCode}` 또는 `UNIVERSITY:{universityId}`다. 대학과 행정구역 코드 충돌을 막는다. REGION의 regionCode는 내장 카탈로그와 정확히 일치한다.
- 시간 없는 `GeoPoint`는 표시용이다. 고스트 알고리즘은 시각·방향·순서가 있는 TrackPoint를 사용한다.
- `TripRegionVisit`는 기존 뱃지 재방문도 주행 상세에 표시하기 위한 보조 모델이다. 뱃지만 조회해 해당 Trip의 모든 방문 지역을 추측하지 않는다.
- `PendingReward`는 F-10의 재시작 가능한 큐다. `deduplicationKey`는 BADGE의 badgeId, TITLE의 titleId, GHOST_RESULT의 결과 id에 kind를 붙인 값이다.
- `previousLastPassedAtMs`는 현재 주행을 반영하기 전의 마지막 통과 시각이다. F-07의 30일 조건은 최초 통과일로 판정하지 않는다.
- `firstExploredDistanceM`는 F-07의 영구 누적 신규 격자 거리 집계다. 격자 수 × 100m로 대체하지 않는다. 도로 단위 정밀 탐험률을 의미하지 않는다.
- 단순화 경로와 고스트 결과의 표시용 경로는 원본 삭제 후에도 남는다. 원본 TrackPoint가 없으면 해당 시기 고스트 정밀 매칭은 제공할 수 없다.
- `MetricsDiff`는 현재−과거다. 서로 다른 단위·미지원·불충분한 관측 지표는 null이다. 비율은 횟수를 합친 뒤 재계산한다.

## 4. 엔진 호출 계약

경로·고스트·지표 엔진 인스턴스는 한 활성 Trip에 속한다. 방문 엔진은 정차 체류 판정을 위해 Trip 종료 후에도 현재 진입 후보를 유지할 수 있다. 다음 Trip 시작 시 연결할 tripId를 갱신하되 이미 확정한 진입을 다시 지급하지 않는다. 시작 시 영구 상태 snapshot을 받아 복구하고, 정상 처리에서는 입력 순서를 유지한다. 상태를 바꾸기 전에 다음 상태와 저장 변경 묶음을 계산하고, 저장 성공 후에만 메모리 상태를 확정한다.

| 컴포넌트 | 호출 API | 반환·책임 |
| --- | --- | --- |
| `LocationValidator` | `validate(sample: LocationSample): LocationValidation` | 정확도·시각·좌표·도약 검사. 이전 승인 위치 기준 |
| `TripStateMachine` | `process(input: TripInput): TripTransition` | 차량·위치·타이머 입력으로 시작·일시중지·재개·종료·변화 없음 |
| `TrackRecorder` | `process(location: ValidatedLocation): TrackUpdate` | 저장할 포인트 목록, 연결 가능한 이동 거리, 새 path segment 여부 |
| `RegionVisitDetector` | `process(input: RegionInput): List<VisitQualified>` | 진입·지역 내 거리·체류·이탈을 처리해 방문 확정만 반환 |
| `GhostDetector` | `process(location: ValidatedLocation, candidates: List<HistoricalTrack>): List<GhostEvent>` | 시작·진행·종료. 한 입력에서 종료와 시작이 함께 가능해 목록 반환 |
| `TitleEvaluator` | `evaluate(fact: AchievementFact, progress: AchievementProgress): List<TitleQualified>` | 방문·종료·고스트 완료·지정 지점 진입 사실을 판정 |
| `MetricsCollector` | `process(sample: VehicleSample, position: TrackPosition?): List<DrivingMetrics>` | 위치 연결 가능한 구간 지표 반환. 종료 때 미완료 구간 flush |
| `ProcessDriveInputUseCase` | `suspend process(input: DriveInput): ProcessingOutcome` | 순서 조정, 포트 조회, 변경 묶음 저장, 상태 확정 |

아래 타입을 같은 계약 PR에서 정의한다. 각 엔진 담당자가 별도 버전을 만들지 않는다.

| 보조 타입 | 필드·의미 |
| --- | --- |
| `DriveInput` | `Location(sample)`, `Vehicle(sample)`, `Tick(elapsedMs, timeMs)`, `ConsentChanged(granted)` 중 하나 |
| `TripInput` | DriveInput 중 검증된 위치·차량·타이머. 동의는 UseCase 진입에서 차단 |
| `TripTransition` | `Started(trip)`, `Paused(atMs)`, `Resumed(atMs)`, `Ended(atMs)`, `Unchanged` |
| `TrackUpdate` | `points: List<TrackPoint>`, `connectedDistanceM: Double`, `newSegment: Boolean` |
| `RegionInput` | `Location(location, connectedDistanceM)`, `Tick(elapsedMs, timeMs)`, `ContinuityLost`, `TripEnded` |
| `VisitQualified` | `regionCode`, `tripId`, `entrySequence`, `qualifiedAtMs` |
| `HistoricalTrack` | `tripId`, `points: List<TrackPoint>`; 순서와 segment 경계를 유지한 과거 후보 |
| `GhostEvent` | `Started(pastTripId, atMs)`, `Updated(matchedDistanceM)`, `Finished(result: GhostResult)` |
| `AchievementFact` | `BadgeGranted(badgeId, tripId, atMs)`, `TripFinished(tripId, atMs, newRoadM)`, `GhostFinished(result)`, `CoastEntered(coastId, tripId, atMs)` |
| `AchievementProgress` | `regionBadgeCount: Int`, `newRoadDistanceM: Double`, `coastVisitsByDate`, `acquiredTitleIds: Set<String>` |
| `TitleQualified` | `titleId`, `tripId`, `acquiredAtMs`, `conditionText` |
| `TrackPosition` | `tripId`, `pathSegmentId`, `pointId`, `distanceFromTripStartM` |
| `ProcessingOutcome` | `Applied`, `Rejected(reason)`, `Failed(error)`; Failed는 저장·데이터 읽기 장애 |

`coastVisitsByDate`는 서울 날짜 문자열 → 동/서해 coast ID 집합이다. `TripFinished.newRoadM`은 해당 Trip의 신규 거리, AchievementProgress의 값은 과거 확정 누적이다. 같은 사실을 재처리해도 중복 반영되지 않아야 한다.

`VisitQualified`·`TitleQualified`는 획득 확정 사실이 아니라 저장 제안이다. DB 유니크 키로 신규 획득이 확인된 후에만 Reward·AchievementFact를 확정한다.

## 5. 외부 포트

| 포트 | API·입출력 | 구현 담당 |
| --- | --- | --- |
| `AppClock` | `nowMs(): Long`, `elapsedMs(): Long`; 실제·재생 시계 모두 같은 형식 | 계약 동현, 플랫폼 구현 채진 |
| `IdGenerator` | `newId(): String`; 실제 UUID, 테스트 고정 순서 ID | 계약 동현, 플랫폼 구현 채진 |
| `LocationSource` | `samples(): Flow<LocationSample>`; 실제·재생 구현 모두 같은 형식 | 채진 |
| `VehicleSource` | `samples(): Flow<VehicleSample>`; 미지원 신호는 null | 채진 |
| `RegionCatalog` | `findCandidates(point): List<RegionDefinition>`; 경계 상자 인덱스로 후보 반환 | 계약 동현, 로더 채진, 데이터 현민 |
| `HistoryRepository` | `suspend findNearby(gridKeys: Set<String>, excludedTripId: String): List<HistoricalTrack>` | 채진 |
| `DriveStore` | `suspend loadSnapshot(): DriveSnapshot`, `suspend commit(changeSet: DriveChangeSet): CommitOutcome` | 채진 |
| `ArchiveRepository` | `observeTrips(): Flow<List<Trip>>`, `suspend getTripDetail(tripId): TripDetail?`, `suspend deleteTrip(tripId)`, `suspend deleteAll()` | 채진 |
| `RewardRepository` | `observePending(): Flow<List<PendingReward>>`, `suspend acknowledge(id, atMs)` | 채진 |
| `SettingsRepository` | `settings: StateFlow<UserSettings>`, `suspend setRecordingConsent(granted: Boolean)`, `suspend setTtsEnabled(enabled: Boolean)` | 채진 |

설정 계약 구현(2026-11-11): `UserSettings(recordingConsentGranted: Boolean = false, ttsEnabled: Boolean = false)`.
두 필드는 독립적이다. 동의 철회는 수집·발화를 중단하지만 음성 선호와 기존 기록은 보존한다.
기록 전체 삭제도 두 설정을 보존한다. 저장 성공 후 관측 상태를 갱신하며 저장 실패를 호출자에게 전달한다.
현재 앱의 구현은 SharedPreferences이며, Room 기반 사용자 주행 데이터와 분리한다.
이 설정 계약 구현이 다른 포트·엔진의 구현 완료를 뜻하지 않는다.

API 인자 타입은 point=`GeoPoint`, gridKeys=`Set<String>`, tripId/id=`String`, atMs=`Long`으로 통일한다. `RegionDefinition`은 데이터 규격의 code·name·provinceCode·representativePoint·geometry·bbox를 가진다. `TripDetail`은 Trip, 방문 지역, 획득 보상, 지표, 고스트 결과를 조합한 읽기 모델이다.

`DriveSnapshot`은 활성 Trip·마지막 승인 위치·판정 상태·방문 진입 번호·획득 ID·칭호 진행·처리 cursor를 담는다. 순수 Kotlin 모델로 두고 Room Entity와 분리한다. `DriveChangeSet`은 포인트·격자·방문·보상·칭호·고스트·지표의 변경과 다음 snapshot, 입력 식별 키를 한 묶음으로 담는다. `CommitOutcome`은 `Committed` 또는 `AlreadyApplied`다. DB 오류는 예외로 상위에 전달한다.

앱 입력 큐가 붙이는 입력 키는 `sessionId + sequence`다. 재시도에서 키를 재발급하지 않는다. 세션 변경 후에도 획득 유니크 키·지역 진입 키·Trip cursor가 중복 지급·중복 거리 누적을 막는다. 스냅샷의 오래된 센서값은 재시작 후 판정 입력으로 재사용하지 않는다.

## 6. 저장·삭제·전달 보장

1. 수집 서비스가 동의·권한을 확인한 뒤 입력 큐에 넣는다. 동의 전에는 임시 경로 파일·seed 이외의 사용자 기록도 만들지 않는다.
2. UseCase는 위치를 한 번 검증하고 F-03·05·12가 동일한 승인 스트림을 사용하게 한다. TrackPoint 저장 간격은 판정 입력 간격과 별개다.
3. 과거 후보를 현재 주행 제외 조건으로 조회한다. 기존 lastVisitedAt 등의 판정용 시각은 현재 위치로 갱신하기 전에 snapshot으로 읽는다.
4. 포인트·방문·신규 보상·칭호·큐·진행 snapshot은 관련 변경을 하나의 Room 트랜잭션으로 저장한다. DB commit 실패 시 다음 입력을 진행시키지 않고 기록 오류 상태로 전환한다.
5. UI는 commit된 DB를 관측한다. 카드 실제 확인 시 acknowledge한다. 화면 전환·프로세스 종료·다시 주행으로 바뀐 경우 미확인 큐를 유지한다.

Room 제약은 badgeId·titleId·deduplicationKey 유니크, `(tripId, regionCode, entrySequence)` 유니크, TrackPoint `(tripId, sequence)` 유니크를 둔다. History 조회에는 gridKey와 tripId, 주행 목록에는 startedAtMs, 보상 큐에는 acknowledgedAtMs 인덱스를 둔다.

Trip 자식 포인트·방문·지표·그 Trip에서 생성한 고스트 결과는 해당 Trip 삭제 시 삭제한다. 보존할 뱃지·칭호·ExploredRoad와 다른 Trip의 고스트 결과에서 삭제된 Trip을 참조하는 필드는 nullable 참조로 끊는다. 획득 시각·조건·고스트 표시 경로는 보존하고 UI는 '관련 주행 삭제됨'으로 표시한다. `startPointId`는 원본 정리 때 null로 바꾸되 거리 범위·segment 정보는 유지한다.

전체 삭제는 수집을 멈추고 큐·DB·경로 캐시·진행 snapshot을 함께 제거한다. 동의·설정은 유지하되 자동 재수집은 하지 않으며, 이후 명시적 수집 재개에서 새 Trip을 시작한다. 원본 180일 정리는 시간 기준 포인트만 정리하고, 표시 경로·보상·누적 탐험 정보는 남긴다.
