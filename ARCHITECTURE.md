# Architecture

## Purpose

This repository is organized around two active projects:

1. `fastapi/` provides map creation and YOLO-based parking occupancy inference.
2. `springboot/` polls the FastAPI status API and exposes application-facing endpoints.

The active runtime path is:

`fastapi/map_builder/map_builder_gui0.py` -> generated slot files in `fastapi/video_test/map/` -> `fastapi/video_test/server0.py` -> `springboot/`

## FastAPI Project

### Structure

- `fastapi/map_builder/`
  - `map_builder_gui0.py`: interactive slot editor used to create parking-slot JSON and map images
  - `map_builder_gui.py`: legacy/editor variant, not used in the active runtime path
- `fastapi/video_test/`
  - `server0.py`: active FastAPI service
  - `videos/`: partition video sources
  - `images/`: reference images for map creation
  - `map/`: generated slot JSON and map images
  - `weights/`: YOLO weight file
  - `server.py`, `server1.py`, `main.py`, `capture.py`, `image_detect.py`, `motion_main.py`, `main_images.py`, `parking_slot_mapping.py`, `resolution_test.py`: legacy or experimental scripts
  - `venv/`: local Python virtual environment, not source-controlled runtime code

### Runtime Responsibilities

`fastapi/video_test/server0.py`:

- loads `weights/visDrone.pt`
- opens `videos/partition1_video.mp4`, `videos/partition2_video.mp4`, and `videos/partition3_video.mp4`
- loads generated slot data from `map/custom_partition*_slots.json`
- runs YOLO inference in a background worker
- calculates occupied/available/disabled slots per partition
- exposes `GET /status`

### FastAPI Response Shape

```json
{
  "last_update": 0,
  "P1": {
    "summary": {
      "total": 0,
      "available": 0,
      "disabled_available": 0
    },
    "slots": []
  },
  "P2": {
    "summary": {
      "total": 0,
      "available": 0,
      "disabled_available": 0
    },
    "slots": []
  },
  "P3": {
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
- `fastapi/video_test/images/partition1_image.png`
- `fastapi/video_test/images/partition2_image.png`
- `fastapi/video_test/images/partition3_image.png`
- `fastapi/video_test/map/custom_partition1_slots.json`
- `fastapi/video_test/map/custom_partition1_map.png`
- `fastapi/video_test/map/custom_partition2_slots.json`
- `fastapi/video_test/map/custom_partition2_map.png`
- `fastapi/video_test/map/custom_partition3_slots.json`
- `fastapi/video_test/map/custom_partition3_map.png`
- `fastapi/video_test/videos/partition1_video.mp4`
- `fastapi/video_test/videos/partition2_video.mp4`
- `fastapi/video_test/videos/partition3_video.mp4`
- `fastapi/video_test/weights/visDrone.pt`

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
- exposes `/api/parking/status`
- exposes `/auth/register` and `/auth/login`
- keeps the deserialization contract aligned with the nested `summary/slots` FastAPI payload

### Relevant Spring Files

- `springboot/src/main/java/com/smartparking/server/config/WebClientConfig.java`
- `springboot/src/main/java/com/smartparking/server/service/ParkingStatusService.java`
- `springboot/src/main/java/com/smartparking/server/controller/ParkingStatusController.java`
- `springboot/src/main/java/com/smartparking/server/controller/AuthController.java`
- `springboot/src/main/java/com/smartparking/server/dto/ParkingStatusResponse.java`

## Legacy Or Non-Active Files

These files are not part of the current active runtime path:

- `fastapi/map_builder/map_builder_gui.py`
- `fastapi/map_builder/map/`
- `fastapi/video_test/capture.py`
- `fastapi/video_test/center_coordinate/*`
- `fastapi/video_test/detected_images/*`
- `fastapi/video_test/image_detect.py`
- `fastapi/video_test/main.py`
- `fastapi/video_test/main_images.py`
- `fastapi/video_test/motion_main.py`
- `fastapi/video_test/parking_slot_mapping.py`
- `fastapi/video_test/resolution_test.py`
- `fastapi/video_test/server.py`
- `fastapi/video_test/server1.py`
- `fastapi/video_test/venv/`
- `image_test/`

## Operational Notes

- `fastapi/video_test/server0.py` expects `custom_partition3_slots.json`; if that file is missing, P3 analysis cannot be completed correctly.
- The current repository snapshot also appears to miss `fastapi/video_test/videos/partition3_video.mp4`, so P3 can remain empty until that asset is restored.
- `fastapi/video_test/server0.py` now resolves weights, videos, and map files from its own directory, so it no longer depends on the current shell working directory.
- `springboot/` should keep its DTOs aligned with the actual FastAPI `/status` contract.
- Do not commit `.env` files or secrets.
- Keep generated environments and build caches out of source control.
- If the runtime path changes, update this document together with `README.md` and `CHANGELOG.md`.
