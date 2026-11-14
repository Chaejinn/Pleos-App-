# Pleos-App-

현재 통합 작업은 `merge_jhmin`에서 진행합니다. 전국 897개 명소, 카카오 지도, GPS 탐험·수집 도감 연결을 포함합니다.

- [현재 기획서 PDF](docs/delivery/RoadLog_기획서.pdf)
- [현재 기능명세서 PDF](docs/delivery/RoadLog_기능명세서.pdf)
- [검증 결과와 남은 작업](docs/delivery/VALIDATION.md)
- 카카오 키는 루트 `.env`에 입력합니다. `.env.example` 참고. Android 키는 패키지·서명 해시, JavaScript 키는 `https://appassets.androidplatform.net` 도메인 등록이 필요합니다.
- 키 변경 후 JDK 17에서 `./gradlew.bat :app:assembleDebug`로 다시 빌드합니다. `.env`는 커밋하지 않습니다.
- PDF 재생성: `pip install -r scripts/pdf-requirements.txt` 후 `python scripts/export_documents.py` (Windows 맑은 고딕 기준).
현대자동차에서 진행하는 (자동차 안에서 새로운 고객 경험을 제공할 수 있는 차량용 앱을 직접 개발) 플레오스 공모전

## 개발 문서

[개발 착수 문서](docs/README.md)에서 팀 컨벤션, 공통 데이터·인터페이스, 구현 규칙, 담당별 작업 계획과 시연 규격을 확인할 수 있습니다.

구현 기준은 [기능명세서](docs/references/FUNCTIONAL_SPEC.md)이며, 기획서는 서비스 목적을 이해하는 자료로 사용합니다.
