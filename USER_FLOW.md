# User Flow

This document describes how the web application starts, what the user sees, and what happens in the backend when each feature is used.

## 1. Startup Flow

### 1.1 Backend starts

1. The user runs `./gradlew bootRun` in `springboot/`.
2. Gradle starts `ServerApplication.main()`.
3. Spring Boot creates the application context.
4. Beans are loaded:
   - controllers
   - services
   - repositories
   - security configuration
   - WebClient configuration
5. JPA connects to the H2 in-memory database and updates the schema.
6. `CampusDataInitializer` seeds the campus and building metadata.
7. `ParkingLotAssetSyncService` scans `fastapi/video_test/videos/*_video*.mp4` and `fastapi/video_test/videos/*_video*.mov` and creates parking-lot rows when matching assets exist.
8. `ParkingStatusService` starts polling FastAPI `/status` every 5 seconds.
9. Spring Boot starts the embedded web server on port `8080`.

### 1.2 Browser opens the site

1. The user opens `http://localhost:8080/`.
2. Spring Boot returns `index.html` from `springboot/src/main/resources/static/`.
3. The browser loads `app.css` and `app.js`.
4. `app.js` runs after `DOMContentLoaded`.
5. `app.js` fetches:
   - `GET /api/ui/config`
   - `GET /api/campus/map`
6. The screen is rendered with:
   - campus header
   - building list
   - campus map
   - selected building detail
   - login panel
   - notifications panel
   - current parking location panel

## 2. Main Screen Layout

The page is divided into these sections:

1. Top header
   - campus name
   - Naver Map status
   - unread notification badge
2. Left sidebar
   - login form
   - current parking location card
   - in-app notification list
   - building list
3. Main content
   - campus map
   - selected building detail
   - parking-lot cards

## 3. Login Flow

### 3.1 Register

1. The user enters username and password in the login panel.
2. The user clicks `회원가입`.
3. The browser sends `POST /auth/register`.
4. `AuthService.register()` checks whether the username already exists.
5. If the user does not exist:
   - password is hashed with BCrypt
   - the user is saved in the `users` table
6. The response is a simple status string such as:
   - `REGISTER_SUCCESS`
   - `USER_EXISTS`

### 3.2 Login

1. The user enters username and password.
2. The user clicks `로그인`.
3. The browser sends `POST /auth/login`.
4. `AuthService.login()`:
   - finds the user by username
   - checks the password with BCrypt
   - creates a JWT if the password is valid
5. The response is JSON:

```json
{
  "token": "...",
  "username": "alice"
}
```

6. The browser stores the token in `localStorage` under `smartparking_token`.
7. The page then calls:
   - `GET /auth/me`
   - `GET /api/me/parking-location/current`
   - `GET /api/me/notifications`
   - `GET /api/me/notifications/unread-count`
   - `GET /api/me/alert-rules`
8. The login panel and user badges update.

### 3.3 Token validation

1. Each user-only request includes:

```http
Authorization: Bearer <token>
```

2. `JwtAuthenticationFilter` reads the header.
3. `JwtUtil` validates the signature and expiration.
4. If valid, Spring Security stores the username in the security context.
5. `/api/me/**` endpoints are then allowed.

## 4. Campus and Building Flow

### 4.1 Campus map

1. `app.js` calls `GET /api/campus/map`.
2. `CampusMapService` refreshes filesystem-driven parking-lot data.
3. It loads:
   - campus data
   - building list
   - parking-lot summaries
   - cached parking status from FastAPI
4. The browser renders the building cards and the campus map markers.

### 4.2 Select a building

1. The user clicks a building card or a map marker.
2. `app.js` calls `GET /api/campus/buildings/{buildingId}`.
3. The backend returns:
   - campus data
   - selected building data
   - parking-lot list for that building
4. The browser renders each parking lot as a separate card.

## 5. Parking-Lot Map Flow

Each parking lot has its own card and its own map workflow.

### 5.1 If no source image exists

1. The parking-lot card shows:
   - total slots
   - available slots
   - disabled-slot availability
   - status
2. The map panel shows that no photo has been uploaded yet.
3. The user can:
   - upload a photo
   - start the map builder

### 5.2 Upload source image

1. The user selects an image file.
2. The browser sends `POST /api/parking-lots/{parkingLotId}/map/upload`.
3. `ParkingLotMapService` stores the image at:
   - `fastapi/video_test/images/{partitionKey}_image.png`
4. Existing generated map files are cleared so the lot returns to an unbuilt state.

### 5.3 Upload polygon spec

1. The user sends a polygon spec JSON that roughly marks one or more parking areas, obstacles, and the entrance.
2. The browser polygon editor lets the user click points directly on the uploaded image and then sends `POST /api/parking-lots/{parkingLotId}/map/polygon-spec`.
3. `ParkingLotMapService` stores the JSON at:
   - `fastapi/video_test/map/{partitionKey}_auto_spec.json`
4. Existing generated map files are cleared so the lot returns to an unbuilt state.
5. The save action immediately calls `POST /api/parking-lots/{parkingLotId}/map/build`, which launches one-row slot generation.
6. `meters_per_pixel` is estimated from visible vehicles when possible and then written back into the spec.
7. The entrance is now stored as `entrances[]`, one line segment per parking polygon, and the generator uses each segment to choose a reference edge.
8. The map builder now scans from the outer boundary inward and stops at the first valid reference edge for each polygon.
9. The saved map image also shows the chosen reference edge for each polygon, so the row basis is visible on the image.
10. If a polygon is not directly reachable from the entrance line, the generator falls back to that polygon's centroid so every drawn polygon can still produce a reference edge.
11. Even if no slot JSON exists yet, the UI can still display the generated map image.

### 5.4 Launch map builder

1. The user clicks `지도 제작하기`.
2. The browser sends `POST /api/parking-lots/{parkingLotId}/map/build`.
3. Spring Boot starts `fastapi/map_builder/map_builder_gui0.py`.
4. The map builder opens with the uploaded photo.
5. If `fastapi/video_test/map/{partitionKey}_auto_spec.json` or `fastapi/video_test/map/{partitionKey}_polygon.json` exists, the builder first tries to generate one-row slots automatically.
6. The user reviews the highlighted reference edges and generated slots.
7. Keyboard shortcuts in the builder:
   - `s`: save and exit
   - `d`: delete the selected slot
   - `q`: quit
8. When saved, the builder writes:
   - `fastapi/video_test/map/{partitionKey}_map.png`
   - `fastapi/video_test/map/{partitionKey}_slots.json`

### 5.5 Render generated map

1. `app.js` refreshes the building detail.
2. If `slotLayoutJson` exists, the browser draws the slot boxes over the blurred background image.
3. Slot colors mean:
   - green: available
   - red: occupied
   - blue: available disabled slot
4. The user can click a slot to select it.

## 6. Save Parking Location Flow

### 6.1 Save current slot

1. The user clicks a slot on a parking-lot map.
2. The selected slot id is stored in the browser state for that lot.
3. The user clicks `내 위치 저장`.
4. The browser sends:

```http
POST /api/me/parking-location
```

with a body like:

```json
{
  "parkingLotId": 1,
  "slotId": 3,
  "vehicleLabel": "My Car",
  "memo": "학생회관 근처"
}
```

5. `ParkingLocationService`:
   - finds the current user
   - finds the parking lot
   - deactivates any previously active saved location
   - saves the new active location
6. The sidebar updates to show the current parking location.

### 6.2 Release parking location

1. The user clicks `주차 종료`.
2. The browser sends:

```http
DELETE /api/me/parking-location/current
```

3. `ParkingLocationService` marks the active record as inactive.
4. The release time is stored.
5. The sidebar returns to the empty state.

## 7. In-App Notification Flow

### 7.1 Create an alert rule

1. The user selects a parking lot.
2. The user enters a threshold number, such as `5`.
3. The user clicks `알림 등록`.
4. The browser sends:

```http
POST /api/me/alert-rules
```

with a body like:

```json
{
  "parkingLotId": 1,
  "minimumAvailableSlots": 5,
  "enabled": true
}
```

5. `ParkingAlertRuleService` stores the rule.

### 7.2 Monitor and notify

1. `ParkingAlertMonitorService` runs on a schedule.
2. It reads the cached FastAPI status from `ParkingStatusService`.
3. For each enabled rule, it checks whether available slots crossed the threshold.
4. If the rule transitions from unmet to met:
   - an in-app notification row is created
5. The notification appears in the sidebar notification panel.

### 7.3 Read notifications

1. The browser loads notifications with:
   - `GET /api/me/notifications`
   - `GET /api/me/notifications/unread-count`
2. The user clicks a notification.
3. The browser sends:

```http
PATCH /api/me/notifications/{notificationId}/read
```

4. The notification is marked as read.
5. The unread count badge updates.

## 8. Data Ownership Summary

### Public, unauthenticated data

- `/`
- `/app.js`
- `/app.css`
- `/api/ui/config`
- `/api/campus/map`
- `/api/campus/buildings/{buildingId}`
- `/api/parking-lots/{parkingLotId}/map`
- `/api/parking-lots/{parkingLotId}/map/upload`
- `/api/parking-lots/{parkingLotId}/map/build`
- `/api/parking/status`

### User-only data

- `/auth/me`
- `/api/me/parking-location/**`
- `/api/me/notifications/**`
- `/api/me/alert-rules/**`

## 9. Short Version

1. Server starts.
2. Spring Boot loads data and services.
3. Browser loads `index.html`.
4. `app.js` requests config and campus data.
5. User logs in if needed.
6. User selects a building.
7. User uploads parking-lot photos and builds maps.
8. User clicks slots to save parking location.
9. User registers alert rules.
10. Scheduled monitoring writes in-app notifications.
