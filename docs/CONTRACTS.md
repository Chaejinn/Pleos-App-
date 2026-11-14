# 공통 데이터·인터페이스 계약

계약 버전: 7 · 소유자: 동현 · Android·Room 구현자: 채진

이 문서의 타입·이름·단위는 구현 기준이다. 표의 `?`는 Kotlin nullable, `List<T>`는 불변 목록이다. 아래 시그니처는 구현할 API 규격이며 앱 소스가 아니다.

2026-11-13 구현 범위: F-03 위치·경로 모델, `LocationValidator`, `TrackRecorder`,
`RecordTrackUseCase`, `IdGenerator`, `TrackRecordingStore`가 신규 `domain` 패키지에 구현되어 있다.
F-03의 실제 호출·복구·저장 계약은 7절을 따른다.
이슈 #7에서 F-05 지역 경계·방문·뱃지 제안과 저장 포트를 구현했다. F-05의 실제 API는 8절을 따르며,
이슈 #9에서 F-12 고스트 구간 판정·이력 조회·저장 포트를 구현했다. 실제 API는 9절을 따른다.
이슈 #11에서 F-13 위치 기반 비교 결과·신규 거리 집계·보상 제안 포트를 구현했다. 실제 API는 10절을 따른다.
차량 상태기계·칭호·차량 특성 계산·범용 통합 저장은 후속 구현 규격이다.
기존 앱은 `legacy` 엔진을 계속 사용하며, 이 계약 추가만으로 새 엔진이 앱에 연결되지는 않는다.

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
| `GhostResult` | `id: String`, `tripId: String`, `pastTripId: String?`, `matchedDistanceM: Double`, `firstPassedAtMs: Long`, `previousLastPassedAtMs: Long`, `newRoadM: Double`, `metricsDiff: MetricsDiff?`, `currentPath: List<PathSegment>`, `pastPath: List<PathSegment>`, `passages: List<GhostPassage>`, `finalized: Boolean = false`; 10절 참조 |
| `MetricsDiff` | `harshBrakesDelta: Int?`, `harshSteersDelta: Int?`, `turnSignalRateDelta: Double?`, `efficiencyDelta: Efficiency?` |
| `TripRegionVisit` | `id: String`, `tripId: String`, `regionCode: String`, `entrySequence: Long`, `qualifiedAtMs: Long` |
| `PendingReward` | `id: String`, `deduplicationKey: String`, `tripId: String?`, `kind: RewardKind`, `subjectId: String`, `occurredAtMs: Long`, `acknowledgedAtMs: Long?` |
| `RewardKind` | `BADGE`, `TITLE`, `GHOST_RESULT` |

타입 해석을 통일하기 위해 다음 규칙도 적용한다.

- `badgeId`는 `REGION:{regionCode}` 또는 `UNIVERSITY:{universityId}`다. 대학과 행정구역 코드 충돌을 막는다. REGION의 regionCode는 내장 카탈로그와 정확히 일치한다.
- 시간 없는 `GeoPoint`는 표시용이다. 고스트 알고리즘은 시각·방향·순서가 있는 TrackPoint를 사용한다.
- `TripRegionVisit`는 기존 뱃지 재방문도 주행 상세에 표시하기 위한 보조 모델이다. 뱃지만 조회해 해당 Trip의 모든 방문 지역을 추측하지 않는다.
- `PendingReward`는 F-10의 재시작 가능한 큐다. `deduplicationKey`는 BADGE의 badgeId, TITLE의 titleId, GHOST_RESULT의 결과 id에 kind를 붙인 값이다.
- `previousLastPassedAtMs`는 현재 주행을 반영하기 전 마지막 통과의 표시용 요약이다. F-07의 30일 조건은 10절의 동일 위치 시각 쌍으로 판정한다.
- `firstExploredDistanceM`는 F-07의 영구 누적 신규 격자 거리 집계다. 격자 수 × 100m로 대체하지 않는다. 도로 단위 정밀 탐험률을 의미하지 않는다.
- 단순화 경로와 고스트 결과의 표시용 경로는 원본 삭제 후에도 남는다. 원본 TrackPoint가 없으면 해당 시기 고스트 정밀 매칭은 제공할 수 없다.
- `MetricsDiff`는 현재−과거다. 서로 다른 단위·미지원·불충분한 관측 지표는 null이다. 비율은 횟수를 합친 뒤 재계산한다.

## 4. 엔진 호출 계약

경로·고스트·지표 엔진 인스턴스는 한 활성 Trip에 속한다. 방문 엔진은 정차 체류 판정을 위해 Trip 종료 후에도 현재 진입 후보를 유지할 수 있다. 다음 Trip 시작 시 연결할 tripId를 갱신하되 이미 확정한 진입을 다시 지급하지 않는다. 시작 시 영구 상태 snapshot을 받아 복구하고, 정상 처리에서는 입력 순서를 유지한다. 상태를 바꾸기 전에 다음 상태와 저장 변경 묶음을 계산하고, 저장 성공 후에만 메모리 상태를 확정한다.

| 컴포넌트 | 호출 API | 반환·책임 |
| --- | --- | --- |
| `LocationValidator` | `validate(sample: LocationSample, previous: ValidatedLocation?): LocationValidation` | 구현됨. 상태를 내장하지 않고 전달받은 마지막 승인 위치 기준으로 검사 |
| `TripStateMachine` | `process(input: TripInput): TripTransition` | 차량·위치·타이머 입력으로 시작·일시중지·재개·종료·변화 없음 |
| `TrackRecorder` | `process(state, validation, inputSequence, knownRoads): TrackUpdate` | 구현됨. 변경 제안만 반환하며 실제 타입·시작·종료·복구는 7절 참조 |
| `RecordTrackUseCase` | `suspend start/process/resume/finish` | 구현됨. F-03 전용 입출력 조합, 저장 성공 후 반환 |
| `RegionVisitDetector` | `process(state, input, inputSequence): RegionVisitUpdate` | 구현됨. 상태 변경·방문 제안만 계산하며 저장은 하지 않음. 8절 참조 |
| `ProcessRegionVisitUseCase` | `suspend start/process/resume` | 구현됨. F-05 저장 포트와 방문·뱃지·보상 제안 조합 |
| `GhostDetector` | `process(state, input, sequence, history): GhostUpdate` | 구현됨. snapshot·시작/진행/종료 제안. 9절 참조 |
| `ProcessGhostInputUseCase` | `suspend start/process/resume` | 구현됨. 이력 조회와 F-12 저장 경계 조합 |
| `GhostResultBuilder` | `build(segment, history): GhostResult` | 구현됨. F-12 대응 구간의 날짜·표시 경로 계산 |
| `GhostResultAssembler` | `process(state, input, history): GhostComparisonUpdate` | 구현됨. F-03 누적 신규 거리와 F-12 실제 경계 조합 |
| `ProcessGhostComparisonUseCase` | `suspend start/process/resume` | 구현됨. F-13 이력 조회·결과·보상 저장 제안 조합. 10절 참조 |
| `TitleEvaluator` | `evaluate(fact: AchievementFact, progress: AchievementProgress): List<TitleQualified>` | 방문·종료·고스트 완료·지정 지점 진입 사실을 판정 |
| `MetricsCollector` | `process(sample: VehicleSample, position: TrackPosition?): List<DrivingMetrics>` | 위치 연결 가능한 구간 지표 반환. 종료 때 미완료 구간 flush |
| `ProcessDriveInputUseCase` | `suspend process(input: DriveInput): ProcessingOutcome` | 순서 조정, 포트 조회, 변경 묶음 저장, 상태 확정 |

아래 타입을 같은 계약 PR에서 정의한다. 각 엔진 담당자가 별도 버전을 만들지 않는다.

| 보조 타입 | 필드·의미 |
| --- | --- |
| `DriveInput` | `Location(sample)`, `Vehicle(sample)`, `Tick(elapsedMs, timeMs)`, `ConsentChanged(granted)` 중 하나 |
| `TripInput` | DriveInput 중 검증된 위치·차량·타이머. 동의는 UseCase 진입에서 차단 |
| `TripTransition` | `Started(trip)`, `Paused(atMs)`, `Resumed(atMs)`, `Ended(atMs)`, `Unchanged` |
| `TrackUpdate` | `snapshot: RecordingSnapshot`, `points: List<TrackPoint>`, `roads: Map<String, ExploredRoad>`, `connectedDistanceM: Double`, `newSegment: Boolean`, `validation: LocationValidation?`; 7절 참조 |
| `RegionInput` | `Location(location, connectedDistanceM, newSegment)`, `Tick(elapsedMs, timeMs)`, `ContinuityLost`, `TripStarted(tripId)`, `TripEnded`, `TripDetached(tripId)`; 8절 참조 |
| `VisitQualified` | `regionCode: String`, `tripId: String?`, `entrySequence: Long`, `qualifiedAtMs: Long`; Trip 없는 정차 방문도 표현 |
| `HistoricalTrack` | `tripId: String`, `points: List<TrackPoint>`; F-03이 확정한 sequence·segment 경계를 유지한 과거 후보 |
| `GhostEvent` | `Started(id, pastTripId, atMs)`, `Updated(id, matchedDistanceM)`, `Finished(segment: GhostSegment)`; F-13 GhostResult는 후속 조합 |
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
| `RegionCatalog` | `findCandidates(point: GeoPoint)` 및 `findCandidates(bounds: GeoBounds)`: `List<RegionDefinition>`; 점·선분 경계 상자 후보 | 구현 계약 동현, 파일 로더 채진, 데이터 현민 |
| `HistoryRepository` | `suspend findNearby(gridKeys: Set<String>, excludedTripId: String): List<HistoricalTrack>`, `suspend findForward(cursor: GhostCursor, maxDistanceM: Double): HistoricalTrack`; 9절 조회 계약 | 채진 |
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

## 7. F-03 구현 API와 앱 연결 계약

이 절은 이슈 #3에서 추가한 실제 API다. 기존 v1의 내부 상태형 기록 API를 명시적인 snapshot 입력·변경 제안 방식으로 구체화했다.
기존 `legacy` API와 `UserSettings`·`SettingsRepository`는 변경하지 않았다.
`TrackRecordingStore`는 범용 `DriveStore` 구현 전 F-03 저장 경계를 정의한 포트다. Room 구현은 채진 담당이며 현재 저장소에 구현되어 있지 않다.
후속 `ProcessDriveInputUseCase` 통합 시 `TrackRecorder`의 변경 제안을 방문·보상 변경과 같은 트랜잭션에 조합한다.

### 호출 순서

```kotlin
val recorder = TrackRecorder(idGenerator)
val recording = RecordTrackUseCase(trackRecordingStore, recorder)

recording.start(tripId, startedAtMs, sessionId)
recording.process(tripId, sessionId, sequence, locationSample)
recording.finish(tripId, endedAtMs)
```

세 메서드는 `suspend`이며 `TrackUpdate`를 반환한다. 프로세스 재시작으로 단조 시계 기준이 바뀌면 위치 처리 전에
`recording.resume(tripId, newSessionId)`를 호출한다. 이전 segment의 미저장 승인 끝점을 먼저 저장하고,
새 세션의 첫 유효점부터 별도 segment를 시작한다. 복구 전의 마지막 센서값으로 타이머·거리를 이어 계산하지 않는다.

- `tripId`, `sessionId`는 비어 있지 않은 UUID 문자열을 호출자가 만든다. `sequence`는 세션 안에서 0 이상으로 증가한다.
  입력 큐가 재시도할 때 sessionId·sequence·샘플을 그대로 유지한다. SDK 위치의 elapsedMs와 큐 sequence는 다른 값이다.
- 같은 세션에서 마지막 처리 sequence 이하의 입력은 저장 없이 반환한다. `validation == null`, 빈 points/roads는 재처리·수명주기 응답일 수 있으므로 승인 위치로 재전달하지 않는다.
- 다른 sessionId의 위치는 명시적으로 resume하기 전 거절한다. 같은 sessionId로 resume를 재시도하면 아무 변경도 하지 않는다.
- 하나의 활성 Trip에 대해 시작·위치·복구·종료를 한 큐로 직렬 호출한다. 처리 도중 삭제·다른 Trip 저장을 병행하지 않는다.
- 저장·조회 예외와 코루틴 취소는 그대로 호출자에게 전달한다. 실패하면 다음 입력을 진행하지 않고 동일 입력부터 재시도한다.
  UseCase는 별도 가변 판정 상태를 보관하지 않고 매번 commit된 snapshot을 읽는다.
- `start`는 같은 tripId·시작 시각·sessionId로 재시도할 수 있다. `finish`도 종료된 snapshot에 대해 재시도할 수 있다.
  종료 뒤 새 위치 처리는 `IllegalStateException`, 알 수 없는 Trip·잘못된 호출 인자는 `IllegalArgumentException`이다.
- finish의 종료 시각은 요청 시각, 시작 시각, 마지막 승인 위치의 벽시각 중 최댓값이다. 벽시계 역전으로 음수 주행 기간을 만들지 않는다.
  resume 직후 새 승인 위치가 없으면 sequence 오름차순 원본의 마지막 점 시각을 사용한다. resume가 미저장 끝점을 먼저 commit하므로
  복구 전 마지막 승인 시각도 보존된다. 벽시계 역전이 가능하므로 원본 전체의 최대 timeMs로 대체하지 않는다.
  종료점의 시각은 마지막 승인 위치 원본 시각이며 종료 요청 시각으로 덮어쓰지 않는다.

### snapshot과 변경 제안

`RecordingSnapshot` 필드:

| 필드 | 의미 |
| --- | --- |
| `trip`, `status` | 주행 요약과 `ACTIVE` / `FINISHED` / `DISCARDED` |
| `sessionId`, `lastInputSequence` | 현재 실행 세션과 마지막 처리 입력. 초기 sequence는 -1 |
| `revision` | 신규 snapshot 0, 상태를 바꾸는 commit마다 1 증가 |
| `previous` | 마지막 승인 `ValidatedLocation?`. 거절 위치로 교체하지 않음 |
| `lastStored`, `lastStoredElapsedMs` | 마지막 원본 저장점과 그 단조 시각. 20m/5초 판정 기준 |
| `segmentId`, `nextPointSequence`, `continuityBroken` | segment 연결 상태와 Trip 전체 원본 순번 |
| `pendingRoads` | 현재 Trip의 임시 격자 변경. 완료 전 전역 탐험 기록으로 노출하지 않음 |
| `newGridKeys` | 이 Trip 이전에는 없었던 격자 키 |
| `creditedSections` | 격자 키별 `ExploredSection(gridKey, from, to)` 목록. 현재 Trip 내 신규 거리 중복 방지 상태 |

`ValidatedLocation`은 외부에서 임의 생성·copy할 수 없다. Room 매퍼는 저장 필드를 `LocationSample`로 읽고
`LocationValidator.validate(sample, null)`의 Accepted 결과로 복원한다. 불량 저장 데이터를 승인 상태로 만들지 않는다.
복구한 단조 시각은 새 세션 입력과 비교하지 않으며 반드시 위 resume 순서를 따른다.

`TrackUpdate.points`는 이번 트랜잭션에서 추가할 원본 목록, `roads`는 완료 시 전역에 반영할 격자 목록이다.
`snapshot`은 다음 상태 제안이며 commit 전 UI나 다른 엔진의 확정 상태로 게시하지 않는다.
`connectedDistanceM`는 이번 승인 위치로 실제 연결한 거리다. `newSegment`가 true이면 0이며,
`validation`은 위치 처리에서 Accepted 또는 Rejected, 수명주기·중복 응답에서는 null이다.
필터링으로 저장하지 않은 승인 위치도 Accepted로 반환하므로 후속 F-05·F-12 입력에 사용할 수 있다.

`TrackRecorder`는 `start(tripId, startedAtMs, sessionId)`, `requiredGridKeys(state, location)`,
`process(state, validation, inputSequence, knownRoads)`, `resume(state, sessionId)`, `finish(state, atMs, storedPoints)`를 제공한다.
UseCase가 검증을 한 번 수행하고 필요한 과거 격자를 읽은 뒤 recorder에 전달한다. recorder 자체는 I/O와 commit을 하지 않는다.
`IdGenerator.newId()`로 segment ID와 point ID를 발급하며, 거리가 아닌 `TrackPoint.sequence`로 원본 순서를 정렬한다.

### TrackRecordingStore 구현 조건

```kotlin
interface TrackRecordingStore {
    suspend fun load(tripId: String): RecordingSnapshot?
    suspend fun loadPoints(tripId: String): List<TrackPoint>
    suspend fun findRoads(gridKeys: Set<String>): Map<String, ExploredRoad>
    suspend fun commit(expectedRevision: Long?, update: TrackUpdate)
}
```

- `load`는 종료·폐기 상태까지 마지막 commit을 반환한다. `loadPoints`는 해당 Trip의 원본을 sequence 오름차순으로 빠짐없이 반환한다.
  `findRoads`는 요청 키 중 전역에 확정된 격자만 반환하며, 미존재와 조회 실패를 구분한다.
- commit은 Trip ID별 현재 revision을 비교한다. expectedRevision null은 최초 생성에만 허용한다.
  기존 값 불일치·동시 생성은 예외로 실패시키며 다른 호출자의 snapshot을 덮어쓰지 않는다.
- 포인트 `(tripId, sequence)` 유니크, snapshot 갱신, 종료 Trip, 확정 roads를 한 트랜잭션으로 저장한다.
  실패 시 일부 포인트만 남거나 메모리 snapshot만 진행해서는 안 된다.
- ACTIVE의 pendingRoads는 snapshot 안에서만 보존한다. FINISHED일 때만 roads를 전역에 반영한다.
  한 Trip의 `(tripId, gridKey)` 방문은 1회다. 종료 재시도에서 visitCount를 다시 증가시키지 않는다.
- DISCARDED면 해당 Trip의 원본과 주행 본문을 제거하고 아카이브에서 제외한다. 전역 roads에는 반영하지 않는다.
  재시도 중복 방지를 위한 종료 cursor와 좌표 없는 최소 tombstone은 유지한다. 이미 확정한 보상 참조 해제는 후속 통합 트랜잭션 책임이다.
- 전체 삭제 시 snapshot·tombstone·임시 원본도 함께 삭제한다. 활성 기록과 격자 갱신은 단일 입력 큐가 소유한다.

### 경로·탐험 계산의 범위

거리·격자는 저장 간격 이전의 모든 승인 연결 위치로 계산한다. 거절 입력도 미저장 승인 끝점을 flush하고 연결을 끊는다.
20m 또는 5초 저장과 별개로 최초·공백 전후·종료점을 보존한다. 종료 때 원본을 유지하면서 segment별 5m 단순화 경로를 만든다.

격자 경계에서 연결 선분을 분할해 구면 거리의 해당 비율을 배분한다. 첫 탐험 격자의 같은 선분은 방향·샘플 분할이 달라도
고정 투영 평면의 공선 겹침 구간을 합쳐 신규 길이를 중복 누적하지 않는다. 공선 오차 허용은 부동소수점 계산용 0.000001m다.
다른 선분이나 GPS 잡음으로 달라진 선을 정밀 도로 수준으로 합치지는 않는다. 원본 1초 위치의 직선 근사와 약 100m 격자라는 한계가 있다.
날짜변경선 통과는 경도 ±180°에서 분리해 전 지구를 가로지르는 격자를 만들지 않는다.

## 8. F-05 지역 방문 API와 앱 연결 계약

이슈 #7의 범위는 순수 Kotlin 경계 판정·방문 엔진, 뱃지·방문·보상 저장 제안과 테스트 저장 포트다.
실제 행정구역 파일·TourAPI·지도·Room·서비스·보상 UI 연결은 포함하지 않는다. 기존 F-03·legacy·설정 API는 유지한다.

### 지역 데이터와 후보 조회

| 타입 | 필드·의미 |
| --- | --- |
| `GeoBounds` | `westDeg`, `southDeg`, `eastDeg`, `northDeg`: Double. WGS84 축 정렬 경계 상자 |
| `RegionPolygon` | `outer: List<GeoPoint>`, `holes: List<List<GeoPoint>>`. 각 ring은 최소 4점이며 첫 점과 끝 점이 같음 |
| `RegionGeometry` | `polygons: List<RegionPolygon>`. 단일 Polygon과 MultiPolygon을 같은 타입으로 표현 |
| `RegionDefinition` | `code`, `name`, `provinceCode`: String, `representativePoint: GeoPoint`, `geometry`, 계산된 `bbox` |
| `RegionSection` | `regionCode: String?`, `startFraction`, `endFraction`: Double. 승인 선분 안의 순서 있는 구간, null은 지역 밖 |

GeoJSON의 `[경도, 위도]`는 파일 로더에서 `GeoPoint(위도, 경도)`로 변환한다. 코드 문자열과 앞자리 0을 보존한다.
provinceName·뱃지 에셋 등 표시 메타데이터는 기존 데이터 규격을 따르며, 판정 모델의 필드로 중복 계산하지 않는다.
`IndexedRegionCatalog`는 입력 geometry를 복사하고 경계 상자 트리를 한 번 생성한다. 입력마다 전국 파일을 읽거나 파싱하지 않는다.
`findCandidates(point)`는 점의 경계 상자, `findCandidates(bounds)`는 선분 전체의 경계 상자와 겹치는 지역을 반환한다.
양 끝점이 지역 밖이어도 중간에 통과하는 지역을 빠뜨리면 안 된다. 미존재는 빈 목록, 조회·데이터 오류는 예외다.

`RegionGeometryResolver.locate`는 hole 내부를 제외한다. 외곽·hole 경계선은 경계로 취급한다.
경계 후보에 직전 지역이 있으면 유지하고, 없으면 code 사전순으로 선택한다. 겹친 내부 후보도 code 순으로 하나를 선택한다.
`split`은 외곽·hole 교점에서 선분을 나눠 같은 지역의 연속 구간을 합친다. 분할 비율의 합은 1이며 지역 밖도 보존한다.
거리에는 F-03의 승인 연결 거리를 해당 비율로 배분한다. 좌표 선형 근사와 한국 기준 고정 투영을 사용하며,
날짜변경선을 가로지르는 지역 ring·승인 선분은 지원하지 않고 거절한다. 세계 전역 경계 엔진으로 간주하지 않는다.

유한 좌표·닫힌 ring·3개 이상 서로 다른 점·비영 면적을 검사한다. 연속 중복점은 허용하고,
자기 교차·외곽 밖 hole·외곽과 접촉/교차하는 hole·겹치거나 중첩된 hole은 카탈로그 생성에서 거절한다.
실제 파일 가공 시 이 지원 범위를 검증해야 하며 불량 자료를 조용히 빈 지역으로 대체하지 않는다.

### 상태·입력·방문 제안

`RegionVisitSnapshot`은 sessionId·tripId?·revision·lastInputSequence·nextEntrySequence·candidate·previous·lastLocationElapsedMs를 가진다.
`RegionVisitCandidate`는 regionCode·entrySequence·distanceM·dwellMs·qualified를 보존한다.
entrySequence는 지역 진입마다 증가하며 Trip 변경·프로세스 복구에서 초기화하지 않는다. 전체 삭제 때 관련 기록과 함께 초기화한다.

| 입력 | 처리 |
| --- | --- |
| `Location` | F-03과 동일한 승인 위치, connectedDistanceM, newSegment를 전달. 위치를 별도로 재검증하거나 가짜 정확도로 채우지 않음 |
| `Tick` | 음수 시각 검사·입력 cursor 저장만 수행. 체류·공백·위치 기준점 변경 없음 |
| `ContinuityLost` | 거절 위치·수집 중단 등 연결 단절. 미확정 후보의 거리·체류를 0으로 만들고 이전 위치를 비움 |
| `TripStarted(tripId)` | 이후 방문의 Trip 연결 갱신. 같은 지역의 후보·확정 플래그는 보존 |
| `TripEnded` | 정차 후 체류 판정을 위해 마지막 Trip 연결과 후보를 유지 |
| `TripDetached(tripId)` | 폐기·삭제된 ID와 현재 연결이 일치할 때만 tripId를 null 처리. 후보·체류·확정 플래그 보존 |

Tick은 늦게 도착한 SDK 관측보다 시각이 앞설 수 있다. 위치끼리의 순서만 lastLocationElapsedMs로 비교한다.
F-03의 Rejected는 ContinuityLost로, Accepted는 `RegionInput.Location(location, connectedDistanceM, newSegment)`로 전달한다.
중복 응답의 validation null은 새 승인 위치가 아니다. F-03의 마지막 저장점이 아닌 모든 승인 위치를 소비한다.
연결 거리는 같은 승인 두 점의 거리와 일치해야 하며 1mm 또는 상대 오차 0.0001% 이내만 허용한다. 단절 입력 거리는 0이다.

같은 진입에서 1000m 이상 이동하거나 유효 관측 사이의 체류 합이 180000ms 이상이면 `VisitQualified`를 한 번 제안한다.
체류는 두 관측을 잇는 선분 전체가 같은 지역을 유지할 때만 누적한다. 지역 전환 시각을 추정해 체류 시간을 만들지 않는다.
10초 이상 위치 공백·newSegment·ContinuityLost·새 세션 resume에서는 미확정 거리·체류를 초기화한다.
TripStarted 자체는 후보를 보존하지만, 다음 Trip의 첫 F-03 승인 위치는 newSegment=true이므로 미확정 누적을 초기화한다.
같은 지역이면 entrySequence는 유지하며 새 위치부터 다시 누적한다.
이미 확정한 진입의 qualified는 유지하며, 유효 이탈 후 재진입이 확인되어야 다음 방문이다.
동일/이전 위치 elapsedMs 입력은 진척을 더하지 않는다. qualifiedAtMs는 조건 충족을 확인한 승인 위치의 timeMs다.

### 저장 조합과 복구

```kotlin
val detector = RegionVisitDetector(regionCatalog)
val visits = ProcessRegionVisitUseCase(regionVisitStore, detector, idGenerator)
visits.start(sessionId, tripId)
visits.process(sessionId, sequence, regionInput)
visits.resume(newSessionId)
```

이 API는 F-05 단독 저장 경계의 검증용 조합이다. 실제 F-03·05·보상 통합은 각 엔진의 제안을 같은 DriveChangeSet에 넣어
하나의 트랜잭션으로 반영해야 한다. F-03 UseCase 저장 후 F-05 UseCase 저장을 순서대로 호출하는 것만으로 원자성이 생기지 않는다.
Trip 폐기·삭제 시 기존 참조 해제와 TripDetached 입력도 같은 통합 변경에 포함한다.

`RegionVisitStore`:

- `load()`는 현재 사용자의 마지막 commit된 방문 snapshot을 반환한다. 한 입력 큐가 시작·처리·resume·삭제를 직렬 소유한다.
- `findBadges(regionCodes)`는 요청한 **regionCode 집합**의 기존 뱃지를 읽되 반환 Map의 키는 **badgeId (`REGION:{code}`)**다.
- `commit(expectedRevision, update)`는 현재 revision을 비교하고 snapshot·뱃지·Trip 방문·보상을 한 트랜잭션으로 저장한다.
  expectedRevision null은 최초 생성에만 허용한다. 불일치·조회 실패·저장 실패·취소를 호출자에게 전달하며 일부만 저장하지 않는다.

`RegionVisitUpdate`는 snapshot, qualifiedVisits, badges, visits, rewards를 가진다. badges는 badgeId별 변경 제안이다.
최초 REGION 뱃지는 획득 시각·firstTripId·visitCount 1을 만들고 `BADGE:REGION:{code}` 보상 키를 한 번 제안한다.
기존 뱃지는 최초 획득 정보를 보존하고 visitCount만 증가시키며 새 보상을 만들지 않는다.
연결된 Trip이 있을 때만 TripRegionVisit를 만들고 `(tripId, regionCode, entrySequence)`로 중복을 차단한다.
Trip이 없으면 firstTripId·보상 tripId는 null이며 dangling TripRegionVisit를 만들지 않는다.
뱃지 badgeId와 보상 deduplicationKey는 각각 유니크다. UI는 반환된 제안이 아닌 commit된 보상 큐를 관측한다.

시작·입력·resume 모두 sessionId와 입력 cursor로 재시도한다. 저장 실패 시 같은 입력부터 재시도하고 다음 입력을 진행하지 않는다.
실제 commit 뒤 응답이 유실되어도 같은 sequence 재전송은 제안을 다시 만들지 않는다. 새 세션은 resume로 연결 정보를 비우고,
미확정 후보의 누적을 초기화하되 entrySequence·확정 여부·마지막 Trip 연결은 보존한다. 원본 시계로 타이머를 이어 붙이지 않는다.
전체 삭제에서는 방문 snapshot·진입 번호·방문·뱃지·보상을 함께 제거한다. F-10의 실제 큐 관측·표시·acknowledge는 후속 앱 구현이다.

## 9. F-12 고스트 API와 앱 연결 계약

이슈 #9의 범위는 순수 Kotlin 고스트 판정·원본 이력 조회 계약·snapshot과 종료 구간 저장 제안이다.
기존 F-03·F-05·legacy·설정 API와 app·Gradle·SDK·Room·팀원 데이터는 변경하지 않는다.
지도 표시·리플레이·F-13 GhostResult 집계·F-07 칭호·F-10 보상 큐 생성은 후속 통합 범위다.

### 이력 조회와 경로 연속성

`HistoryRepository.findNearby(gridKeys: Set<String>, excludedTripId: String): List<HistoricalTrack>`는
현재 Trip을 제외한 완료·보존 이력을 반환한다. `GhostDetector.requiredGridKeys`는 현재 승인 선분이 통과한 격자와
각 격자의 인접 8개를 포함한다. 단절 첫 점은 현재 격자와 인접 8개만 조회한다.

- 동일 tripId는 한 HistoricalTrack으로 묶고 TrackPoint.sequence 오름차순으로 반환한다.
- gridKey만으로 일치를 확정하지 않는다. 조회 구간의 앞뒤 인접 원본도 포함해 보간할 선분을 구성한다.
- 누락된 sequence를 다시 번호 매기거나, 다른 pathSegmentId·Trip의 점을 이어 붙이지 않는다.
- cursor 사이의 중간 원본이 조회에서 누락되면 연속성을 증명하지 못하므로 거리를 인정하지 않는다.
- 원본은 F-03이 승인·확정한 TrackPoint다. 과거 연결은 같은 Trip·segment와 연속 sequence로 확인한다.
  timeMs는 벽시계이므로 그 차이로 수신 공백·속도·방향 순서를 다시 판정하지 않는다.
- 데이터 오류·조회 실패는 예외다. 빈 이력은 정상적인 후보 없음이며 실패 대체값으로 사용하지 않는다.

`HistoryRepository.findForward(cursor: GhostCursor, maxDistanceM: Double): HistoricalTrack`는
활성 고스트의 마지막 일치 cursor 이후 복귀에 필요한 연속 원본을 거리 범위로 조회한다.
현재 위치 주변 격자만으로는 미일치 구간의 중간 원본을 확보할 수 없으므로 별도 조회한다.

- cursor와 같은 과거 Trip·pathSegmentId에서 cursor.from부터 sequence 순서로 반환한다.
  거리 한도는 cursor의 보간 위치부터 과거 경로를 따라 측정하고, 한도에 도달하거나 처음 넘는 끝점까지 포함한다.
- 실제 누락 sequence·segment 경계에서 중단한다. 삭제·정리로 원본이 없으면 같은 tripId의 빈 HistoricalTrack을 반환한다.
  누락을 메우거나 전체 Trip을 무조건 읽는 대체 처리를 하지 않는다. 조회 오류는 예외로 전달한다.
- UseCase는 활성 미일치 거리가 0보다 크고 현재 입력이 이전 승인점과 연결될 때 한 번만 추가 조회한다.
  한도는 `mismatchedDistanceM + connectedDistanceM + 2 × 30m`다. 후보별·포인트별 조회는 하지 않는다.
  중복 입력·10초 이상 공백·newSegment·ContinuityLost·TripEnded에는 이전 cursor의 추가 조회가 없다.
- 주변 결과와 합칠 때 원본 `(tripId, sequence)`와 모든 필드를 보존한다. 두 조회의 동일 원본은 한 번만 유지한다.
  다른 Trip 반환·정렬 오류·중복 Trip·동일 sequence의 상충 원본은 commit 전에 실패로 전달한다.
  조회 간 데이터가 바뀌면 다음 입력으로 넘어가지 않고 같은 입력을 재시도한다.

기존 findNearby 시그니처는 유지한다. 버전 5부터 HistoryRepository 구현체는 findForward도 구현해야 한다.
현재 실제 Room 구현체는 없으며, 테스트 저장소만 두 조회를 구현한다. Room 어댑터 연결 시 이 범위 조회와
`(tripId, sequence)` 인덱스를 함께 구현한다. GhostSnapshot 모델은 변경하지 않았다.

`GhostMatcher`는 인접 원본 사이를 선형 보간하고 현재점과의 구면 거리 30m 이하,
현재 방향과 양 끝 과거 방향의 원형 차이 45도 이하를 검사한다. 방향 미확인은 후보에서 제외한다.
`GhostCursor(from, to, fraction)`는 원본 두 점과 0..1 보간 비율을 보존한다. 현재 Trip은 판정에서도 재차 제외한다.
과거 cursor가 뒤로 가거나 sequence·segment 연결이 끊기면 후보 누적을 합치지 않는다.

거리 인정 전 현재 승인 선분 전체가 대응 과거 경로의 30m 범위에 있는지 확인한다.
로컬 평면의 과거 각 선분 주변 캡슐 구간을 합쳐 현재 선분을 완전히 덮는지 검사하므로 양끝만 가까운 지름길을 제외한다.
일치 거리는 현재 승인 거리와 과거 경로 전진 거리 중 작은 값이다. 한 과거점 주위의 반복 이동으로 거리를 만들지 않는다.
구면 거리·좌표 보간·로컬 평면 근사이며 날짜변경선 과거 edge는 매칭하지 않는다.
입체 교차·30m 이내 평행 도로를 정밀 도로 ID 없이 완전히 구분할 수 있다는 보장은 없다.

### 상태와 이벤트

| 타입 | 필드·역할 |
| --- | --- |
| `GhostSnapshot` | tripId·sessionId·revision·lastInputSequence·previous·candidates·active·finished |
| `GhostProgress` | cursor·firstLocation·lastMatchedLocation·matchedDistanceM·mismatchedDistanceM·sections·id? |
| `GhostKey` | 과거 tripId와 pathSegmentId. 같은 키에 여러 반복 회차 후보가 있을 수 있음 |
| `GhostSection` | currentFrom/currentTo 승인 관측, pastFrom/pastTo cursor, 인정 distanceM |
| `GhostSegment` | id·tripId·pastTripId·matchedDistanceM·startedAtMs·endedAtMs·sections |
| `GhostInput` | Location(location, connectedDistanceM, newSegment), ContinuityLost, TripEnded |
| `GhostUpdate` | 다음 snapshot과 이번 입력의 events. commit 전 확정 상태가 아님 |

F-03과 같은 승인 위치·연결 거리·newSegment를 사용한다. F-03 Rejected는 ContinuityLost로 전달하고,
중복 응답의 validation null은 새 입력으로 보내지 않는다. 별도로 가짜 정확도·방향·거리를 만들지 않는다.
입력 연결 거리의 허용 오차는 F-05와 같은 1mm 또는 상대 오차 0.0001%다. 단절 입력 거리는 0이다.

활성 전에는 같은 과거 Trip·segment 안에서 같은 위치를 여러 번 통과한 회차도 독립 후보로 유지한다.
현재 위치와 일치하는 과거 edge를 sequence가 연속인 묶음으로 나누고 각 묶음의 가장 가까운 대응점에서 시작한다.
기존 후보는 각 묶음으로 독립 진행하며 서로 다른 회차의 거리를 합산하지 않는다.
동일한 과거 cursor 위치에 도달한 후보끼리는 matchedDistanceM이 가장 큰 하나만 유지한다.
edge 끝 fraction=1과 다음 edge 시작 fraction=0은 같은 위치로 취급한다.

후보별 일치 거리가 500m 이상이면 Started를 한 번 제안하고 stable id를 발급한다.
동시에 조건을 충족한 후보는 현재 대응 과거 시각이 최근인 순서, 동률이면 tripId·segment ID 사전순으로 선택한다.
활성 대상은 구간 종료까지 고정하며 이후에는 Updated를 제안한다.

활성 뒤 미일치 이동을 따로 누적해 200m 이상이면 마지막 일치 위치에서 Finished를 제안한다.
200m 미만에서 복귀하면 누적 미일치 거리를 0으로 만들지만, 미관측 연결 거리를 matchedDistanceM에 넣지 않는다.
복귀 가능 전진 범위는 마지막 일치 이후 미일치 누적을 포함한다. 미일치 기간 다른 후보가 새 조건을 충족하면
한 입력에 이전 Finished와 다음 Started가 함께 있을 수 있어 events 목록을 반환한다.

10초 이상 공백·newSegment·ContinuityLost·resume는 활성 구간을 닫고 이전 위치·후보를 비운다.
TripEnded는 같은 정리를 수행하고 finished=true로 닫는다. 동일 세션 중복/이전 sequence는 재처리하지 않는다.
동일/이전 위치 elapsedMs도 거리를 더하지 않는다. Tick으로 고스트 거리나 시각을 외삽하지 않는다.
startedAtMs는 최초 일치 관측, endedAtMs는 시작 이상으로 보정한 마지막 일치 시각이다.
벽시계가 역전되어도 sections의 원본 관측 시각은 보존하며, epoch 차이로 소요 시간 경쟁을 계산하지 않는다.

### 저장과 복구

```kotlin
val detector = GhostDetector(idGenerator)
val ghosts = ProcessGhostInputUseCase(ghostStore, historyRepository, detector)
ghosts.start(tripId, sessionId)
ghosts.process(tripId, sessionId, sequence, ghostInput)
ghosts.resume(tripId, newSessionId)
```

`GhostStore.load(tripId)`는 마지막 commit된 GhostSnapshot을 반환한다.
`commit(expectedRevision: Long?, update: GhostUpdate)`는 Trip별 revision을 비교하고 snapshot과
Finished의 GhostSegment를 한 트랜잭션으로 저장한다. segment id는 유니크이며 null revision은 최초 생성에만 사용한다.
동일 입력 재시도 키(sessionId·sequence)를 바꾸지 않는다. 실패·취소를 상위에 전달하고 다음 입력을 진행하지 않는다.
실제 저장 후 응답만 유실되어도 같은 입력 재전송은 Started·Finished를 다시 만들지 않는다.

resume는 새 세션의 단조 시계로 위치를 잇지 않고, 활성 구간을 마지막 일치점에서 닫은 뒤 새 관측부터 시작한다.
Trip 삭제는 해당 snapshot·종료 구간을 제거하고, 보존 결과의 과거 Trip 참조 해제는 후속 통합 트랜잭션에서 수행한다.
전체 삭제는 활성·대기 후보·snapshot·종료 구간도 함께 제거한다.

위 UseCase는 F-12 단독 저장 경계다. 실제 F-03·05·12·보상 통합은 엔진 제안을 하나의 DriveChangeSet과
트랜잭션으로 묶는다. 여러 전용 UseCase를 순서대로 호출하는 것만으로 원자성을 보장하지 않는다.
GhostSegment는 F-13용 원본 대응 구간이며 GhostResult·보상 큐가 아니다. F-13은 이 대응 범위에 대해
최초 통과·현재 이전 최종 통과·종료 뒤 신규 도로를 집계해야 하며 과거 Trip 전체 시각으로 대체하지 않는다.

## 10. F-13 비교 결과 API와 앱 연결 계약

이슈 #11의 구현은 위치 기반 비교 결과와 저장 제안이다. F-15 원본 지표가 준비되지 않았으므로
metricsDiff는 null이다. Efficiency·EfficiencyUnit·MetricsDiff 모델 추가가 차량 지표 계산 완료를 뜻하지 않는다.
기존 앱·SDK·Room·지도·F-03·05·12 임계값은 보존한다. 실제 보상 큐 표시·TTS·칭호 지급·리플레이는 후속 통합이다.

### 입력과 호출 순서

```kotlin
val comparisons = ProcessGhostComparisonUseCase(ghostComparisonStore, historyRepository, idGenerator)
comparisons.start(tripId, sessionId)
comparisons.process(tripId, sessionId, sequence, GhostComparisonInput(trackUpdate, ghostUpdate))
comparisons.resume(tripId, newSessionId, GhostComparisonInput(trackResumeUpdate, ghostResumeUpdate))
```

- GhostComparisonInput은 같은 Trip·session의 F-03 TrackUpdate와 F-12 GhostUpdate다. F-03 승인·거절·종료와
  F-12 처리를 동일 순서로 수행하고 **모든 revision**을 전달한다. 두 엔진을 revision 0에서 시작한 뒤 F-13도 시작한다.
- 위치 입력은 같은 sequence다. TrackRecorder.finish는 마지막 위치 sequence를 유지하므로 종료 입력은
  GhostUpdate.lastInputSequence를 사용한다. 종료는 F-03 FINISHED/DISCARDED와 F-12 finished=true를 함께 전달한다.
- resume는 F-03·F-12 resume 제안과 함께 호출한다. 새 세션의 sequence는 -1로 초기화하고 다음 입력부터 0 이상이다.
  이미 저장된 동일 세션 resume·동일/이전 입력 sequence는 재처리하지 않는다.
- 누락 revision·서로 다른 Trip/session·불일치 승인 위치는 commit 전에 거절한다. 중간부터 연결해 빠진 경계를 추측하지 않는다.
- 세 전용 UseCase를 차례로 commit하는 방식은 통합 트랜잭션이 아니다. 실제 앱은 TrackRecorder·GhostDetector·
  GhostResultAssembler의 제안을 같은 DriveChangeSet에 조합하고 snapshot·포인트·결과·보상을 한 번에 저장한다.
  GhostResultAssembler.process 자체도 입력 일관성을 검증한다. UseCase는 조회 전에 validate를 먼저 호출한다.

### 날짜 근거와 표시 경로

GhostResultBuilder는 Finished의 GhostSegment를 받는다. 현재 Trip을 제외한 완료·보존 이력을
HistoryRepository.findNearby로 대응 현재 선분 전체와 인접 격자에 대해 한 번 묶어 조회한다.
조회 어댑터는 9절처럼 앞뒤 원본·sequence·segment를 보존한다. 후보별 DB 조회를 하지 않는다.

- 선택한 과거 Trip은 F-12가 고정한 pastTripId다. 표시용 과거 경로를 더 오래된 Trip으로 교체하지 않는다.
- 최초·이전 최종 시각은 보존 이력 중 해당 공간·방향에 대응하는 원본 cursor의 epoch 시각이다.
  Trip 시작·종료 시각이나 격자의 lastVisitedAt으로 대체하지 않는다. 서로 다른 Trip의 날짜 근거는 각 공간 범위에만 적용한다.
- GhostPassage는 `sectionIndex`, `fromFraction`, `toFraction`, `distanceM`, `currentPassedAtMs`,
  `firstPassedAtMs`, `previousLastPassedAtMs`, `visits: List<GhostPassageVisit> = emptyList()`다.
  fraction은 대응 GhostSection 현재 선분의 0..1 위치다.
  각 section의 passage 거리 합은 해당 section의 인정 거리다.
- GhostPassageVisit는 `pastTripId: String?`, `pathSegmentId: String`, `currentFromAtMs: Long`,
  `currentToAtMs: Long`, `pastFromAtMs: Long`, `pastToAtMs: Long`이다. 현재·과거의 from과 to는 각각 같은
  대응 위치의 시각 쌍이다. 같은 passage의 모든 visit는 같은 현재 양끝 시각을 사용한다.
- 긴 승인 선분은 과거 원본점 투영과 연속 과거 edge의 30m 범위·방향 허용 범위의 진입/이탈 위치에서 나눈다.
  과거 edge 양끝 밖의 투영 구간은 제외하고, 같은 Trip·segment·연속 sequence·전진·양끝 과거 방향과 45도 이하를 요구한다.
  30m 범위와 원본 투영은 GhostCorridor와 동일한 GhostPlane으로 계산한다. 경계를 다른 구면 거리로 재검사하여
  양의 길이인 부분 전체를 제거하지 않는다. F-12 공개 매칭과 30m·45도·500m·200m 임계값은 변경하지 않는다.
- 선택한 대응 과거 경로는 원본 꼭짓점에서도 나누고 경로 거리 비율로 시각을 보간한다. 추가 방문은 원본 edge의
  투영 비율로 보간한다. epoch ms는 가장 가까운 밀리초로 반올림하며 시각 역전을 공백으로 오인하지 않는다.
  보간은 날짜 근거 계산용이며 새 승인 입력으로 저장하지 않는다.
  currentPassedAtMs는 해당 현재 부분의 양끝 epoch 중 작은 값, previousLastPassedAtMs는 대응 과거 시각 중 큰 값이다.
- 결과 요약의 firstPassedAtMs는 passage의 최소값, previousLastPassedAtMs는 최대값이다.
  이 요약이나 `currentPassedAtMs - previousLastPassedAtMs`로 경과 시간을 계산하지 않는다. 서로 다른 위치의 시각이기 때문이다.
- F-07에 전달할 30일 경과 거리는 `result.passages.sumOf { it.revisitedDistanceM(30L * 24 * 60 * 60 * 1000) }`으로 계산한다.
  이 메서드는 같은 위치의 현재−과거 시각을 선형 보간하고, 모든 방문에 대해 조건을 만족하는 부분 거리만 반환한다.
  따라서 각 위치의 가장 최근 방문을 기준으로 판정하며, 정확히 30일은 포함하고 일부만 만족하면 그 길이만 인정한다.
  음수 기준·유효하지 않은 거리/시각·불일치 현재 시각 쌍은 거절한다. 이 메서드가 칭호를 지급하지는 않는다.
- 버전 7 이전처럼 visits가 비어 있으면 경과 거리 근거가 없어 0m를 반환한다. 표시용 요약으로 복원하지 않는다.
  Room 연결 시 visits 전체를 저장·복구한다. 현재 실제 Room 구현체가 없어 이번 변경에 DB migration은 없다.
- 현재·과거 표시 경로는 인정된 sections만 구성하고 미일치 연결 구간은 별도 PathSegment로 끊는다.
  과거 중간 원본을 포함한 뒤 허용 오차 5m로 단순화하여 결과에 복사한다. 원본 삭제 후에도 복사한 경로는 남는다.
- F-12 cursor에 보존한 원본도 이력과 병합하되 동일 sequence 상충은 실패다. 필요한 중간 원본이 없으면
  결과를 추측하거나 직선으로 잇지 않고 오류를 전달한다. 삭제된 시기의 원본 없는 정밀 매칭은 복원하지 못한다.
- 이력은 한 번 검증하고 edge가 통과하는 격자로 메모리 인덱싱한다. 계산한 부분 범위와 시각 쌍을 재사용한다.
  구면 거리·좌표/방향 보간·로컬 평면 근사이며 입체 교차·가까운 평행 도로 구별 한계는 F-12와 같다.

### 신규 거리 경계와 최종 확정

newRoadM의 범위는 **고스트의 마지막 일치 위치 뒤부터 다음 고스트의 첫 일치 위치 또는 Trip 종료까지**다.
500m 활성 감지 위치와 200m 이탈 감지 위치로 범위를 자르지 않는다.

- F-03 ACTIVE snapshot에서 newGridKeys에 속한 pendingRoads.firstExploredDistanceM 합만 현재 Trip 누적으로 읽는다.
  과거에 탐험한 격자의 영구 누적값은 제외한다. F-03 finish는 pendingRoads를 비우므로 종료 때 직전 누적을 유지한다.
- GhostComparisonSnapshot은 후보 firstLocation.elapsedMs별 누적, 활성 구간의 최초·마지막 일치 누적을 보존한다.
  모든 F-03·F-12 입력을 전달해야 실제 경계의 누적을 확보할 수 있다. 임의 거리·격자 수 × 100m로 대신하지 않는다.
- Finished에서 F-12 구간 id를 그대로 결과 id로 사용하고, 마지막 일치 누적을 기준으로 감지 지연 중 신규 거리도 포함한다.
- 다음 Started가 오면 첫 일치 체크포인트에서 이전 결과를 finalized=true로 확정한다.
  그 전 provisional newRoadM은 다음 후보의 500m 확인을 기다리는 값이므로 최종 절단 때 감소할 수 있다.
- Trip 종료는 마지막 pending 결과를 확정한다. resume는 활성 고스트를 닫고 같은 Trip의 신규 거리 기준을 보존하되,
  이전 세션 elapsedMs 후보를 비운다. 같은 결과를 새 ID로 만들지 않는다.

### 저장·보상·실패 처리

GhostComparisonSnapshot은 tripId·sessionId·revision·lastInputSequence·F03/F12 revision·newRoadM·후보 체크포인트·
active·pending·finished를 가진다. GhostComparisonUpdate는 다음 snapshot·결과 ID별 변경·신규 PendingReward 제안이다.

GhostComparisonStore는 `suspend load(tripId): GhostComparisonSnapshot?`,
`suspend commit(expectedRevision: Long?, update: GhostComparisonUpdate)`를 구현한다.
revision 비교와 snapshot·결과 upsert·신규 보상을 같은 트랜잭션으로 저장한다. null revision은 최초 생성 전용이다.
결과 id와 보상 deduplicationKey에 유니크 제약을 둔다. 실제 Room 구현체는 아직 없다.

결과 최초 생성 때 `GHOST_RESULT:{resultId}` 보상을 한 번 제안한다. subjectId는 resultId,
occurredAtMs는 F-12 endedAtMs다. 거리 갱신·최종 확정에는 보상을 다시 만들지 않으며 확인 상태를 초기화하지 않는다.
F-10 UI는 subjectId로 최신 결과를 조회한다. 큐의 소비·표시·acknowledge는 앱의 후속 구현이다.

조회 실패·상충 원본·저장 실패·취소는 상위로 전달한다. 같은 sessionId·sequence로 재시도하며 다음 입력을 먼저 처리하지 않는다.
commit 뒤 응답이 유실되어도 저장된 cursor로 결과·보상 중복을 막는다. Trip 삭제·전체 삭제는 비교 snapshot과 해당 결과도 제거하고,
다른 Trip의 결과가 삭제한 과거 Trip을 참조하면 결과와 각 GhostPassageVisit의 pastTripId를 null로 해제한다.
표시 경로·pathSegmentId·동일 위치 시각 쌍·날짜 요약은 보존한다.
