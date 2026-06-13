# P-Project

YOLO 기반 영상 분석 기술을 활용한 주차 점유 감지 시스템입니다.

현재 구조는 아래 2개 프로젝트로 나뉩니다.

- `fastapi/`: YOLO 추론 서버, 실시간 점유 계산, `map_builder_gui0.py`
- `springboot/`: 캠퍼스/건물/주차장 API, 주차장별 맵 제작 연동, 최소 확인용 웹 UI

## 실행 순서

1. FastAPI 실행 준비

```bash
cd fastapi/video_test
python3 -m venv venv
source venv/bin/activate
pip install -r ../../requirements.txt
python -m uvicorn server0:app --host 0.0.0.0 --port 8000
```

2. Spring Boot 실행

```bash
cd springboot
./gradlew bootRun
```

3. 브라우저 확인

- `http://localhost:8080/`

## 환경변수

필요한 경우에만 설정합니다.

```bash
SMARTPARKING_YOLO_BASE_URL=http://localhost:8000
SMARTPARKING_NAVER_MAP_CLIENT_ID=your_naver_maps_client_id
SMARTPARKING_JWT_SECRET=smartparking-dev-secret-smartparking-dev-secret
SMARTPARKING_JWT_EXPIRATION_MS=3600000
```

- `SMARTPARKING_YOLO_BASE_URL`
  - FastAPI `/status`를 가져올 주소
- `SMARTPARKING_NAVER_MAP_CLIENT_ID`
  - 네이버 지도 표시용 Client ID
- `SMARTPARKING_JWT_SECRET`
  - 로그인 JWT 서명용 고정 비밀키
- `SMARTPARKING_JWT_EXPIRATION_MS`
  - JWT 만료 시간

## Spring Boot API

- `GET /api/parking/status`
- `GET /api/campus/map`
- `GET /api/campus/buildings/{buildingId}`
- `GET /api/parking-lots/{parkingLotId}/map`
- `GET /api/parking-lots/{parkingLotId}/map/source-image`
- `GET /api/parking-lots/{parkingLotId}/map/generated-image`
- `POST /api/parking-lots/{parkingLotId}/map/upload`
- `POST /api/parking-lots/{parkingLotId}/map/build`
- `GET /api/ui/config`
- `POST /auth/register`
- `POST /auth/login`
- `GET /auth/me`
- `GET /api/me/parking-location/current`
- `POST /api/me/parking-location`
- `DELETE /api/me/parking-location/current`
- `GET /api/me/notifications`
- `GET /api/me/notifications/unread-count`
- `PATCH /api/me/notifications/{notificationId}/read`
- `GET /api/me/alert-rules`
- `POST /api/me/alert-rules`
- `PUT /api/me/alert-rules/{ruleId}`
- `DELETE /api/me/alert-rules/{ruleId}`

## API Reference

이 섹션은 프로젝트 안에 존재하는 API를 기준으로 정리합니다. 각 API마다 `반환 형식`, `어디서 쓰는지`, `내부 흐름`을 적었습니다.

### FastAPI API

#### `GET /status`

- 반환 형식: `ParkingStatusResponse`
- 어디서 쓰는지:
  - Spring Boot의 `ParkingStatusService`가 5초마다 폴링합니다.
  - `CampusMapService`가 건물/주차장 응답을 만들 때 사용합니다.
  - 프론트는 Spring Boot API를 통해 간접적으로 이 값을 봅니다.
- 내부 흐름:
  - `server0.py`가 영상 파일과 슬롯 JSON을 자동 스캔합니다.
  - YOLO가 각 프레임에서 차량을 탐지합니다.
  - 주차 슬롯 내부 점유 여부를 계산합니다.
  - `last_update`와 각 파티션별 `summary/slots`를 하나의 JSON으로 반환합니다.

### Spring Boot API

#### `GET /api/parking/status`

- 반환 형식: `ParkingStatusResponse`
- 어디서 쓰는지:
  - 점유 상태 원본 조회용입니다.
  - 디버깅이나 상태 확인에 사용됩니다.
- 내부 흐름:
  - `ParkingStatusService.cachedStatus`를 읽습니다.
  - 캐시가 없으면 `204 No Content`를 반환합니다.
  - 캐시가 있으면 그대로 반환합니다.

#### `GET /api/ui/config`

- 반환 형식: `UiConfigResponse`
- 어디서 쓰는지:
  - `app.js`가 최초 부팅할 때 가장 먼저 호출합니다.
  - 네이버 지도 로딩과 캠퍼스 기본 정보 표시용입니다.
- 내부 흐름:
  - `CampusMapService.getUiConfig()`가 먼저 filesystem 동기화를 수행합니다.
  - `smartparking.naver-map.client-id`와 기본 캠퍼스 정보를 합쳐 반환합니다.

#### `GET /api/campus/map`

- 반환 형식: `CampusMapResponse`
- 어디서 쓰는지:
  - `app.js`가 첫 화면에서 건물 목록과 캠퍼스 지도를 그릴 때 사용합니다.
- 내부 흐름:
  - `ParkingLotAssetSyncService.syncFromFilesystem()`를 먼저 호출합니다.
  - 기본 캠퍼스를 찾고, 캠퍼스에 속한 건물을 순서대로 조회합니다.
  - 각 건물의 주차장 요약을 붙여서 반환합니다.

#### `GET /api/campus/buildings/{buildingId}`

- 반환 형식: `BuildingDetailResponse`
- 어디서 쓰는지:
  - 사용자가 건물을 클릭했을 때 건물 상세 패널을 채우는 용도입니다.
  - `app.js`가 주차장 카드 전체를 다시 그릴 때 사용합니다.
- 내부 흐름:
  - filesystem 동기화를 다시 수행합니다.
  - 건물을 찾습니다.
  - 해당 건물의 주차장 목록을 조회합니다.
  - 각 주차장에 대해 `ParkingLotView`를 만들고 반환합니다.

#### `GET /api/parking-lots/{parkingLotId}/map`

- 반환 형식: `ParkingLotMapResponse`
- 어디서 쓰는지:
  - 주차장 카드의 현재 맵 상태 표시용입니다.
  - 사진 업로드 전후 상태를 UI가 판단할 때 사용합니다.
- 내부 흐름:
  - 주차장 ID로 DB에서 레코드를 찾습니다.
  - 로컬 파일 존재 여부를 확인합니다.
  - 원본 사진과 생성 맵의 존재 여부를 응답에 포함합니다.

#### `GET /api/parking-lots/{parkingLotId}/map/source-image`

- 반환 형식: `image/png`
- 어디서 쓰는지:
  - 프론트가 주차장 배경 사진을 보여줄 때 사용합니다.
- 내부 흐름:
  - `fastapi/video_test/images/{partitionKey}_image.png`를 읽습니다.
  - 파일이 없으면 `404`를 반환합니다.

#### `GET /api/parking-lots/{parkingLotId}/map/generated-image`

- 반환 형식: `image/png`
- 어디서 쓰는지:
  - 프론트가 완성된 주차장 맵을 보여줄 때 사용합니다.
- 내부 흐름:
  - `fastapi/video_test/map/{partitionKey}_map.png`를 읽습니다.
  - 파일이 없으면 `404`를 반환합니다.

#### `POST /api/parking-lots/{parkingLotId}/map/upload`

- 반환 형식: `ParkingLotMapResponse`
- 요청 형식: `multipart/form-data`
- 필드:
  - `file`
- 어디서 쓰는지:
  - 사용자가 주차장 배경 사진을 업로드할 때 사용합니다.
- 내부 흐름:
  - 업로드 파일이 이미지인지 검사합니다.
  - `fastapi/video_test/images/{partitionKey}_image.png`에 저장합니다.
  - 기존 generated map과 slot JSON을 삭제합니다.
  - 다시 맵 제작 전 상태로 되돌립니다.

#### `POST /api/parking-lots/{parkingLotId}/map/build`

- 반환 형식: `ParkingLotMapResponse`
- 어디서 쓰는지:
  - 사용자가 `지도 제작하기` 버튼을 눌렀을 때 사용합니다.
- 내부 흐름:
  - 원본 이미지가 있는지 확인합니다.
  - `fastapi/map_builder/map_builder_gui0.py`를 로컬 프로세스로 실행합니다.
  - 맵 빌더에서 저장하면 JSON과 맵 이미지가 생성됩니다.

#### `POST /auth/register`

- 반환 형식: `String`
- 예시:
  - `REGISTER_SUCCESS`
  - `USER_EXISTS`
- 어디서 쓰는지:
  - 로그인 화면의 `회원가입` 버튼입니다.
- 내부 흐름:
  - username 중복을 검사합니다.
  - password를 BCrypt로 해시합니다.
  - `users` 테이블에 저장합니다.

#### `POST /auth/login`

- 반환 형식: `LoginResponse`
- 어디서 쓰는지:
  - 로그인 화면의 `로그인` 버튼입니다.
- 내부 흐름:
  - username으로 사용자를 찾습니다.
  - password를 BCrypt로 검증합니다.
  - JWT를 생성해서 반환합니다.
  - 프론트는 이 토큰을 `localStorage`에 저장합니다.

#### `GET /auth/me`

- 반환 형식: `LoginResponse`
- 어디서 쓰는지:
  - 페이지 새로고침 후 로그인 상태 복원용입니다.
- 내부 흐름:
  - `Authorization: Bearer ...` 헤더를 검사합니다.
  - 토큰이 유효하면 현재 사용자명과 새 토큰 정보를 반환합니다.

#### `GET /api/me/parking-location/current`

- 반환 형식: `ParkingLocationResponse` 또는 `204 No Content`
- 어디서 쓰는지:
  - 현재 저장된 주차 위치를 사이드바에 보여줄 때 사용합니다.
- 내부 흐름:
  - 로그인한 사용자의 활성 주차 위치를 찾습니다.
  - 없으면 `204`를 반환합니다.

#### `POST /api/me/parking-location`

- 반환 형식: `ParkingLocationResponse`
- 요청 형식:

```json
{
  "parkingLotId": 1,
  "slotId": 3,
  "vehicleLabel": "My Car",
  "memo": "학생회관 근처"
}
```

- 어디서 쓰는지:
  - 슬롯을 선택한 뒤 `내 위치 저장` 버튼을 누를 때 사용합니다.
- 내부 흐름:
  - 기존 활성 위치를 비활성화합니다.
  - 새로운 `SavedParkingLocation`을 저장합니다.

#### `DELETE /api/me/parking-location/current`

- 반환 형식: `ParkingLocationResponse` 또는 `204 No Content`
- 어디서 쓰는지:
  - `주차 종료` 버튼입니다.
- 내부 흐름:
  - 현재 활성 주차 위치를 찾아 비활성화합니다.
  - 해제 시각을 기록합니다.

#### `GET /api/me/notifications`

- 반환 형식: `List<InAppNotificationResponse>`
- 어디서 쓰는지:
  - 알림 패널 목록 렌더링용입니다.
- 내부 흐름:
  - 로그인한 사용자의 알림을 최신 순으로 조회합니다.

#### `GET /api/me/notifications/unread-count`

- 반환 형식: `UnreadCountResponse`
- 어디서 쓰는지:
  - 헤더의 알림 배지 숫자 표시용입니다.
- 내부 흐름:
  - 읽지 않은 알림 개수를 계산해서 반환합니다.

#### `PATCH /api/me/notifications/{notificationId}/read`

- 반환 형식: `InAppNotificationResponse`
- 어디서 쓰는지:
  - 사용자가 알림을 클릭해서 읽음 처리할 때 사용합니다.
- 내부 흐름:
  - 알림 소유자가 현재 사용자와 같은지 확인합니다.
  - `readFlag=true`로 바꾸고 읽은 시각을 기록합니다.

#### `GET /api/me/alert-rules`

- 반환 형식: `List<ParkingAlertRuleResponse>`
- 어디서 쓰는지:
  - 사용자가 등록한 알림 규칙 목록을 보여줄 때 사용합니다.
- 내부 흐름:
  - 로그인한 사용자의 알림 규칙을 최신 순으로 조회합니다.

#### `POST /api/me/alert-rules`

- 반환 형식: `ParkingAlertRuleResponse`
- 요청 형식:

```json
{
  "parkingLotId": 1,
  "minimumAvailableSlots": 5,
  "enabled": true
}
```

- 어디서 쓰는지:
  - `알림 등록` 버튼입니다.
- 내부 흐름:
  - 대상 주차장을 찾습니다.
  - 규칙을 저장합니다.

#### `PUT /api/me/alert-rules/{ruleId}`

- 반환 형식: `ParkingAlertRuleResponse`
- 어디서 쓰는지:
  - 알림 규칙 활성화/비활성화 전환용입니다.
- 내부 흐름:
  - 규칙 소유자를 검증합니다.
  - `enabled` 값을 바꿉니다.

#### `DELETE /api/me/alert-rules/{ruleId}`

- 반환 형식: `204 No Content`
- 어디서 쓰는지:
  - 알림 규칙 삭제용입니다.
- 내부 흐름:
  - 규칙 소유자를 검증합니다.
  - DB에서 삭제합니다.

## Web UI 흐름

1. 사용자가 캠퍼스 지도에서 건물을 선택합니다.
2. 각 건물에 속한 주차장 카드가 렌더링됩니다.
3. 로그인 후에는 상단 로그인 패널에서 JWT를 저장하고, 앱 내부 알림과 현재 주차 위치를 확인할 수 있습니다.
4. 각 주차장은 자신의 `partitionKey`를 기준으로 원본 사진과 슬롯 레이아웃을 읽습니다.
5. 사진이 없으면 업로드와 `지도 제작하기` 버튼이 표시됩니다.
6. 사진이 있으면 흐리게 깐 바탕 위에 슬롯 사각형이 실제 위치대로 오버레이됩니다.
7. 슬롯을 클릭한 뒤 `내 위치 저장`으로 주차 위치를 저장하고, `주차 종료`로 해제할 수 있습니다.
8. `알림 등록`을 누르면 현재 주차장 기준의 앱 내부 빈자리 알림 규칙이 저장됩니다.
9. 업로드된 사진은 로컬에 저장됩니다.
   - `fastapi/video_test/images/{partitionKey}_image.png`
10. `지도 제작하기`를 누르면 `fastapi/map_builder/map_builder_gui0.py`가 로컬에서 실행됩니다.
11. 맵 빌더 단축키는 다음과 같습니다.
   - `s`: 저장 후 종료
   - `d`: 선택한 슬롯 삭제
   - `q`: 종료
12. 저장이 끝나면 결과가 다시 조회됩니다.
   - `fastapi/video_test/map/{partitionKey}_map.png`
   - `fastapi/video_test/map/{partitionKey}_slots.json`

## FastAPI 파일 규칙

- `server0.py`는 `fastapi/video_test/videos/*_video.mp4`를 자동 스캔합니다.
- 같은 prefix의 `fastapi/video_test/map/*_slots.json`을 자동 매칭합니다.
- 파일 prefix가 곧 주차장 식별자입니다.
  - 예: `gachon_ai_1`, `gachon_ai_2`, `gachon_dorm1_1`, `gachon_dorm3_1`
- 건물 식별자는 `mapKey`입니다.
  - 예: `gachon_ai`, `gachon_library`, `gachon_dorm1`, `gachon_dorm3`

## 주차장 자동 생성 규칙

- Spring Boot는 `fastapi/video_test/videos/{partitionKey}_video*.mp4`가 있으면 해당 `ParkingLot` 레코드를 자동 생성합니다.
- `partitionKey`가 `gachon_ai_1`처럼 숫자 접미사를 가지면 상위 `Building.mapKey`는 `gachon_ai`로 추론합니다.
- 이미지, 맵, 슬롯 JSON은 나중에 업로드/제작되면 카드 안에 반영됩니다.

## 확인용 요청

```bash
curl http://localhost:8080/api/campus/map
curl http://localhost:8080/api/campus/buildings/1
curl http://localhost:8080/api/parking-lots/1/map
curl http://localhost:8080/
```

## Notes

- `Building.description`은 제거되었습니다.
- 프론트는 건물명, 건물 `mapKey`, 주차장 `partitionKey`, 좌표, 주차장 상태, 로그인 사용자 상태, 앱 내부 알림, 주차 위치 저장 상태를 사용합니다.
- H2 메모리 DB를 쓰므로 Spring Boot를 재시작하면 시드 데이터가 다시 생성됩니다.
- 현재 시드 건물에는 `AI공학관`, `제1기숙사`, `제2기숙사`, `제3기숙사`, `중앙도서관`, `교육대학원`이 포함됩니다.
- 실시간 상태는 FastAPI의 `/status` 응답을 Spring Boot가 캐시해서 제공합니다.
- JWT는 `SMARTPARKING_JWT_SECRET` 값으로 고정 서명됩니다. 서버 재시작 후에도 같은 비밀키를 쓰면 기존 토큰이 유지됩니다.

## Entity Guide

이 프로젝트의 엔티티는 캠퍼스 -> 건물 -> 주차장 -> 사용자 데이터 순서로 연결됩니다.

### `Campus`

- 테이블: `campuses`
- 역할: 캠퍼스 단위의 최상위 위치 정보
- 주요 필드:
  - `id`
  - `name`
  - `centerLat`
  - `centerLng`
  - `defaultZoom`
- 의미:
  - 지도의 기본 중심점과 확대 수준을 정합니다.
  - 현재는 `가천대학교 글로벌캠퍼스`가 기본 캠퍼스로 들어갑니다.

### `Building`

- 테이블: `buildings`
- 역할: 캠퍼스 안의 건물 정보
- 주요 필드:
  - `id`
  - `campus_id`
  - `name`
  - `mapKey`
  - `lat`
  - `lng`
  - `sortOrder`
- 의미:
  - 한 캠퍼스 안에 여러 건물이 존재하는 1:N 구조입니다.
  - `mapKey`는 건물의 파일/지도 식별자입니다.
  - 예: `gachon_ai`, `gachon_dorm1`, `gachon_dorm3`, `gachon_library`, `gachon_gradschool`
- 관계:
  - `Building`은 반드시 하나의 `Campus`에 속합니다.

### `ParkingLot`

- 테이블: `parking_lots`
- 역할: 건물 아래의 실제 주차장 단위
- 주요 필드:
  - `id`
  - `building_id`
  - `name`
  - `partitionKey`
  - `mapImageUrl`
  - `slotLayoutJson`
  - `sortOrder`
- 의미:
  - 한 건물 안에 여러 주차장이 있을 수 있습니다.
  - `partitionKey`는 주차장 파일/상태 식별자입니다.
  - 예: `gachon_ai_1`, `gachon_ai_2`, `gachon_ai_3`
- 관계:
  - `ParkingLot`은 반드시 하나의 `Building`에 속합니다.
- 파일 규칙:
  - `fastapi/video_test/images/{partitionKey}_image.png`
  - `fastapi/video_test/map/{partitionKey}_map.png`
  - `fastapi/video_test/map/{partitionKey}_slots.json`
  - `fastapi/video_test/videos/{partitionKey}_video*.mp4`

### `User`

- 테이블: `users`
- 역할: 로그인 계정
- 주요 필드:
  - `id`
  - `username`
  - `password`
- 의미:
  - `password`는 BCrypt 해시로 저장됩니다.
  - 로그인 성공 시 JWT가 발급됩니다.

### `SavedParkingLocation`

- 테이블: `saved_parking_locations`
- 역할: 사용자가 저장한 현재 주차 위치
- 주요 필드:
  - `id`
  - `user_id`
  - `parking_lot_id`
  - `slotId`
  - `vehicleLabel`
  - `memo`
  - `active`
  - `savedAt`
  - `releasedAt`
- 의미:
  - 사용자가 어디에 주차했는지 기록합니다.
  - `active=true`인 레코드가 현재 주차 위치입니다.
  - `주차 종료`를 누르면 `active=false`로 바뀌고 `releasedAt`이 저장됩니다.

### `ParkingAlertRule`

- 테이블: `parking_alert_rules`
- 역할: 사용자가 등록한 빈자리 알림 규칙
- 주요 필드:
  - `id`
  - `user_id`
  - `parking_lot_id`
  - `minimumAvailableSlots`
  - `enabled`
  - `lastKnownAvailableSlots`
  - `lastTriggeredAt`
- 의미:
  - 예: `minimumAvailableSlots=5`면 해당 주차장의 빈자리가 5개 이상일 때 알림 조건이 됩니다.
  - `lastKnownAvailableSlots`는 직전 상태를 기억해서 중복 알림을 줄이는 데 사용합니다.

### `InAppNotification`

- 테이블: `in_app_notifications`
- 역할: 앱 내부 알림 저장소
- 주요 필드:
  - `id`
  - `user_id`
  - `title`
  - `message`
  - `category`
  - `readFlag`
  - `createdAt`
  - `readAt`
- 의미:
  - 빈자리 알림이 조건을 만족하면 여기에 저장됩니다.
  - 프론트는 이 테이블을 읽어서 알림 패널에 보여줍니다.
  - `readFlag=true`면 읽은 알림입니다.

## Entity Relationship

```text
Campus 1 ─── N Building 1 ─── N ParkingLot

User 1 ─── N SavedParkingLocation
User 1 ─── N ParkingAlertRule
User 1 ─── N InAppNotification

Building.mapKey
  └─ 건물 식별자
ParkingLot.partitionKey
  └─ 주차장 식별자
```

### Relationship Notes

- `Campus`는 최상위 캠퍼스입니다.
- `Building`은 반드시 하나의 `Campus`에 속합니다.
- `ParkingLot`은 반드시 하나의 `Building`에 속합니다.
- `User`는 로그인 계정입니다.
- `SavedParkingLocation`은 사용자의 현재 주차 위치입니다.
- `ParkingAlertRule`은 사용자의 빈자리 알림 조건입니다.
- `InAppNotification`은 사용자의 앱 내부 알림입니다.
- `Building.mapKey`는 건물 데이터와 건물 파일을 묶는 키입니다.
- `ParkingLot.partitionKey`는 주차장 파일, 영상, 슬롯 JSON, 점유 상태를 묶는 키입니다.

## Key Terms

- `mapKey`
  - 건물 식별자입니다.
  - 건물 이미지, 건물 지도, 건물 마커, 상위 주차장 그룹을 연결하는 기준입니다.
- `partitionKey`
  - 주차장 식별자입니다.
  - 비디오 파일, 이미지 파일, 슬롯 JSON, 점유 상태를 연결하는 기준입니다.
- `sortOrder`
  - 같은 건물 또는 같은 목록 안에서 표시 순서를 정합니다.
- `slotLayoutJson`
  - 슬롯 좌표와 크기, 각도, 타입이 들어 있는 JSON 문자열입니다.
- `vehicleLabel`
  - 사용자가 저장한 차량 이름입니다.
- `memo`
  - 사용자가 남긴 메모입니다.
