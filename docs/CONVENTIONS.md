# 팀 개발 컨벤션

버전: 1 · 적용일: 2026-11-11

## 1. 구조와 의존성

초기 모듈은 두 개다. 작은 팀의 통합 비용을 줄이면서 순수 Kotlin 판정 테스트를 분리한다.

```text
domain/src/main/kotlin/com/pleos/explorer/domain/
  model/                 공통 값·결과 모델
  port/                  저장·정적 데이터 조회 인터페이스
  engine/                위치·주행·지역·고스트·칭호·특성 판정
  usecase/               입력 처리와 저장 작업 조합
  geo/                   거리·방향·격자·폴리곤 계산
domain/src/test/          Android 없는 판정 테스트
app/src/main/java/com/pleos/explorer/
  platform/location/     Pleos 위치 어댑터
  platform/vehicle/      차량 상태·특성 어댑터
  platform/speech/       음성 어댑터
  data/local/            Room Entity·DAO·DB·마이그레이션
  data/repository/       domain port 구현·매퍼
  data/catalog/          내장 정적 데이터 로더
  presentation/map/      S-01
  presentation/collection/ S-02
  presentation/archive/  S-03
  presentation/settings/ 권한·동의·설정
  service/               수집 서비스와 입력 직렬화
  di/                    객체 생성·주입
app/src/main/assets/catalog/ 배포용 지역·대학·칭호·에셋 데이터
app/src/debug/assets/demo/   재생 입력·시연 seed
test-data/               Android와 무관한 공통 fixture
docs/                    개발 기준과 검증 기록
```

이 구조는 신규 구현의 기준이다. 2026-11-11에 역할별 파일 분리를 반영했으며, 실제 파일·미구현 영역은 [현재 코드 구조](ARCHITECTURE.md)를 따른다. 기존 데모 코드는 각 역할 디렉터리의 `legacy`에 격리한다. `com.pleos.explorer`는 내부 개발용 네임스페이스이며, 배포용 applicationId 확정을 의미하지 않는다.

- `app → domain`만 허용한다. `domain → app`과 순환 참조는 금지한다.
- `domain`은 Kotlin/JVM과 coroutines만 사용한다. Android, Room, Compose, Pleos 클래스와 애너테이션은 가져오지 않는다.
- UI는 Compose + ViewModel + StateFlow를 기본으로 한다. 지도 뷰가 별도 Android View이면 `presentation/map`의 어댑터에서 감싼다.
- DI는 생성자 주입과 `app/di`의 수동 조립으로 시작한다. DI 프레임워크 도입을 착수 조건으로 만들지 않는다.
- Repository 패턴으로 저장 구현을 숨기고, 위치·차량 어댑터로 SDK 타입을 숨긴다. 실제 SDK와 재생 입력을 같은 엔진에 연결하기 위한 경계다.
- 인터페이스는 외부 입출력·저장 경계에 둔다. 내부 계산 클래스까지 일괄적으로 인터페이스화하지 않는다.

## 2. 수정 소유 범위

| 경로·책임 | 주 담당 | 경계 |
| --- | --- | --- |
| `domain/**`, 판정 임계값 | 동현 | Android·Room 타입 추가 금지 |
| `app/**` 소스, 루트 Gradle, 버전 카탈로그 | 채진 | 도메인 알고리즘을 ViewModel·DAO에 재구현하지 않음 |
| `app/src/main/assets/catalog/**`, `test-data/**`, `app/src/debug/assets/demo/**` | 현민 | 규격·로더 코드는 동현·채진이 구현 |
| `docs/CONTRACTS.md`, `docs/IMPLEMENTATION_RULES.md` | 동현 | Android 매핑 영향은 채진 검토 |
| 나머지 문서, 출처·문구·시연 절차 | 현민 | 실행·검증 결과는 수행자가 기록 |

소유자는 병목을 만드는 승인권자가 아니라 수정 조정 담당자다. 공통 파일 변경 필요 시 PR에 영향 경로와 변경 계약을 적고, 영향을 받는 담당자를 리뷰어로 지정한다. 다른 사람의 작업을 덮어쓰지 않는다.

## 3. Kotlin 작성 규칙

| 대상 | 규칙·예시 |
| --- | --- |
| 클래스·인터페이스·파일 | PascalCase, `TrackPoint.kt`, `GhostDetector` |
| 함수·프로퍼티 | lowerCamelCase, `processLocation`, `distanceM` |
| 상수·enum 항목 | UPPER_SNAKE_CASE, `REGION`, `UNKNOWN` |
| 패키지 | 소문자, 기능과 계층을 표현 |
| 포맷 | UTF-8, LF, 들여쓰기 4칸, 탭 금지, 마지막 개행 |
| 줄 길이 | 120자 목표, 가독성을 위해 긴 인자 목록은 줄바꿈 |
| 데이터 | `val`, 불변 컬렉션, `data class` 기본 |
| nullable | 미지원·미수신에만 `null`; 0·빈 문자열·NaN으로 대체 금지 |
| 접근 범위 | 외부 계약만 public, 내부 구현은 internal/private |
| 주석 | 이유·제약이 코드로 드러나지 않을 때만 작성, 수정 시 함께 갱신 |

와일드카드 import, `!!`, `GlobalScope`, 도메인 전역 가변 singleton, 의미 없는 `Manager`·`Util` 묶음을 사용하지 않는다. 하나의 명확한 계산은 함수로, 상태와 수명주기가 있는 판정기는 클래스로 캡슐화한다. 상속보다 조합을 우선한다.

## 4. 비동기·오류·로그

- 단발성 I/O는 `suspend`, 지속 조회는 `Flow`, UI 현재 상태는 `StateFlow`로 표현한다.
- 엔진의 한 주행 상태를 바꾸는 입력은 수집 서비스에서 한 큐로 직렬 처리한다. 위치·차량·타이머·삭제가 같은 상태를 동시에 수정하지 않는다.
- 위치 한 건의 검증·지역·고스트 계산은 Default 작업 컨텍스트에서 수행하고, DB·파일 I/O는 적절한 I/O 컨텍스트 또는 Room 비동기 API로 실행한다. Main 스레드에서 계산·I/O를 하지 않는다.
- 엔진의 예상 가능한 거절은 결과 타입으로 돌려준다. DB·파일 장애는 호출자까지 전달해 기록 실패 상태를 표시한다. 실패를 성공·빈 목록으로 바꾸지 않는다.
- `CancellationException`은 재전파한다. 무차별 `runCatching`으로 취소까지 삼키지 않는다.
- 로그에는 기능 ID, 오류 코드, 익명 처리한 처리 건수만 남긴다. 좌표, 전체 경로, 차량 식별값, 동의 정보, API 키를 남기지 않는다.
- 화면의 일회성 효과와 영구 획득 기록을 구분한다. 보상 표시는 DB의 미확인 큐를 구독한다.

## 5. Git·커밋·PR

- `main`은 빌드·테스트 가능한 통합 브랜치다. 작업은 최신 main에서 짧은 브랜치로 시작한다.
- 브랜치: `feature/f03-track-recording`, `feature/f05-region-badge`, `fix/f12-direction`, `docs/contracts`, `data/demo-route`.
- 커밋: `feat(f05): 지역 체류 조건 판정 추가`처럼 `type(scope): 한국어 설명` 형식을 쓴다. type은 `feat`, `fix`, `refactor`, `test`, `docs`, `data`, `build`, `chore`다.
- PR은 기능 하나 또는 통합 가능한 작은 흐름 하나로 만든다. 제목에 기능 ID를 넣고 squash merge를 기본으로 한다.
- 공유 브랜치 강제 push, main 직접 push, 무관한 전체 포맷 변경을 하지 않는다.
- 한 명 이상이 리뷰하고 필수 검증 통과 후 병합한다. 최소 하루 한 번 통합한다.

PR 본문 필수 항목:

1. 구현한 기능 ID와 바뀐 동작
2. 공통 모델·단위·정적 데이터 형식·Room 스키마 영향
3. 실제 실행한 검증과 결과, 실행하지 못한 검증의 이유
4. 화면 변경이면 정차·주행 상태별 캡처, SDK 변경이면 확인한 신호와 환경

## 6. 빌드·테스트 완료 기준

- Gradle Wrapper, Kotlin DSL, `gradle/libs.versions.toml`로 도구·라이브러리 버전을 고정한다. 버전 선언은 채진이 관리한다.
- minSdk는 기능명세서대로 28이다. JDK·AGP·Kotlin·compileSdk·targetSdk·Pleos 의존성 버전은 채진이 실제 SDK 조합으로 빌드한 뒤 작업 계획에 기록한다. 임의 최신 버전을 섞지 않는다.
- Wrapper 생성 후 Windows 표준 확인 명령은 `./gradlew.bat :domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`다. 프로젝트 생성 전에는 실행 가능한 명령으로 간주하지 않는다.
- F-03·05·07·12·15는 정상 사례와 임계값 바로 아래·같음·위, 결측·중복·역순 입력을 검증한다.
- DB 변경은 마이그레이션과 삭제·재시작 검증을 포함한다. 개발 편의를 위한 destructive migration을 배포에 사용하지 않는다.
- SDK 미지원 지표는 null이어도 통과할 수 있지만, 실제 SDK 지원 여부를 확인하지 않은 상태를 '연동 완료'라고 적지 않는다.
- 비밀값과 `local.properties`, 개인 위치 기록, keystore는 커밋하지 않는다. 재생 fixture는 합성 데이터만 사용한다.

## 7. 계약 변경

필드 추가 시 기본값·null 의미와 기존 입력 호환성을 함께 정한다. 이름·단위·의미 변경은 계약 버전을 올리고 생산자·소비자·fixture·Room 매핑·문서를 같은 PR에서 수정한다. 기능 임계값 변경은 기능명세서 사본과 구현 결정의 변경 이력에 근거를 기록한다. 병합 직전 별도 합의 회의를 요구하지 않는다.
