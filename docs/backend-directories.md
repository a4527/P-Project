# 현재 프로젝트 구조와 백엔드 흐름

이 문서는 현재 코드 기준으로 `fastapi/`, `springboot/`, Docker Compose,
테스트 구조와 기능별 흐름을 정리한다.

## 1. 실행 구조

```text
docker compose up --build
        │
        ├─ springboot :8080
        │    ├─ PostgreSQL :5432
        │    ├─ MinIO :9000 / Console :9001
        │    ├─ FastAPI :8000
        │    └─ Ollama :11434
        │
        └─ fastapi :8000
             └─ Spring Boot /api/internal/analysis/sources
```

Spring Boot가 웹 UI, REST API, DB, MinIO, 외부 API 연동을 담당한다.
FastAPI는 Spring Boot가 제공하는 분석 manifest에서 영상을 받아 YOLO 분석만 수행한다.

## 2. 루트 구성

```text
P-Project/
├─ docker-compose.yml             전체 서비스 실행 및 volume 설정
├─ .env.example                   Docker 환경변수 예시
├─ README.md                      설치·실행·DB 조회 요약
├─ fastapi/                       YOLO 분석 서버
├─ springboot/                    웹/API 서버
└─ docs/
   ├─ backend-directories.md      현재 백엔드 구조 문서
   ├─ feature-flows.md            기능별 상세 흐름
   ├─ report-src.md               프로젝트 보고서 원문
   └─ my-backend-role-summary.md  백엔드 역할 요약
```

## 3. FastAPI 구조

```text
fastapi/
├─ Dockerfile
├─ .dockerignore
├─ requirements.txt
├─ server0.py
├─ parking_yolo/
│  ├─ __init__.py
│  ├─ app.py
│  ├─ analyzer.py
│  ├─ config.py
│  ├─ source_discovery.py
│  └─ visualization.py
└─ weights/
   └─ visDrone.pt                 (별도 준비가 필요한 YOLO 가중치)
```

### 파일별 역할

| 파일 | 역할 |

| `server0.py` | FastAPI 앱과 `ParkingAnalyzer`를 생성하는 실행 진입점 |
| `parking_yolo/app.py` | `/health`, `/status` API와 분석 worker등록 |
| `parking_yolo/config.py` | 모델 경로, 분석 주기, CPU 스레드 수, manifest URL, cache 설정 |
| `parking_yolo/source_discovery.py` | Spring Boot manifest 조회, 영상 다운로드, 슬롯 JSON 파싱 |
| `parking_yolo/analyzer.py` | 영상 프레임 YOLO 추론, 차량 중심점과 슬롯 영역 비교, 상태 cache 생성 |
| `requirements.txt` | FastAPI, Uvicorn, Ultralytics, OpenCV, NumPy 의존성 |
| `Dockerfile` | FastAPI 이미지 빌드와 Python 의존성 설치 |

### 영상 분석 흐름

```text
FastAPI 시작
  -> YOLO 모델 로드
  -> GET http://springboot:8080/api/internal/analysis/sources
  -> manifest의 영상 URL을 /cache에 다운로드
  -> manifest의 slotLayoutJson 파싱
  -> 프레임 읽기
  -> 일정 시간 간격마다 YOLO 차량 탐지
  -> 차량 중심점이 슬롯 사각형 안에 있는지 판단
  -> partitionKey별 summary와 slots 생성
  -> GET /status에서 최신 cache 반환
```

기본 분석 설정은 다음과 같다.

| 설정 | 기본값 | 의미 |
|---|---:|---|
| `SMARTPARKING_ANALYSIS_INTERVAL` | `10.0` | 같은 영상의 실제 YOLO 분석 최소 간격(초) |
| `SMARTPARKING_LOOP_INTERVAL` | `0.05` | 분석 루프 throttle(초) |
| `SMARTPARKING_TORCH_THREADS` | `2` | PyTorch CPU 스레드 수 |
| `SMARTPARKING_FRAME_INTERVAL` | `30` | 분석 후보 프레임을 건너뛰는 보조 조건 |
| `SMARTPARKING_ANALYSIS_CACHE_DIR` | `/cache`(Docker) | 다운로드 영상 임시 저장 위치 |

`images/`, `map/`, `videos/` 폴더를 직접 읽는 로직은 없다. 영상과 슬롯 정보는
Spring Boot manifest를 통해 전달된다.

## 4. Spring Boot 구조

```text
springboot/
├─ build.gradle
├─ settings.gradle
├─ gradlew
├─ src/
│  ├─ main/
│  │  ├─ java/com/smartparking/server/
│  │  │  ├─ ServerApplication.java
│  │  │  ├─ config/
│  │  │  ├─ controller/
│  │  │  ├─ dto/
│  │  │  ├─ entity/
│  │  │  ├─ repository/
│  │  │  ├─ security/
│  │  │  └─ service/
│  │  └─ resources/
│  │     ├─ application.properties
│  │     └─ static/
│  └─ test/java/com/smartparking/server/
└─ Dockerfile
```

### 공통 계층 역할

| 계층 | 위치 | 역할 |
|---|---|---|
| 설정 | `config/` | DB 외부 서비스, MinIO, 보안, 초기 데이터 설정 |
| Controller | `controller/` | HTTP 요청 경로와 인증 진입점 |
| DTO | `dto/` | API 요청·응답 형식 |
| Entity | `entity/` | PostgreSQL 테이블과 관계 모델 |
| Repository | `repository/` | Spring Data JPA DB 접근 |
| Security | `security/` | JWT 요청 검증 |
| Service | `service/` | 기능별 업무 로직과 외부 시스템 연동 |
| Static | `resources/static/` | 웹 HTML, JavaScript, CSS |

## 5. 설정·보안 파일

- `ServerApplication.java`: Spring Boot 시작 및 `@EnableScheduling` 적용.
- `application.properties`: 유일한 Spring 설정 파일. PostgreSQL, MinIO,
  FastAPI, Ollama, 네이버 API, Gemini, JWT 설정을 환경변수로 받는다.
- `MinioConfig.java`: MinIO client Bean을 생성한다.
- `WebClientConfig.java`: FastAPI, Gemini, Ollama, 네이버 호출용 WebClient를 구성한다.
- `CampusDataInitializer.java`: DB에 캠퍼스가 없을 때 기본 캠퍼스를 생성한다.
- `SecurityConfig.java`: 조회·음성 API는 공개하고, 등록·삭제·`/api/me/**`는
  JWT 인증을 요구한다.
- `JwtAuthenticationFilter.java`: `Authorization: Bearer` 토큰을 검증한다.
- `JwtUtil.java`: JWT 발급과 검증을 담당한다.
- `CurrentUserService.java`: 현재 인증된 사용자를 조회한다.

## 6. Controller와 API

| Controller | 주요 경로 | 기능 |
|---|---|---|
| `AuthController` | `/auth/*` | 회원가입, 로그인, 현재 사용자 |
| `CampusController` | `/api/campus/*` | 캠퍼스 지도, 건물 상세 조회 |
| `UiController` | `/api/ui/config` | 웹 UI와 네이버 지도 설정 |
| `BuildingController` | `/api/buildings/*` | 건물 등록·삭제, 주차장 영상 등록 |
| `ParkingLotController` | `/api/parking-lots/{id}` | 주차장 삭제 |
| `ParkingLotMapController` | `/api/parking-lots/{id}/map/*` | 이미지 업로드, 웹 슬롯 JSON 저장, 이미지 조회 |
| `ParkingStatusController` | `/api/parking/status` | Spring Boot가 cache한 주차 상태 조회 |
| `AnalysisSourceController` | `/api/internal/analysis/*` | FastAPI용 영상 manifest·영상 stream |
| `GeoSearchController` | `/api/geo/search` | 네이버 장소 검색 |
| `VoiceController` | `/api/voice/ask` | 텍스트 질문에 대한 주차 답변 |
| `MeParkingLocationController` | `/api/me/parking-location/*` | 사용자 현재 주차 위치 |
| `MeAlertRuleController` | `/api/me/alert-rules/*` | 빈자리 알림 규칙 |
| `MeNotificationController` | `/api/me/notifications/*` | 알림 목록·읽음 처리 |

## 7. Service 구성

### 장소·주차장 조회

- `CampusMapService`: 캠퍼스·건물·주차장을 DB에서 읽고 주차 상태 cache와
  슬롯 레이아웃을 합쳐 웹 응답을 만든다.
- `ParkingStatusService`: 5초마다 FastAPI `/status`를 호출해 memory cache에
  저장한다. FastAPI 장애 시 기존 cache를 유지한다.
- `ParkingLotMapService`: 주차장 원본 이미지 asset과 웹 슬롯 JSON을 저장·조회한다.

### 등록·파일·분석 manifest

- `BuildingRegistrationService`: 건물과 주차장을 생성·삭제하고 영상·이미지를
  MinIO에 저장하며 `parking_lot_assets` 메타데이터를 관리한다.
- `ParkingLotAssetService`: asset 유형, object key, 파일 크기와 업로더를 관리한다.
- `StorageService`: 저장소 추상화 인터페이스다.
- `MinioStorageService`: Docker MinIO에 파일을 저장·조회·삭제한다.
- `AnalysisSourceController`: `slot_layout_json`과 VIDEO asset이 모두 있는
  주차장만 FastAPI manifest에 포함한다.

### 인증·검색·음성

- `AuthService`: BCrypt 비밀번호 처리와 사용자 조회.
- `NaverSearchService`: 네이버 Local Search 호출과 좌표 변환.
- `VoiceAnswerService`: 현재 주차 상태에서 숫자가 정확한 답변 초안을 만든 뒤
  LLM에 자연어 표현을 요청한다.
- `GeminiClient`: Gemini 호출.
- `LocalLlmClient`: Gemini 실패 시 Ollama 호출.

### 사용자 기능·스케줄러

- `ParkingLocationService`: 사용자별 현재 주차 위치를 저장하고 기존 active
  위치를 해제한다.
- `ParkingAlertRuleService`: 사용자별 빈자리 알림 조건을 관리한다.
- `ParkingAlertMonitorService`: 15초마다 cache와 알림 조건을 비교한다.
- `InAppNotificationService`: 알림 생성, 목록, 미읽음 수, 읽음 처리를 담당한다.

## 8. 웹 슬롯 편집 흐름

```text
웹에서 이미지 업로드
  -> POST /api/parking-lots/{id}/map/upload
  -> MinIO SOURCE_IMAGE 저장

웹 편집 버튼
  -> 이미지 위에 슬롯 추가·삭제·드래그
  -> 크기·각도·번호·일반/장애인 유형 설정
  -> POST /api/parking-lots/{id}/map/slots
  -> parking_lots.slot_layout_json 저장
  -> FastAPI가 다음 manifest 조회 때 분석에 사용
```

웹 편집기는 `static/app.js`의 `openSlotEditor()`와 관련 CSS로 구현되어 있다.

## 9. PostgreSQL Entity 관계

```text
Campus 1 ─── N Building 1 ─── N ParkingLot 1 ─── N ParkingLotAsset
                         │              │
                         │              ├── N SavedParkingLocation
                         │              └── N ParkingAlertRule
                         │
                         └── created_by_user -> User

User 1 ─── N SavedParkingLocation
User 1 ─── N ParkingAlertRule
User 1 ─── N InAppNotification
User 0..1 ─── N Building/ParkingLot/ParkingLotAsset (등록·업로드 사용자)
```

주요 Entity:

- `Campus`, `Building`, `ParkingLot`: 장소 계층.
- `ParkingLotAsset`: VIDEO, SOURCE_IMAGE asset의 MinIO 메타데이터.
- `User`: 로그인 사용자.
- `SavedParkingLocation`: 사용자 현재 주차 위치.
- `ParkingAlertRule`: 사용자별 빈자리 알림 규칙.
- `InAppNotification`: 사용자 알림함.

자세한 Mermaid ERD와 PostgreSQL 조회 SQL은 [README의 DB 섹션](../README.md#db-확인-방법)을 참고한다.

## 10. Docker Compose 서비스

| 서비스 | 역할 | 주요 volume/포트 |
|---|---|---|
| `springboot` | 웹/API 서버 | `8080:8080` |
| `fastapi` | YOLO 분석 서버 | `8000:8000`, `/weights`, `/cache` |
| `postgres` | PostgreSQL | `5432:5432`, `postgres-data` |
| `minio` | 파일 저장소 | `9000:9000`, `9001:9001`, `minio-data` |
| `ollama` | 로컬 LLM 서버 | `11434:11434`, `ollama-data` |
| `ollama-pull` | `qwen2.5:1.5b` 초기 다운로드 | 일회성 compose 작업 |

실행:

```bash
docker compose up --build
```

종료:

```bash
docker compose down
```

데이터 volume까지 삭제하려면 별도로 `docker compose down -v`를 실행한다.

## 11. 테스트 구조

```text
springboot/src/test/java/com/smartparking/server/
├─ TestcontainersConfiguration.java
├─ ServerApplicationTests.java
├─ controller/
│  ├─ BuildingControllerTest.java
│  └─ VoiceControllerTest.java
└─ service/
   ├─ BuildingRegistrationServiceTest.java
   └─ VoiceAnswerServiceTest.java
```

- Spring Boot 통합 테스트는 `TestcontainersConfiguration`의 PostgreSQL
  container를 사용한다.
- `VoiceAnswerServiceTest`는 외부 API를 호출하지 않고 Gemini/Ollama client를
  mock으로 대체한다.
- 테스트 실행에도 Docker가 필요하다.

```bash
cd springboot
./gradlew test
```
