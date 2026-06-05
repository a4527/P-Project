# Architecture

## Purpose

This repository is organized around two active projects:

1. `fastapi/` provides map creation and YOLO-based parking occupancy inference.
2. `springboot/` aggregates campus metadata, exposes application-facing endpoints, and serves the minimal verification frontend.

The active runtime path is:

`springboot/src/main/resources/static/app.js` -> `springboot/src/main/java/...` -> `springboot/src/main/java/com/smartparking/server/controller/ParkingLotMapController.java` -> `fastapi/map_builder/map_builder_gui0.py` -> generated slot files in `fastapi/video_test/map/`

## FastAPI Project

### Structure

- `fastapi/map_builder/`
- `map_builder_gui0.py`: interactive slot editor used to create parking-slot JSON and map images from a lot-level key such as `gachon_ai_1` or `gachon_library_1`
- `fastapi/video_test/`
  - `server0.py`: active FastAPI service
  - `videos/`: partition video sources
  - `images/`: reference images for map creation
  - `map/`: generated slot JSON and map images
  - `weights/`: YOLO weight file
  - `venv/`: local Python virtual environment, not source-controlled runtime code

### Runtime Responsibilities

`fastapi/video_test/server0.py`:

- loads `weights/visDrone.pt`
- auto-discovers every `videos/*_video.mp4`
- loads generated slot data from the matching `map/*_slots.json`
- runs YOLO inference in a background worker
- calculates occupied/available/disabled slots per discovered lot key
- exposes `GET /status`

### FastAPI Response Shape

```json
{
  "last_update": 0,
  "gachon_ai_1": {
    "summary": {
      "total": 0,
      "available": 0,
      "disabled_available": 0
    },
    "slots": []
  },
  "gachon_ai_2": {
    "summary": {
      "total": 0,
      "available": 0,
      "disabled_available": 0
    },
    "slots": []
  },
  "gachon_dorm1_1": {
    "summary": {
      "total": 0,
      "available": 0,
      "disabled_available": 0
    },
    "slots": []
  }
}
```

### Required FastAPI Assets

- `fastapi/map_builder/map_builder_gui0.py`
- `fastapi/video_test/server0.py`
- `fastapi/video_test/images/*_image.png`
- `fastapi/video_test/map/*_slots.json`
- `fastapi/video_test/map/*_map.png`
- `fastapi/video_test/videos/*_video.mp4`
- `fastapi/video_test/weights/visDrone.pt`

### Naming Rules

- Building-level map identifiers use `mapKey` values such as `gachon_ai`, `gachon_library`, `gachon_dorm1`, and `gachon_gradschool`.
- Parking-lot assets use the `mapKey` plus a lot suffix, such as `gachon_ai_1`, `gachon_ai_2`, `gachon_ai_3`, or `gachon_dorm1_1`.
- FastAPI occupancy keys are the same lot prefixes discovered from filenames, so Spring Boot can resolve them through `ParkingLot.partitionKey` without hardcoding.

## Spring Boot Project

### Structure

- `springboot/src/main/java/com/smartparking/server/`
  - `config/`: WebClient and security configuration
  - `controller/`: auth and parking-status endpoints
  - `dto/`: API request/response classes
  - `entity/`: JPA entities
  - `repository/`: persistence access layer
  - `service/`: JWT, auth, and parking-status caching logic
- `springboot/src/main/resources/application.properties`: local runtime configuration
- `springboot/gradlew`: Gradle wrapper entrypoint for this project

### Runtime Responsibilities

`springboot/`:

- polls FastAPI `/status` on a schedule
- caches the latest parking status
- resolves the cached FastAPI partitions to campus/building/parking-lot records
- auto-creates `ParkingLot` rows when a matching `*_video*.mp4` asset exists for a partition key
- issues stable JWTs for `/auth/login` and protects the user-only `/api/me/**` routes
- stores per-user saved parking locations and active parking-end state
- stores per-user in-app notification rules and unread notification records
- exposes `/api/parking/status` as the raw status payload
- exposes `/api/campus/map` for campus-wide map metadata
- exposes `/api/campus/buildings/{buildingId}` for building detail and lot status
- exposes `/api/parking-lots/{parkingLotId}/map` for parking-lot map status, upload, and local map-builder launch
- exposes `/api/ui/config` for the frontend bootstrap data
- exposes `/auth/login`, `/auth/register`, `/auth/me`, `/api/me/parking-location/**`, and `/api/me/notifications/**` for user-driven workflows
- serves the minimal verification UI from `springboot/src/main/resources/static/`
- keeps the deserialization contract aligned with the nested `summary/slots` FastAPI payload
- keeps the user-facing building payload minimal: `name`, `mapKey`, coordinates, and parking-lot data only

### Relevant Spring Files

- `springboot/src/main/java/com/smartparking/server/config/CampusDataInitializer.java`
- `springboot/src/main/java/com/smartparking/server/config/WebClientConfig.java`
- `springboot/src/main/java/com/smartparking/server/controller/CampusController.java`
- `springboot/src/main/java/com/smartparking/server/controller/BuildingMapController.java`
- `springboot/src/main/java/com/smartparking/server/controller/ParkingLotMapController.java`
- `springboot/src/main/java/com/smartparking/server/controller/ParkingStatusController.java`
- `springboot/src/main/java/com/smartparking/server/controller/UiController.java`
- `springboot/src/main/java/com/smartparking/server/controller/AuthController.java`
- `springboot/src/main/java/com/smartparking/server/controller/MeParkingLocationController.java`
- `springboot/src/main/java/com/smartparking/server/controller/MeNotificationController.java`
- `springboot/src/main/java/com/smartparking/server/controller/MeAlertRuleController.java`
- `springboot/src/main/java/com/smartparking/server/service/CampusMapService.java`
- `springboot/src/main/java/com/smartparking/server/service/BuildingMapService.java`
- `springboot/src/main/java/com/smartparking/server/service/ParkingLotMapService.java`
- `springboot/src/main/java/com/smartparking/server/service/ParkingStatusService.java`
- `springboot/src/main/java/com/smartparking/server/service/JwtUtil.java`
- `springboot/src/main/java/com/smartparking/server/service/ParkingLocationService.java`
- `springboot/src/main/java/com/smartparking/server/service/ParkingAlertRuleService.java`
- `springboot/src/main/java/com/smartparking/server/service/ParkingAlertMonitorService.java`
- `springboot/src/main/java/com/smartparking/server/service/InAppNotificationService.java`
- `springboot/src/main/java/com/smartparking/server/dto/CampusMapResponse.java`
- `springboot/src/main/java/com/smartparking/server/dto/BuildingDetailResponse.java`
- `springboot/src/main/java/com/smartparking/server/dto/BuildingMapResponse.java`
- `springboot/src/main/java/com/smartparking/server/dto/ParkingLotMapResponse.java`
- `springboot/src/main/java/com/smartparking/server/dto/LoginResponse.java`
- `springboot/src/main/java/com/smartparking/server/dto/ParkingLocationResponse.java`
- `springboot/src/main/java/com/smartparking/server/dto/ParkingAlertRuleResponse.java`
- `springboot/src/main/java/com/smartparking/server/dto/InAppNotificationResponse.java`
- `springboot/src/main/resources/static/index.html`
- `springboot/src/main/resources/static/app.js`



## Operational Notes

- `fastapi/video_test/server0.py` resolves weights, videos, and map files from its own directory and auto-discovers matching video/map pairs.
- `springboot/` keeps the cached FastAPI lot keys aligned with campus and building records through `ParkingLot.partitionKey`, while exposing user-facing lot codes such as `gachon_ai_1`, `gachon_ai_2`, and `gachon_ai_3`.
- `springboot/` resolves parking-lot map upload and map-builder actions through `ParkingLot.partitionKey`, such as `gachon_ai_1`, `gachon_ai_2`, and `gachon_ai_3`.
- `springboot/` auto-creates `ParkingLot` records from filesystem video assets, then fills in source images and slot layouts when those files appear.
- JWT signing now uses a fixed secret from `SMARTPARKING_JWT_SECRET`, so browser tokens remain valid across server restarts when the secret stays unchanged.
- The web UI stores the JWT in browser storage, then uses it for user-specific parking-location save/release, notification retrieval, and alert-rule management.
- The in-app notification monitor reuses the cached FastAPI status and creates notification rows only when a configured alert threshold transitions from unmet to met.
- Building entities no longer carry a free-form description column; the frontend-visible campus/building payloads rely on name, `mapKey`, coordinates, and map status only.
- The campus map view seeds buildings from `CampusDataInitializer`, then discovers lots from filesystem assets instead of hardcoded lot rows.
- Building responses intentionally omit free-form descriptions; the UI now relies on the building name, `mapKey`, and map status fields instead of a text subtitle.
- The web UI is intentionally minimal and read-only for verification.
- Frontend-facing parking data now uses a shared `ParkingLotView` shape with `summary`, `slots`, and lot-level map state fields; both campus and building responses reuse the same `BuildingView` DTO, and building detail responses keep a single `parkingLots` array instead of duplicating lot data inside the building block.
- The web UI can upload a source image, store it under `fastapi/video_test/images/{partitionKey}_image.png`, launch `map_builder_gui0.py` locally, and then refresh the generated `fastapi/video_test/map/{partitionKey}_map.png` and `fastapi/video_test/map/{partitionKey}_slots.json` assets.
- Parking-lot map files are stored locally under `fastapi/video_test/images/{partitionKey}_image.png`, `fastapi/video_test/map/{partitionKey}_map.png`, and `fastapi/video_test/map/{partitionKey}_slots.json`.
- Do not commit `.env` files or secrets.
- Keep generated environments and build caches out of source control.
- If the runtime path changes, update this document together with `README.md` and `CHANGELOG.md`.
