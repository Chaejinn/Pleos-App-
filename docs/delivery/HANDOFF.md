# 문서 확인 및 인수인계

기준일: 2026-11-15 · 브랜치: merge_jhmin

## 문서 양식 검토
개발 착수 문서의 읽는 순서와 우선순위는 현재 프로젝트 구조에 맞는다. CONVENTIONS, CONTRACTS, IMPLEMENTATION_RULES, WORK_PLAN, DEMO_AND_DATA 및 references 원문은 유지한다. 이번에 다시 전달한 기획서·기능명세·작업 순서 원문 3개는 기존 references 파일과 바이트 단위로 일치한다.

채팅에서 복사한 `\|`, `\(`, HTML 문자 코드 등의 이스케이프는 배포 Markdown에서 제거해야 표와 링크가 정상 렌더링된다. 저장소의 개발 문서는 정상 Markdown이며, 제출용 PDF는 docs/delivery의 구현 기준 문서에서 생성한다.

원본 기능명세의 필수 기능은 14개다. F-06은 선택이고, F-13 계산은 필수다. 개발 계약 문서만으로 SDK·알고리즘·지도 연동 완료를 주장하지 않는다.

## 이번 화면 변경
- 지도 중심 화면, 숲색·미색 테마, 아이콘 메뉴, 보조 동작 분리.
- 가로 화면 좌측 메뉴와 지도·시작 패널 분할, 좁은 화면 하단 메뉴.
- 수집 도감 1~3열 카드, 주행 기록 카드, 정적인 주행 상태 화면.
- 가상 주행 메뉴 분리, 명소 상세의 수집 여부 정정, 탭 전환 스크롤 초기화.
- WebView 렌더러 종료 처리와 재연결. 정차 화면의 지도 크기는 기존 설정 유지.

## 직접 실행 순서
1. JDK 17로 `./gradlew.bat :domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`를 실행한다.
2. APK를 설치하고 기록 저장 동의 후 지도에서 탐험을 시작한다. 실제 GPS는 정확한 위치 권한이 필요하다.
3. ‘가상 주행 체험 → 명소 수집 시연’을 실행한다. 종료 후 시연 배지·주행 기록을 확인한다.
4. 같은 시연을 다시 실행하고 두 번째 기록에서 ‘과거의 나와 만난 구간’을 확인한다.
5. 도감에서 실제 방문·시연 기록을 전환하고 명소 검색·상세·지도 이동을 확인한다.

## 남은 원본 요구사항
랜드마크 점 897개를 행정구역 경계로 사용하지 않는다. F-05 경계 수집·지역 30개 칭호·동서해 칭호는 해당 데이터와 앱 연결이 필요하다. 대학 구역은 선택이다. Pleos 차량 신호 기반의 새 기록 파이프라인, 차량 특성, 신규 보상 음성·고스트 보상 큐, 180일 정리와 성능 목표는 별도 후속 작업이다. 완료·미완료 상세는 기능명세 대응표와 VALIDATION.md를 따른다.

## 지도 종료 대응 근거
Android 공식 WebViewClient.onRenderProcessGone 계약에 따라 종료된 WebView를 뷰 계층에서 제거하고 폐기한 뒤 별도 뷰로 재시도한다.
https://developer.android.com/reference/android/webkit/WebViewClient#onRenderProcessGone(android.webkit.WebView,%20android.webkit.RenderProcessGoneDetail)

기존 종료 원인은 에뮬레이터의 System UI 응답 없음·시스템 프로세스 종료 기록만으로 확정하지 않는다. 현재 변경은 확인된 취약 경로를 보강한 것이며, 모든 기기의 종료 문제가 해결됐다는 보장은 아니다.
