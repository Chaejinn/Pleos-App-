# F-03 Android 저장 어댑터 통합 기록

2026-11-13

## 기준

- main a4134a0: 공식 가이드 분석 문서 반영.
- PR #4, head 5023afe: 경로 기록·복구 엔진과 TrackRecordingStore 계약. 원격 PR은 아직 open이며 이번 작업은 로컬 통합 검증이다.
- 가이드 분석 문서는 코드 변경을 보류하도록 제안했으나, 이번 사용자 지시로 검토 후 저장 어댑터 개발을 재개했다. 외부 승인·SDK 필드의 의미를 확인한 것으로 해석하지 않는다.

## 구현

- Room 2.6.1 + Java annotation processor. Android 타입을 domain에 추가하지 않았다.
- TrackRecordingStore 구현: snapshot revision 검사, 포인트 ID·sequence 중복 검사, 짧은 Trip 폐기, 확정 도로·snapshot·포인트를 동일 트랜잭션으로 저장.
- AES-256-GCM으로 payload 암호화. 행 ID를 AAD로 사용하고 매 암호화마다 새 IV를 생성한다. 운영 키는 Android Keystore alias roadlog.recording.v1에 보관한다.
- 좌표·경로·격자·관측 시각은 암호문 안에 저장. 행 ID에는 레코드 종류와 Trip ID가 남으므로 DB 전체 암호화와 동일하지 않다.
- 키 유실·변조·복호화 실패를 빈 기록으로 대체하지 않고 오류를 전달한다.
- Room 스키마 1을 app/schemas에 내보낸다. AppGraph에 lazy 저장소와 UseCase factory를 추가했다.

## 검증과 한계

- Robolectric + 실제 Room SQLite로 DB 재개방·복구 후 공백 단절, revision 충돌, 200m 미만 폐기, 전체 삭제, 저장 실패 rollback·같은 입력 재시도를 검증한다.
- JVM 키로 AES-GCM 암호문·IV·변조·행 바꿔치기를 검증한다. Android Keystore 실기기 검증과 동일하지 않다.
- 아직 RoadLogService와 UI에 새 저장소를 연결하지 않았다. 현재 화면·서비스의 legacy JSON은 그대로이며 자동 이행·삭제 연동도 아직 하지 않았다.
- 버전 1 어댑터는 포인트 목록과 도로 인덱스를 암호화된 묶음으로 읽고 쓴다. 장시간·대용량 기록의 쓰기 증폭과 조회 성능을 검증하기 전 운영 완료로 취급하지 않는다. 개별 포인트·격자별 암호화 행과 조회 인덱스로 나누는 후속 작업이 필요하다.
- 앱 진입 동의·서비스 입력 직렬화·종료·프로세스 복구를 연결하기 전 새 DB에 사용자 기록을 쓰지 않는다.

## 다음 연결 조건

1. 실제 FusedLocation confidence를 accuracyM으로 임의 변환하지 않는다. 정확도 미확인은 LocationValidator의 MISSING_ACCURACY로 유지한다.
2. heading·timestamp 의미와 정확도 원천이 확인될 때까지 실제 입력 지원 완료로 표시하지 않는다. 합성 입력은 정확도를 명시한 재생 데이터로 별도 검증한다.
3. 수집 상태와 CarUxRestrictions를 분리하고 규제 실패 시 상세 UI를 허용하지 않는 어댑터를 구현한다.
4. 기존 JSON 이행·설정의 전체 삭제·보관 정책과 새 DB를 함께 연결하고 검증한다.
5. 지도·보상·고스트 허용 범위와 권한 승인 여부는 공식 확인 대상으로 남긴다.

## 구현 참고

- [Room Entity](https://developer.android.com/training/data-storage/room/defining-data)
- [Android 암호화·권장 알고리즘](https://developer.android.com/privacy-and-security/cryptography)
