# 기능별 전체 흐름

이 문서는 `말해주차`의 기능을 사용자 동작 기준으로 나누고, 각 기능이 어떤
Spring Boot·FastAPI 파일을 거치는지 정리한 문서다. Spring Boot는 웹 화면과
DB·외부 서비스 연동을 담당하고, FastAPI는 영상에서 차량과 주차 슬롯을
분석하는 역할을 담당한다.

## 1. 전체 구조

```text
브라우저
  ├─ 정적 화면: springboot/src/main/resources/static/index.html
  └─ 동작 코드: springboot/src/main/resources/static/app.js
        │ HTTP
        ▼
Spring Boot :8080
  ├─ REST Controller       요청/응답 진입점
  ├─ Service                기능별 업무 로직
  ├─ Repository + JPA       PostgreSQL 데이터 조회·저장
  ├─ StorageService         MinIO 파일 처리
  ├─ FastAPI /status        최신 주차 상태 수집
  ├─ Gemini/Ollama          음성 질의 답변 문장 정리
  └─ Naver Local Search     장소 검색
        │
        ▼
FastAPI :8000
  ├─ 영상 소스 탐색
  ├─ 슬롯 JSON 로딩
  ├─ YOLO 차량 탐지
  └─ 슬롯별 점유 상태 캐시 및 /status 제공
```

### 공통 파일 역할

| 영역 | 파일 | 설명 |
|---|---|---|
| 웹 화면 | `springboot/src/main/resources/static/index.html` | 지도, 로그인, 즐겨찾기, 음성 질의, 주차장 상세 화면의 HTML |
| 웹 동작 | `springboot/src/main/resources/static/app.js` | API 호출, 화면 렌더링, 로그인 상태, 브라우저 음성인식·음성출력 |
| 웹 스타일 | `springboot/src/main/resources/static/app.css` | 화면 레이아웃과 상태별 스타일 |
| Spring 시작 | `springboot/.../ServerApplication.java` | 애플리케이션 시작 및 스케줄링 활성화 |
| 보안 | `springboot/.../config/SecurityConfig.java` | 공개 API와 로그인 필요 API 구분 |
| JWT 필터 | `springboot/.../security/JwtAuthenticationFilter.java` | Authorization 헤더의 토큰 검증 |
| DB 모델 | `springboot/.../entity/` | 캠퍼스, 건물, 주차장, 사용자, 알림 등의 테이블 모델 |
| DB 접근 | `springboot/.../repository/` | Spring Data JPA 쿼리와 저장소 |

경로 표기의 `...`는 `src/main/java/com/smartparking/server`를 의미한다.

## 1.1 `index.html`과 `app.js` 연결

`index.html`은 최초 화면의 뼈대와 JavaScript가 찾을 `id`를 제공한다.
`app.js`는 HTML 마지막의 `<script src="/app.js"></script>`로 로드되며,
`DOMContentLoaded` 이후 초기화와 이벤트 연결을 시작한다. 주차장 카드, 슬롯
편집기 모달, 등록 폼처럼 데이터가 필요한 화면은 `app.js`가 `innerHTML`로
동적으로 생성한다.

### 정적 요소 연결

| `index.html` 요소 | `app.js` 연결 | 역할 |
|---|---|---|
| `#config-badge`, `#update-badge`, `#user-badge`, `#unread-badge` | `elements`, `renderCampusHeader()`, `renderAccountPanel()` | 서버 설정, 상태 갱신, 로그인, 알림 배지 |
| `#login-form`, `#register-button`, `#logout-button` | `bindAuthActions()` | 로그인·회원가입·로그아웃 이벤트 |
| `#current-location-panel` | `renderCurrentLocationPanel()` | 내 주차 위치 표시 및 해제 |
| `#favorite-list`, `#favorite-count` | `renderFavoritePanel()` | 사용자별 브라우저 즐겨찾기 표시 |
| `#notification-list`, `#notification-count` | `renderNotificationPanel()` | 알림 목록과 읽음 처리 |
| `#building-list` | `renderBuildingList()` | 건물 목록과 건물 선택 |
| `#detail-title`, `#detail-subtitle`, `#detail-content` | `renderSelectedBuilding()`, `renderParkingLotCard()` | 선택한 건물·주차장 상세 화면 |
| `#map`, `#map-fallback` | `renderMapIfPossible()`, `createNaverMap()` | 네이버 지도 또는 대체 안내 |
| `#map-search-input`, `#map-search-button` | `bindMapSearch()` | 장소 검색 |
| `#voice-button`, `#voice-question-input`, `#voice-ask-button`, `#voice-output` | `bindVoice()` | 음성 입력, 텍스트 질문, 답변·음성 출력 |

### 동적으로 생성되는 요소

`app.js`는 건물·주차장 데이터를 받은 뒤 다음 요소를 동적으로 만든다.

- 건물 버튼: `[data-building-id]`
- 주차장 카드: `[data-parking-lot-card]`
- 즐겨찾기 버튼: `[data-favorite-lot]`
- 내 주차 위치·빈자리 알림 버튼: `data-save-location-btn`,
  `data-create-alert-btn`
- 주차장 등록 폼: `#add-lot-form`
- 슬롯 박스: `[data-parking-lot-slot]`
- 슬롯 편집기 모달: `[data-editor-*]`

동적 요소는 HTML에 처음부터 존재하지 않으므로, 해당 화면을 렌더링한 직후
`bindParkingLotActions()`, `bindBuildingDetailActions()` 같은 함수가 이벤트를
연결한다.

### 대표 호출 흐름

```text
index.html 로드
  -> app.js DOMContentLoaded
  -> bootstrap()
  -> /api/ui/config, /api/campus/map 호출
  -> building-list와 지도 렌더링

건물 선택
  -> renderSelectedBuilding()
  -> GET /api/campus/buildings/{id}
  -> detail-content에 주차장 카드 생성
  -> bindParkingLotActions()

주차장 등록
  -> 동적 #add-lot-form submit
  -> submitAddParkingLot()
  -> POST /api/buildings/{buildingId}/parking-lots
  -> 건물 상세 재조회

슬롯 저장
  -> openSlotEditor()에서 슬롯 JSON 생성
  -> POST /api/parking-lots/{id}/map/slots
  -> 주차장 상세 재조회

음성·텍스트 질문
  -> bindVoice() / askVoiceQuestion()
  -> POST /api/voice/ask
  -> #voice-output에 답변 표시
  -> speak()가 브라우저 음성 출력
```

따라서 `index.html`은 화면 영역과 연결용 식별자를 정의하고, `app.js`는
상태 조회·렌더링·이벤트·API 호출을 담당한다. Spring Boot의 컨트롤러는
`app.js`가 호출한 HTTP 요청의 서버 측 진입점이다.

## 2. 서버 시작과 주기 작업

### 시작 흐름

```text
Spring Boot 시작
  -> application.properties에서 PostgreSQL·MinIO 설정 로드
  -> CampusDataInitializer가 기본 캠퍼스 확인·생성
  -> ParkingStatusService가 5초 주기로 FastAPI /status 호출 시작
  -> ParkingAlertMonitorService가 15초 주기로 알림 조건 확인 시작
```

### 관련 파일

- `config/CampusDataInitializer.java`: 캠퍼스 기본 데이터가 없을 때 생성한다.
- `service/ParkingStatusService.java`: FastAPI `/status`를 호출해 메모리 캐시에
  저장한다. 호출 실패 시 기존 캐시를 유지한다.
- `service/ParkingAlertMonitorService.java`: 활성화된 알림 규칙과 캐시된 주차
  상태를 비교해 조건을 만족하면 알림을 생성한다.
- `application.properties`: PostgreSQL, MinIO, FastAPI, Ollama 연결 설정을 사용한다.

## 3. 캠퍼스 지도와 주차 현황 조회

### 사용자 흐름

```text
페이지 열기
  -> GET /api/ui/config
  -> GET /api/campus/map
  -> 캠퍼스·건물·주차장 목록 표시
  -> 각 parkingLot의 partitionKey로 최신 점유 상태 결합
  -> 건물 선택 시 상세 주차장과 슬롯 표시
```

### Spring Boot 처리

- `controller/UiController.java`: 네이버 지도 키와 캠퍼스 기본 정보를 반환한다.
- `controller/CampusController.java`: `/api/campus/map`,
  `/api/campus/buildings/{buildingId}`를 제공한다.
- `service/CampusMapService.java`: DB의 `Campus`, `Building`, `ParkingLot`을
  조회하고 `ParkingStatusService`의 캐시를 `partitionKey`로 결합한다.
- `dto/CampusMapResponse.java`, `BuildingDetailResponse.java`,
  `ParkingLotView.java`: 웹에 전달할 조회 응답 형태를 정의한다.
- `repository/CampusRepository.java`, `BuildingRepository.java`,
  `ParkingLotRepository.java`: 장소 계층을 조회한다.

### 상태 계산

`CampusMapService`는 FastAPI의 상태를 그대로 화면에 전달하지 않고 다음 값을
주차장별로 가공한다.

- `AVAILABLE`: 빈자리가 있는 상태
- `FULL`: 빈자리가 없는 상태
- `NO_DATA`: 아직 분석 결과가 없거나 해당 `partitionKey`를 찾지 못한 상태
- 전체 칸 수, 빈자리 수, 장애인석 빈자리 수
- 슬롯별 `available`/`occupied` 상태와 중심 좌표

## 4. 주차 영상 분석

### 전체 흐름

```text
Spring Boot ParkingStatusService
  -> FastAPI GET /status
       -> ParkingAnalyzer.status_cache 반환

FastAPI 백그라운드 분석 루프
  -> 영상 소스 탐색
  -> manifest의 slotLayoutJson 로딩
  -> 영상 프레임 읽기
  -> YOLO 차량 탐지
  -> 차량 중심점과 슬롯 사각형 비교
  -> 슬롯별 점유 상태 계산
  -> status_cache 갱신
```

### FastAPI 파일

- `fastapi/server0.py`: 실행 진입점이다. `ParkingAnalyzer`와 FastAPI 앱을 만들고, 일반 실행에서는 분석 루프를 시작한다.
- `fastapi/parking_yolo/app.py`: `/health`와 `/status`를 등록한다.
  `start_worker=True`이면 분석 루프를 백그라운드 스레드로 시작한다.
- `fastapi/parking_yolo/config.py`: 모델 경로, 분석 주기, 루프 throttle,
  Docker manifest URL 등의 환경변수를 읽는다.
- `fastapi/parking_yolo/source_discovery.py`: Spring Boot의
  `/api/internal/analysis/sources` manifest에서 분석 대상을 찾는다.
  영상·슬롯 JSON·`partitionKey`를 하나의 소스로 묶는다.
- `fastapi/parking_yolo/analyzer.py`: YOLO를 실행하고 슬롯의 점유
  여부를 계산한 뒤 `status_cache`를 만든다. 차량 클래스는 `vehicle_ids`로
  제한한다.

### 분석 소스 제공 흐름

- Docker Compose에서 FastAPI가 Spring Boot `/api/internal/analysis/sources`를
  조회하고, Spring Boot가 MinIO의 영상을 스트리밍한다.
- 등록 영상은 `StorageService`에 저장되고, DB의
  `parking_lot_assets` 메타데이터를 기준으로 `AnalysisSourceController`가
  FastAPI에 분석 소스를 제공한다.
- FastAPI는 영상을 `/cache` volume에 임시 다운로드한 뒤 분석하며,
  원본 영상은 FastAPI 로컬 폴더에 보관하지 않는다.
- `springboot/.../controller/AnalysisSourceController.java`: Docker 방식에서
  분석 가능한 주차장 목록과 영상을 FastAPI에 제공한다.

## 5. 장소·주차장 등록과 삭제

### 등록 흐름

```text
로그인 사용자
  -> POST /api/buildings
  -> BuildingRegistrationService가 buildings 저장

주차장 등록 폼
  -> POST /api/buildings/{buildingId}/parking-lots
     (name + video + optional image)
  -> parking_lots 저장
  -> 영상·이미지를 StorageService에 저장
  -> parking_lot_assets에 objectKey와 파일 메타데이터 저장
  -> partitionKey를 FastAPI 분석 식별자로 사용
```

### 관련 파일

- `controller/BuildingController.java`: 건물 생성·삭제와 주차장 등록 API를
  받는다.
- `service/BuildingRegistrationService.java`: 캠퍼스 확인, `mapKey`·
  `partitionKey` 생성, DB 저장, 업로드 파일 저장, 삭제 시 연결 asset 정리를
  담당한다.
- `entity/Building.java`, `entity/ParkingLot.java`: 장소와 주차장 테이블이다.
- `entity/ParkingLotAsset.java`, `service/ParkingLotAssetService.java`:
  영상·이미지의 유형, object key, 크기, checksum 등의 메타데이터를 관리한다.
- `static/app.js`: 지도 클릭 등록, 영상 업로드, 삭제 버튼과 목록 갱신을 처리한다.

삭제 시 DB 레코드뿐 아니라 저장소의 영상·이미지 asset도 함께 삭제하고, 사용자
소유 데이터인지 확인하는 보안 검사는 `CurrentUserService`와 각 서비스에서
수행한다.

## 6. 지도 이미지와 주차 슬롯 배치

### 흐름

```text
원본 이미지 업로드
  -> POST /api/parking-lots/{id}/map/upload
  -> StorageService에 SOURCE_IMAGE 저장

웹 편집기에서 슬롯 추가·수정
  -> 사진 위에 슬롯을 클릭하거나 드래그
  -> 크기·각도·슬롯 번호·일반/장애인 유형 수정
  -> POST /api/parking-lots/{id}/map/slots
  -> JSON을 parking_lots.slot_layout_json에 저장
```

### 관련 파일

- `controller/ParkingLotMapController.java`: 주차장 이미지 업로드, 슬롯 JSON
  저장, 원본 이미지 조회 API를 제공한다.
- `service/ParkingLotMapService.java`: 주차장 이미지 asset을 관리하고 웹 편집기가
  보낸 슬롯 JSON을 검증·저장한다.
- `static/app.js`: 웹 슬롯 편집 모달에서 슬롯을 추가·삭제·드래그·수정한다.
- `entity/ParkingLot.java`: FastAPI가 사용할 `partitionKey`와 슬롯 JSON을
  저장한다.

슬롯 JSON이 준비되어야 FastAPI가 해당 영상을 분석 대상으로 등록한다. 영상만
업로드하고 슬롯 배치를 완료하지 않은 주차장은 `NO_DATA`가 될 수 있다.

## 7. 로그인과 인증

```text
회원가입
  -> POST /auth/register
  -> AuthService가 BCrypt로 비밀번호를 해시해 users 저장

로그인
  -> POST /auth/login
  -> AuthService가 비밀번호 검증
  -> JwtUtil이 JWT 발급
  -> app.js가 localStorage의 smartparking_token에 저장

인증 API 호출
  -> Authorization: Bearer {token}
  -> JwtAuthenticationFilter 검증
  -> Principal.getName()으로 현재 username 확인
```

### 관련 파일

- `controller/AuthController.java`: 회원가입, 로그인, 현재 사용자 조회 API다.
- `service/AuthService.java`: 사용자 조회·등록·비밀번호 검증을 담당한다.
- `service/JwtUtil.java`: JWT 생성과 검증을 담당한다.
- `security/JwtAuthenticationFilter.java`: 요청마다 토큰을 검사한다.
- `config/SecurityConfig.java`: 지도 조회·음성 질의는 공개하고 등록·삭제와
  `/api/me/**`는 인증이 필요하도록 설정한다.
- `entity/User.java`, `repository/UserRepository.java`: 사용자 DB 모델과
  조회를 담당한다.
- `static/app.js`: 토큰을 보관하고 로그인 상태에 따라 사용자 패널을 갱신한다.

현재 즐겨찾기는 서버 DB가 아니라 `app.js`의 사용자명별 `localStorage` 키를
사용한다. 따라서 같은 브라우저에서는 계정별로 분리되지만, 다른 기기와는
동기화되지 않는다.

## 8. 음성·텍스트 주차 질의

### 브라우저부터 답변까지

```text
텍스트 입력 또는 브라우저 SpeechRecognition
  -> app.js가 질문 문자열 확보
  -> POST /api/voice/ask { question }
  -> VoiceAnswerService가 현재 캠퍼스 현황 조회
  -> 규칙 기반으로 숫자·장소가 정확한 답변 초안 생성
  -> GeminiClient 호출
       실패 또는 빈 응답이면 LocalLlmClient(Ollama) 호출
       둘 다 실패하면 규칙 기반 초안 반환
  -> app.js가 답변 표시
  -> SpeechSynthesis가 한국어로 읽기
```

### 관련 파일

- `static/app.js`: `SpeechRecognition`/`webkitSpeechRecognition`으로 음성을
  텍스트로 바꾸고, `/api/voice/ask`를 호출한다. 결과는 입력창과 화면에
  표시하며 `SpeechSynthesisUtterance`로 답변을 읽는다.
- `controller/VoiceController.java`: 질문 DTO를 검증하고 답변을 반환한다.
- `dto/VoiceAskRequest.java`, `VoiceAskResponse.java`: 질문과 답변 JSON 형식이다.
- `service/VoiceAnswerService.java`: 현황을 수치로 계산하고 자연어 답변을
  생성한다. 장소명과 숫자가 LLM에 의해 바뀌지 않도록 규칙 기반 초안을 함께
  프롬프트에 전달한다.
- `service/GeminiClient.java`: Gemini API 호출을 담당한다.
- `service/LocalLlmClient.java`: Gemini 대체 경로로 Ollama를 호출한다.
- `service/CampusMapService.java`: 음성 답변에 필요한 최신 주차 현황을 제공한다.

음성인식 자체는 브라우저 기능이므로 Chrome/Edge, HTTPS 또는 localhost,
사이트 마이크 권한이 필요하다. 음성인식이 실패해도 텍스트 질문 API는 별도로
정상 동작할 수 있다.

## 9. 장소 검색

```text
검색창 입력
  -> GET /api/geo/search?query=...
  -> NaverSearchService
  -> 네이버 지역검색 API
  -> HTML 태그 제거 및 좌표 변환
  -> app.js가 검색 결과와 지도 위치 표시
```

- `controller/GeoSearchController.java`: 검색 API 진입점이다.
- `service/NaverSearchService.java`: 네이버 API 인증 헤더, 호출, 결과 정제를
  담당한다.
- `dto/NaverLocalSearchResponse.java`: 네이버 원본 응답 DTO다.
- `dto/GeoSearchResult.java`: 웹에 전달할 장소명·주소·좌표 DTO다.
- `static/app.js`: 검색 결과 선택과 지도 중심 이동을 담당한다.

## 10. 내 주차 위치

```text
주차장 상세에서 슬롯 선택
  -> POST /api/me/parking-location
  -> ParkingLocationService가 기존 active 위치를 해제
  -> saved_parking_locations에 새 위치 저장
  -> GET /api/me/parking-location/current로 현재 위치 표시
  -> 해제 시 DELETE /api/me/parking-location/current
```

- `controller/MeParkingLocationController.java`: 저장·조회·해제 API다.
- `service/ParkingLocationService.java`: username으로 사용자를 찾고, 한 사용자당
  활성 위치가 하나가 되도록 기존 위치를 비활성화한다.
- `entity/SavedParkingLocation.java`: 사용자, 주차장, 슬롯, 차량 라벨, 메모,
  저장·해제 시각을 저장한다.
- `repository/SavedParkingLocationRepository.java`: 현재 활성 위치 조회를
  담당한다.
- `static/app.js`: 로그인 사용자의 패널과 슬롯 저장 버튼을 갱신한다.

## 11. 빈자리 알림과 알림함

### 알림 규칙 설정

```text
사용자가 주차장과 최소 빈자리 수 설정
  -> POST /api/me/alert-rules
  -> ParkingAlertRuleService가 parking_alert_rules 저장
  -> 15초 주기 monitor가 현재 캐시와 비교
  -> 조건 충족 시 InAppNotificationService가 알림 저장
  -> 웹이 /api/me/notifications로 목록 조회
```

### 관련 파일

- `controller/MeAlertRuleController.java`: 규칙 생성·목록·활성화 변경·삭제 API다.
- `service/ParkingAlertRuleService.java`: 사용자별 규칙을 관리한다.
- `service/ParkingAlertMonitorService.java`: `ParkingStatusService`의 현재 상태와
  규칙을 비교한다.
- `service/InAppNotificationService.java`: 알림 생성, 목록, 미읽음 수, 읽음
  처리를 담당한다.
- `controller/MeNotificationController.java`: 알림함 API다.
- `entity/ParkingAlertRule.java`, `InAppNotification.java`: 각각 규칙과 발송된
  알림 테이블이다.
- `static/app.js`: 알림 목록, 미읽음 배지, 읽음 처리를 화면에 반영한다.

## 12. 파일 저장소와 MinIO

```text
업로드 요청
  -> StorageService.put(objectKey, stream, size, contentType)
  -> MinioStorageService -> MinIO bucket
  -> DB parking_lot_assets에 objectKey와 메타데이터 저장
```

- `service/storage/StorageService.java`: 저장소 추상화 인터페이스다.
- `service/storage/MinioStorageService.java`: Docker 환경에서 MinIO에 저장한다.
- `config/MinioConfig.java`: MinIO client와 설정을 구성한다.
- `entity/ParkingLotAsset.java`: 실제 파일이 아니라 파일의 유형, 경로, 크기,
  MIME 타입, checksum을 DB에 저장한다.
- `controller/AnalysisSourceController.java`: FastAPI가 Docker 내부에서
  영상을 읽을 수 있도록 저장소 영상을 HTTP 스트림으로 제공한다.

## 13. 기능별 파일 찾기 요약

| 기능 | Spring Boot 진입점 | 핵심 서비스 | FastAPI/외부 연동 |
|---|---|---|---|
| 지도·현황 조회 | `CampusController` | `CampusMapService`, `ParkingStatusService` | FastAPI `/status` |
| 주차 분석 | `ParkingStatusController` | `ParkingStatusService` | `server0.py`, `app.py`, `analyzer.py` |
| 건물·주차장 등록 | `BuildingController` | `BuildingRegistrationService` | StorageService |
| 이미지·슬롯 맵 | `ParkingLotMapController` | `ParkingLotMapService` | 브라우저 웹 슬롯 편집기 |
| 로그인 | `AuthController` | `AuthService`, `JwtUtil` | 없음 |
| 음성·텍스트 질의 | `VoiceController` | `VoiceAnswerService` | Gemini, Ollama, 브라우저 Speech API |
| 장소 검색 | `GeoSearchController` | `NaverSearchService` | 네이버 Local Search API |
| 내 주차 위치 | `MeParkingLocationController` | `ParkingLocationService` | 없음 |
| 빈자리 알림 | `MeAlertRuleController` | `ParkingAlertRuleService`, `ParkingAlertMonitorService` | FastAPI 캐시 |
| 알림함 | `MeNotificationController` | `InAppNotificationService` | 없음 |
| 분석 파일 제공 | `AnalysisSourceController` | `ParkingLotAssetService`, `StorageService` | FastAPI `source_discovery.py` |

## 14. 기능을 따라갈 때의 권장 읽기 순서

1. `static/index.html`에서 사용자가 조작하는 화면을 확인한다.
2. `static/app.js`에서 해당 버튼이 호출하는 API를 찾는다.
3. 같은 경로의 `controller/`에서 요청 진입점을 찾는다.
4. `service/`에서 DB·캐시·외부 API 처리 순서를 확인한다.
5. 저장·조회 대상은 `entity/`와 `repository/`에서 확인한다.
6. 주차 상태 기능은 마지막으로 FastAPI의 `source_discovery.py`와
   `analyzer.py`를 확인한다.
