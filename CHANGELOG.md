# Changelog

## Unreleased

- Documented the active two-project runtime architecture around `fastapi/map_builder/map_builder_gui0.py`, `fastapi/video_test/server0.py`, and `springboot/`.
- Added a current-file inventory that separates required runtime assets from legacy or unused files.
- Recorded the live FastAPI contract as nested `P1/P2/P3 -> summary/slots` payloads with `last_update`.
- Added a configurable `smartparking.yolo.base-url` Spring property so the WebClient can target the FastAPI server explicitly.
- Verified at runtime that Spring Boot receives and returns the parsed parking-status payload from FastAPI.
- Noted the missing `fastapi/video_test/map/custom_partition3_slots.json` and `fastapi/video_test/videos/partition3_video.mp4` assets in the current snapshot.
- Normalized `fastapi/video_test/server0.py` path handling to resolve weights, videos, and map files relative to the script directory instead of the shell working directory.
