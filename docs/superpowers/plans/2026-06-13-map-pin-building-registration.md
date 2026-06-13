# 지도 핀 기반 건물·주차장 동적 등록 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사용자가 네이버 지도에서 위치를 클릭해 건물을 만들고, 그 건물에 영상을 업로드해 주차장을 등록하며, 등록 데이터는 재시작 후에도 유지된다.

**Architecture:** DB 우선(A안). 신규 REST API가 건물·주차장 레코드를 영속 DB(H2 파일 모드)에 직접 생성하고 업로드 영상을 `videos/`에 저장한다. 기존 파일 자동 스캔과 YOLO 파이프라인은 그대로 활용한다. 하드코딩 건물 시드는 제거한다. 프론트는 기존 `app.js` 한 화면에 클릭 등록·검색·삭제 UI를 얹는다.

**Tech Stack:** Spring Boot 4 (Web, Security, Data JPA, Validation), H2(file), Lombok, JUnit5 + MockMvc, 바닐라 JS + 네이버 지도 v3 SDK(Geocoding 포함).

**참고 설계 문서:** `docs/superpowers/specs/2026-06-13-map-pin-building-registration-design.md`

---

## File Structure

**신규 생성 (백엔드):**
- `springboot/src/main/java/com/smartparking/server/service/AssetPathResolver.java` — 영상/이미지/맵/슬롯/스크립트 경로 단일 해석(테스트용 루트 설정 가능)
- `springboot/src/main/java/com/smartparking/server/dto/BuildingCreateRequest.java`
- `springboot/src/main/java/com/smartparking/server/dto/BuildingResponse.java`
- `springboot/src/main/java/com/smartparking/server/dto/ParkingLotCreatedResponse.java`
- `springboot/src/main/java/com/smartparking/server/service/BuildingRegistrationService.java` — 건물/주차장 생성·삭제 + 파일 처리
- `springboot/src/main/java/com/smartparking/server/controller/BuildingController.java` — `POST /api/buildings`, `DELETE /api/buildings/{id}`, `POST /api/buildings/{id}/parking-lots`
- `springboot/src/main/java/com/smartparking/server/controller/ParkingLotController.java` — `DELETE /api/parking-lots/{id}`
- `springboot/src/test/java/com/smartparking/server/service/BuildingRegistrationServiceTest.java`
- `springboot/src/test/java/com/smartparking/server/controller/BuildingControllerTest.java`

**수정 (백엔드):**
- `springboot/src/main/resources/application.properties` — H2 파일 모드, 멀티파트 2GB, asset-root 기본값
- `springboot/src/main/java/com/smartparking/server/config/CampusDataInitializer.java` — 건물 시드 제거
- `springboot/src/main/java/com/smartparking/server/repository/SavedParkingLocationRepository.java` — `deleteByParkingLotId`
- `springboot/src/main/java/com/smartparking/server/repository/ParkingAlertRuleRepository.java` — `deleteByParkingLotId`
- `springboot/src/main/java/com/smartparking/server/config/SecurityConfig.java` — 신규 엔드포인트 인증
- `.gitignore` — `springboot/data/`

**수정 (프론트):**
- `springboot/src/main/resources/static/app.js` — 지도 클릭 등록, Geocoding 검색, 주차장 추가 폼, 삭제 버튼
- `springboot/src/main/resources/static/index.html` — 검색창, 건물 추가 안내 영역
- `springboot/src/main/resources/static/app.css` — 신규 요소 최소 스타일

---

## Task 1: H2 파일 모드 · 멀티파트 한도 · .gitignore

**Files:**
- Modify: `springboot/src/main/resources/application.properties:1`, `:15-16`
- Modify: `.gitignore`

- [ ] **Step 1: application.properties 수정**

`springboot/src/main/resources/application.properties`에서 1번째 줄과 15-16번째 줄을 아래처럼 바꾼다.

1번째 줄:
```properties
spring.datasource.url=jdbc:h2:file:./data/smartparking;AUTO_SERVER=TRUE
```

15-16번째 줄:
```properties
spring.servlet.multipart.max-file-size=2GB
spring.servlet.multipart.max-request-size=2GB
```

그리고 파일 맨 아래에 자산 경로 기본값(빈 값=자동 탐색) 한 줄 추가:
```properties
smartparking.asset-root=${SMARTPARKING_ASSET_ROOT:}
```

- [ ] **Step 2: .gitignore에 DB 디렉터리 추가**

`.gitignore` 맨 아래에 추가:
```gitignore
springboot/data/
```

- [ ] **Step 3: 빌드가 깨지지 않는지 확인**

Run:
```bash
cd springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew compileJava -q
```
Expected: 에러 없이 종료(빌드 성공).

- [ ] **Step 4: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/resources/application.properties .gitignore
git commit -m "chore: H2 파일 모드 전환, 멀티파트 한도 2GB, asset-root 설정 추가"
```

---

## Task 2: 하드코딩 건물 시드 제거 (캠퍼스만 유지)

**Files:**
- Modify: `springboot/src/main/java/com/smartparking/server/config/CampusDataInitializer.java`

- [ ] **Step 1: 건물 시드 제거**

`CampusDataInitializer.java` 전체를 아래로 교체한다. (건물 5개 + `BuildingSeed` record 제거, 캠퍼스 생성과 자동 스캔만 유지)

```java
package com.smartparking.server.config;

import com.smartparking.server.entity.Campus;
import com.smartparking.server.repository.CampusRepository;
import com.smartparking.server.service.ParkingLotAssetSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class CampusDataInitializer {

    private final CampusRepository campusRepository;
    private final ParkingLotAssetSyncService parkingLotAssetSyncService;

    @Bean
    public CommandLineRunner seedCampusData() {
        return args -> {
            if (campusRepository.count() == 0) {
                Campus campus = new Campus();
                campus.setName("가천대학교 글로벌캠퍼스");
                campus.setCenterLat(37.4535458);
                campus.setCenterLng(127.1325556);
                campus.setDefaultZoom(17);
                campusRepository.save(campus);
            }
            parkingLotAssetSyncService.syncFromFilesystem();
        };
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run:
```bash
cd springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew compileJava -q
```
Expected: 성공(미사용 import `BuildingRepository` 등 제거되어 에러 없음).

- [ ] **Step 3: 기존 H2 파일 초기화 (이전 인메모리/시드 잔재 제거)**

Run:
```bash
rm -rf /Users/leehnsong/P-Project/springboot/data
```
Expected: 다음 기동 시 빈 DB로 시작(건물 0개).

- [ ] **Step 4: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/java/com/smartparking/server/config/CampusDataInitializer.java
git commit -m "feat: 하드코딩 건물 시드 제거, 캠퍼스 시드만 유지"
```

---

## Task 3: AssetPathResolver (경로 단일 해석 + 테스트 가능)

**Files:**
- Create: `springboot/src/main/java/com/smartparking/server/service/AssetPathResolver.java`

- [ ] **Step 1: AssetPathResolver 작성**

`springboot/src/main/java/com/smartparking/server/service/AssetPathResolver.java`:

```java
package com.smartparking.server.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AssetPathResolver {

    private final String configuredRoot;

    public AssetPathResolver(@Value("${smartparking.asset-root:}") String configuredRoot) {
        this.configuredRoot = configuredRoot;
    }

    public Path videoTestRoot() {
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            return Paths.get(configuredRoot).toAbsolutePath().normalize();
        }
        return resolveExistingPath(
                Paths.get("fastapi", "video_test"),
                Paths.get("..", "fastapi", "video_test"),
                Paths.get("..", "..", "fastapi", "video_test"));
    }

    public Path videoPath(String partitionKey) {
        return videoTestRoot().resolve("videos").resolve(partitionKey + "_video.mp4");
    }

    public Path sourceImagePath(String partitionKey) {
        return videoTestRoot().resolve("images").resolve(partitionKey + "_image.png");
    }

    public Path generatedMapPath(String partitionKey) {
        return videoTestRoot().resolve("map").resolve(partitionKey + "_map.png");
    }

    public Path slotLayoutPath(String partitionKey) {
        return videoTestRoot().resolve("map").resolve(partitionKey + "_slots.json");
    }

    private Path resolveExistingPath(Path... candidates) {
        for (Path candidate : candidates) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.exists(absolute)) {
                return absolute;
            }
        }
        return candidates[0].toAbsolutePath().normalize();
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run:
```bash
cd springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew compileJava -q
```
Expected: 성공.

- [ ] **Step 3: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/java/com/smartparking/server/service/AssetPathResolver.java
git commit -m "feat: 자산 경로 단일 해석용 AssetPathResolver 추가"
```

---

## Task 4: 건물 생성 — DTO + 서비스 (TDD)

**Files:**
- Create: `springboot/src/main/java/com/smartparking/server/dto/BuildingCreateRequest.java`
- Create: `springboot/src/main/java/com/smartparking/server/dto/BuildingResponse.java`
- Create: `springboot/src/main/java/com/smartparking/server/service/BuildingRegistrationService.java`
- Test: `springboot/src/test/java/com/smartparking/server/service/BuildingRegistrationServiceTest.java`

- [ ] **Step 1: DTO 두 개 작성**

`dto/BuildingCreateRequest.java`:
```java
package com.smartparking.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BuildingCreateRequest {
    @NotBlank
    private String name;
    @NotNull
    private Double lat;
    @NotNull
    private Double lng;
}
```

`dto/BuildingResponse.java`:
```java
package com.smartparking.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BuildingResponse {
    private Long id;
    private String name;
    private String mapKey;
    private Double lat;
    private Double lng;
    private Integer sortOrder;
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/smartparking/server/service/BuildingRegistrationServiceTest.java`:
```java
package com.smartparking.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.server.dto.BuildingCreateRequest;
import com.smartparking.server.dto.BuildingResponse;
import com.smartparking.server.entity.Campus;
import com.smartparking.server.repository.BuildingRepository;
import com.smartparking.server.repository.CampusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class BuildingRegistrationServiceTest {

    @Autowired
    private BuildingRegistrationService service;
    @Autowired
    private CampusRepository campusRepository;
    @Autowired
    private BuildingRepository buildingRepository;

    @BeforeEach
    void ensureCampus() {
        if (campusRepository.count() == 0) {
            Campus campus = new Campus();
            campus.setName("테스트캠퍼스");
            campus.setCenterLat(37.0);
            campus.setCenterLng(127.0);
            campus.setDefaultZoom(17);
            campusRepository.save(campus);
        }
    }

    @Test
    void createsBuildingWithCoordinatesAndAutoMapKey() {
        BuildingCreateRequest request = new BuildingCreateRequest();
        request.setName("학생회관");
        request.setLat(37.451);
        request.setLng(127.131);

        BuildingResponse response = service.createBuilding(request);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getName()).isEqualTo("학생회관");
        assertThat(response.getLat()).isEqualTo(37.451);
        assertThat(response.getMapKey()).startsWith("bldg-");
        assertThat(buildingRepository.findByMapKey(response.getMapKey())).isPresent();
    }

    @Test
    void generatesUniqueMapKeysForMultipleBuildings() {
        BuildingCreateRequest a = new BuildingCreateRequest();
        a.setName("A");
        a.setLat(37.1);
        a.setLng(127.1);
        BuildingCreateRequest b = new BuildingCreateRequest();
        b.setName("B");
        b.setLat(37.2);
        b.setLng(127.2);

        String keyA = service.createBuilding(a).getMapKey();
        String keyB = service.createBuilding(b).getMapKey();

        assertThat(keyA).isNotEqualTo(keyB);
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run:
```bash
cd springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests "*BuildingRegistrationServiceTest" -q
```
Expected: 컴파일 실패 또는 FAIL (`BuildingRegistrationService` / `createBuilding` 없음).

- [ ] **Step 4: 서비스 구현 (건물 생성 부분)**

`service/BuildingRegistrationService.java`:
```java
package com.smartparking.server.service;

import com.smartparking.server.dto.BuildingCreateRequest;
import com.smartparking.server.dto.BuildingResponse;
import com.smartparking.server.entity.Building;
import com.smartparking.server.entity.Campus;
import com.smartparking.server.repository.BuildingRepository;
import com.smartparking.server.repository.CampusRepository;
import java.util.Comparator;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class BuildingRegistrationService {

    private final CampusRepository campusRepository;
    private final BuildingRepository buildingRepository;

    @Transactional
    public BuildingResponse createBuilding(BuildingCreateRequest request) {
        Campus campus = getDefaultCampus();

        Building building = new Building();
        building.setCampus(campus);
        building.setName(request.getName());
        building.setMapKey(generateUniqueMapKey());
        building.setLat(request.getLat());
        building.setLng(request.getLng());
        building.setSortOrder(nextBuildingSortOrder(campus.getId()));
        buildingRepository.save(building);

        return toResponse(building);
    }

    private String generateUniqueMapKey() {
        String candidate;
        do {
            candidate = "bldg-" + UUID.randomUUID().toString().substring(0, 8);
        } while (buildingRepository.findByMapKey(candidate).isPresent());
        return candidate;
    }

    private int nextBuildingSortOrder(Long campusId) {
        return buildingRepository.findByCampusIdOrderBySortOrderAsc(campusId).size() + 1;
    }

    private Campus getDefaultCampus() {
        return campusRepository.findAll().stream()
                .min(Comparator.comparing(Campus::getId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No campus data found"));
    }

    private BuildingResponse toResponse(Building building) {
        return new BuildingResponse(
                building.getId(),
                building.getName(),
                building.getMapKey(),
                building.getLat(),
                building.getLng(),
                building.getSortOrder());
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run:
```bash
cd springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests "*BuildingRegistrationServiceTest" -q
```
Expected: PASS (2 tests).

- [ ] **Step 6: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/java/com/smartparking/server/dto/BuildingCreateRequest.java \
        springboot/src/main/java/com/smartparking/server/dto/BuildingResponse.java \
        springboot/src/main/java/com/smartparking/server/service/BuildingRegistrationService.java \
        springboot/src/test/java/com/smartparking/server/service/BuildingRegistrationServiceTest.java
git commit -m "feat: 건물 동적 생성 서비스(자동 mapKey) + 테스트"
```

---

## Task 5: 주차장 생성 — 영상 업로드 (TDD)

**Files:**
- Create: `springboot/src/main/java/com/smartparking/server/dto/ParkingLotCreatedResponse.java`
- Modify: `springboot/src/main/java/com/smartparking/server/service/BuildingRegistrationService.java`
- Modify: `springboot/src/test/java/com/smartparking/server/service/BuildingRegistrationServiceTest.java`

- [ ] **Step 1: 생성 응답 DTO 작성**

`dto/ParkingLotCreatedResponse.java`:
```java
package com.smartparking.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ParkingLotCreatedResponse {
    private Long id;
    private Long buildingId;
    private String name;
    private String partitionKey;
}
```

- [ ] **Step 2: 실패하는 테스트 추가**

`BuildingRegistrationServiceTest.java`의 import에 추가:
```java
import com.smartparking.server.dto.ParkingLotCreatedResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.beans.factory.annotation.Value;
```

클래스 안에 필드와 테스트 추가:
```java
    @Value("${smartparking.asset-root}")
    private String assetRoot;

    @Test
    void addsParkingLotAndStoresVideoFile() throws Exception {
        BuildingCreateRequest req = new BuildingCreateRequest();
        req.setName("영상건물");
        req.setLat(37.45);
        req.setLng(127.13);
        Long buildingId = service.createBuilding(req).getId();

        MockMultipartFile video = new MockMultipartFile(
                "video", "test_video.mp4", "video/mp4", "dummy-bytes".getBytes());

        ParkingLotCreatedResponse lot = service.addParkingLot(buildingId, "지하1층", video, null);

        assertThat(lot.getId()).isNotNull();
        assertThat(lot.getBuildingId()).isEqualTo(buildingId);
        assertThat(lot.getPartitionKey()).contains("_");
        Path videoPath = Path.of(assetRoot, "videos", lot.getPartitionKey() + "_video.mp4");
        assertThat(Files.exists(videoPath)).isTrue();
    }
```

- [ ] **Step 3: 테스트가 임시 asset-root를 쓰도록 설정**

`src/test/resources/application-test.properties` 파일을 만들고 아래를 넣는다(없으면 생성). 그리고 테스트 클래스 상단 `@SpringBootTest` 아래에 `@org.springframework.test.context.TestPropertySource(properties = "smartparking.asset-root=${java.io.tmpdir}/sp-test-assets")`를 추가한다.

`BuildingRegistrationServiceTest`의 어노테이션을 다음으로 변경:
```java
@SpringBootTest
@org.springframework.test.context.TestPropertySource(properties = {
        "smartparking.asset-root=${java.io.tmpdir}/sp-test-assets",
        "spring.datasource.url=jdbc:h2:mem:sptest;DB_CLOSE_DELAY=-1"
})
@Transactional
```

- [ ] **Step 4: 테스트 실패 확인**

Run:
```bash
cd springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests "*BuildingRegistrationServiceTest" -q
```
Expected: FAIL (`addParkingLot` 메서드 없음).

- [ ] **Step 5: 서비스에 주차장 생성 구현**

`BuildingRegistrationService.java`에 의존성·메서드를 추가한다.

import 추가:
```java
import com.smartparking.server.dto.ParkingLotCreatedResponse;
import com.smartparking.server.entity.ParkingLot;
import com.smartparking.server.repository.ParkingLotRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.springframework.web.multipart.MultipartFile;
```

필드 추가(생성자 주입을 위해 `final` 필드로):
```java
    private final ParkingLotRepository parkingLotRepository;
    private final AssetPathResolver assetPathResolver;
```

메서드 추가:
```java
    @Transactional
    public ParkingLotCreatedResponse addParkingLot(
            Long buildingId, String name, MultipartFile video, MultipartFile image) {
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Building not found: " + buildingId));
        if (video == null || video.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video file is required");
        }

        String partitionKey = generateUniquePartitionKey(building);

        ParkingLot lot = new ParkingLot();
        lot.setBuilding(building);
        lot.setName(name != null && !name.isBlank() ? name : partitionKey);
        lot.setPartitionKey(partitionKey);
        lot.setMapImageUrl(null);
        lot.setSlotLayoutJson(null);
        lot.setSortOrder(parkingLotRepository.findByBuildingIdOrderBySortOrderAsc(buildingId).size() + 1);
        parkingLotRepository.save(lot);

        storeVideo(partitionKey, video);
        if (image != null && !image.isEmpty()) {
            storeImage(partitionKey, image);
        }

        return new ParkingLotCreatedResponse(lot.getId(), buildingId, lot.getName(), partitionKey);
    }

    private String generateUniquePartitionKey(Building building) {
        int n = parkingLotRepository.findByBuildingIdOrderBySortOrderAsc(building.getId()).size() + 1;
        String candidate = building.getMapKey() + "_" + n;
        while (parkingLotRepository.existsByPartitionKey(candidate)) {
            n++;
            candidate = building.getMapKey() + "_" + n;
        }
        return candidate;
    }

    private void storeVideo(String partitionKey, MultipartFile video) {
        Path target = assetPathResolver.videoPath(partitionKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(video.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store video", e);
        }
    }

    private void storeImage(String partitionKey, MultipartFile image) {
        Path target = assetPathResolver.sourceImagePath(partitionKey);
        try {
            Files.createDirectories(target.getParent());
            BufferedImage img = ImageIO.read(image.getInputStream());
            if (img == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported image format");
            }
            ImageIO.write(img, "png", target.toFile());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store image", e);
        }
    }
```

- [ ] **Step 6: 테스트 통과 확인**

Run:
```bash
cd springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests "*BuildingRegistrationServiceTest" -q
```
Expected: PASS (3 tests).

- [ ] **Step 7: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/java/com/smartparking/server/dto/ParkingLotCreatedResponse.java \
        springboot/src/main/java/com/smartparking/server/service/BuildingRegistrationService.java \
        springboot/src/test/java/com/smartparking/server/service/BuildingRegistrationServiceTest.java
git commit -m "feat: 건물에 주차장 추가(영상 업로드, partitionKey 자동) + 테스트"
```

---

## Task 6: 삭제 — 연관 레코드·파일 정리 (TDD)

**Files:**
- Modify: `springboot/src/main/java/com/smartparking/server/repository/SavedParkingLocationRepository.java`
- Modify: `springboot/src/main/java/com/smartparking/server/repository/ParkingAlertRuleRepository.java`
- Modify: `springboot/src/main/java/com/smartparking/server/service/BuildingRegistrationService.java`
- Modify: `springboot/src/test/java/com/smartparking/server/service/BuildingRegistrationServiceTest.java`

- [ ] **Step 1: 리포지토리에 삭제 메서드 추가**

`SavedParkingLocationRepository.java`에 추가:
```java
    void deleteByParkingLotId(Long parkingLotId);
```
`ParkingAlertRuleRepository.java`에 추가:
```java
    void deleteByParkingLotId(Long parkingLotId);
```

- [ ] **Step 2: 실패하는 테스트 추가**

`BuildingRegistrationServiceTest.java`에 테스트 추가:
```java
    @Test
    void deletingParkingLotRemovesRecordAndVideo() throws Exception {
        BuildingCreateRequest req = new BuildingCreateRequest();
        req.setName("삭제건물");
        req.setLat(37.45);
        req.setLng(127.13);
        Long buildingId = service.createBuilding(req).getId();
        MockMultipartFile video = new MockMultipartFile(
                "video", "v.mp4", "video/mp4", "x".getBytes());
        ParkingLotCreatedResponse lot = service.addParkingLot(buildingId, "L", video, null);
        Path videoPath = Path.of(assetRoot, "videos", lot.getPartitionKey() + "_video.mp4");
        assertThat(Files.exists(videoPath)).isTrue();

        service.deleteParkingLot(lot.getId());

        assertThat(parkingLotRepositoryExists(lot.getId())).isFalse();
        assertThat(Files.exists(videoPath)).isFalse();
    }

    @Test
    void deletingBuildingRemovesItsParkingLots() throws Exception {
        BuildingCreateRequest req = new BuildingCreateRequest();
        req.setName("건물삭제");
        req.setLat(37.45);
        req.setLng(127.13);
        Long buildingId = service.createBuilding(req).getId();
        MockMultipartFile video = new MockMultipartFile(
                "video", "v.mp4", "video/mp4", "x".getBytes());
        service.addParkingLot(buildingId, "L", video, null);

        service.deleteBuilding(buildingId);

        assertThat(buildingRepository.findById(buildingId)).isEmpty();
    }
```

테스트 클래스에 헬퍼와 의존성 추가(상단 import에 `import com.smartparking.server.repository.ParkingLotRepository;` 추가):
```java
    @Autowired
    private ParkingLotRepository parkingLotRepository;

    private boolean parkingLotRepositoryExists(Long id) {
        return parkingLotRepository.findById(id).isPresent();
    }
```

- [ ] **Step 3: 테스트 실패 확인**

Run:
```bash
cd springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests "*BuildingRegistrationServiceTest" -q
```
Expected: FAIL (`deleteParkingLot` / `deleteBuilding` 없음).

- [ ] **Step 4: 삭제 구현**

`BuildingRegistrationService.java`에 의존성·메서드 추가.

import 추가:
```java
import com.smartparking.server.repository.ParkingAlertRuleRepository;
import com.smartparking.server.repository.SavedParkingLocationRepository;
import java.util.List;
```

필드 추가:
```java
    private final SavedParkingLocationRepository savedParkingLocationRepository;
    private final ParkingAlertRuleRepository parkingAlertRuleRepository;
```

메서드 추가:
```java
    @Transactional
    public void deleteBuilding(Long buildingId) {
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Building not found: " + buildingId));
        List<ParkingLot> lots = parkingLotRepository.findByBuildingIdOrderBySortOrderAsc(buildingId);
        for (ParkingLot lot : lots) {
            deleteParkingLotInternal(lot);
        }
        buildingRepository.delete(building);
    }

    @Transactional
    public void deleteParkingLot(Long parkingLotId) {
        ParkingLot lot = parkingLotRepository.findById(parkingLotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parking lot not found: " + parkingLotId));
        deleteParkingLotInternal(lot);
    }

    private void deleteParkingLotInternal(ParkingLot lot) {
        savedParkingLocationRepository.deleteByParkingLotId(lot.getId());
        parkingAlertRuleRepository.deleteByParkingLotId(lot.getId());
        String key = lot.getPartitionKey();
        deleteQuietly(assetPathResolver.videoPath(key));
        deleteQuietly(assetPathResolver.sourceImagePath(key));
        deleteQuietly(assetPathResolver.generatedMapPath(key));
        deleteQuietly(assetPathResolver.slotLayoutPath(key));
        parkingLotRepository.delete(lot);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 파일 정리 실패는 무시 (레코드 삭제가 우선)
        }
    }
```

- [ ] **Step 5: 테스트 통과 확인**

Run:
```bash
cd springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests "*BuildingRegistrationServiceTest" -q
```
Expected: PASS (5 tests).

- [ ] **Step 6: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/java/com/smartparking/server/repository/SavedParkingLocationRepository.java \
        springboot/src/main/java/com/smartparking/server/repository/ParkingAlertRuleRepository.java \
        springboot/src/main/java/com/smartparking/server/service/BuildingRegistrationService.java \
        springboot/src/test/java/com/smartparking/server/service/BuildingRegistrationServiceTest.java
git commit -m "feat: 건물/주차장 삭제(연관 레코드·파일 정리) + 테스트"
```

---

## Task 7: 컨트롤러 — 건물/주차장 등록·삭제 API (TDD)

**Files:**
- Create: `springboot/src/main/java/com/smartparking/server/controller/BuildingController.java`
- Create: `springboot/src/main/java/com/smartparking/server/controller/ParkingLotController.java`
- Test: `springboot/src/test/java/com/smartparking/server/controller/BuildingControllerTest.java`

- [ ] **Step 1: 컨트롤러 두 개 작성**

`controller/BuildingController.java`:
```java
package com.smartparking.server.controller;

import com.smartparking.server.dto.BuildingCreateRequest;
import com.smartparking.server.dto.BuildingResponse;
import com.smartparking.server.dto.ParkingLotCreatedResponse;
import com.smartparking.server.service.BuildingRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/buildings")
public class BuildingController {

    private final BuildingRegistrationService service;

    @PostMapping
    public ResponseEntity<BuildingResponse> create(@Valid @RequestBody BuildingCreateRequest request) {
        return ResponseEntity.ok(service.createBuilding(request));
    }

    @DeleteMapping("/{buildingId}")
    public ResponseEntity<Void> delete(@PathVariable Long buildingId) {
        service.deleteBuilding(buildingId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{buildingId}/parking-lots", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ParkingLotCreatedResponse> addParkingLot(
            @PathVariable Long buildingId,
            @RequestParam("name") String name,
            @RequestPart("video") MultipartFile video,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        return ResponseEntity.ok(service.addParkingLot(buildingId, name, video, image));
    }
}
```

`controller/ParkingLotController.java`:
```java
package com.smartparking.server.controller;

import com.smartparking.server.service.BuildingRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/parking-lots")
public class ParkingLotController {

    private final BuildingRegistrationService service;

    @DeleteMapping("/{parkingLotId}")
    public ResponseEntity<Void> delete(@PathVariable Long parkingLotId) {
        service.deleteParkingLot(parkingLotId);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 2: 실패하는 컨트롤러 테스트 작성**

`src/test/java/com/smartparking/server/controller/BuildingControllerTest.java`:
```java
package com.smartparking.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.server.entity.Campus;
import com.smartparking.server.repository.CampusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "smartparking.asset-root=${java.io.tmpdir}/sp-test-assets",
        "spring.datasource.url=jdbc:h2:mem:sptestctl;DB_CLOSE_DELAY=-1"
})
class BuildingControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CampusRepository campusRepository;

    @BeforeEach
    void ensureCampus() {
        if (campusRepository.count() == 0) {
            Campus campus = new Campus();
            campus.setName("c");
            campus.setCenterLat(37.0);
            campus.setCenterLng(127.0);
            campus.setDefaultZoom(17);
            campusRepository.save(campus);
        }
    }

    @Test
    void createBuildingReturnsOkWithMapKey() throws Exception {
        mockMvc.perform(post("/api/buildings")
                        .contentType("application/json")
                        .content("{\"name\":\"테스트동\",\"lat\":37.45,\"lng\":127.13}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapKey").exists())
                .andExpect(jsonPath("$.name").value("테스트동"));
    }

    @Test
    void createBuildingWithMissingNameReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/buildings")
                        .contentType("application/json")
                        .content("{\"lat\":37.45,\"lng\":127.13}"))
                .andExpect(status().isBadRequest());
    }
}
```

> 참고: 이 테스트는 Task 8(보안)에서 신규 엔드포인트가 인증을 요구하게 되면 401이 되므로, Task 8에서 테스트에 인증 우회(`@WithMockUser` 또는 토큰)를 추가한다. 지금 단계(보안 미적용)에서는 통과한다.

- [ ] **Step 3: 테스트 실패 확인**

Run:
```bash
cd springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests "*BuildingControllerTest" -q
```
Expected: FAIL/컴파일 에러 → 구현 후 통과 예상. (Step 1에서 컨트롤러를 이미 만들었다면 이 단계는 바로 통과할 수 있음 — 그 경우 Step 4로.)

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests "*BuildingControllerTest" -q
```
Expected: PASS (2 tests).

- [ ] **Step 5: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/java/com/smartparking/server/controller/BuildingController.java \
        springboot/src/main/java/com/smartparking/server/controller/ParkingLotController.java \
        springboot/src/test/java/com/smartparking/server/controller/BuildingControllerTest.java
git commit -m "feat: 건물/주차장 등록·삭제 REST 컨트롤러 + 테스트"
```

---

## Task 8: 보안 — 신규 엔드포인트 인증 적용 (TDD)

**Files:**
- Modify: `springboot/src/main/java/com/smartparking/server/config/SecurityConfig.java`
- Modify: `springboot/src/test/java/com/smartparking/server/controller/BuildingControllerTest.java`

- [ ] **Step 1: 인증 요구 테스트 추가 + 기존 테스트에 인증 부여**

`BuildingControllerTest.java` 상단 import 추가:
```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import org.springframework.security.test.context.support.WithMockUser;
```

비인증 요청은 401임을 확인하는 테스트 추가:
```java
    @Test
    void createBuildingWithoutAuthIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/buildings")
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"lat\":37.0,\"lng\":127.0}"))
                .andExpect(status().isUnauthorized());
    }
```

그리고 기존 `createBuildingReturnsOkWithMapKey`와 `createBuildingWithMissingNameReturnsBadRequest` 메서드에 `@WithMockUser` 어노테이션을 붙인다(인증 사용자로 호출):
```java
    @Test
    @WithMockUser
    void createBuildingReturnsOkWithMapKey() throws Exception {
```
```java
    @Test
    @WithMockUser
    void createBuildingWithMissingNameReturnsBadRequest() throws Exception {
```

- [ ] **Step 2: 테스트 실패 확인 (아직 보안 미적용)**

Run:
```bash
cd springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests "*BuildingControllerTest" -q
```
Expected: `createBuildingWithoutAuthIsUnauthorized` FAIL (현재 permitAll이라 200/400 반환).

- [ ] **Step 3: SecurityConfig에 인증 규칙 추가**

`SecurityConfig.java` 상단 import 추가:
```java
import org.springframework.http.HttpMethod;
```

`authorizeHttpRequests(...)` 블록에서 기존 `.requestMatchers("/api/campus/**", ...)` 줄(현 44행) **바로 위에** 다음을 추가한다(구체 규칙이 먼저 매칭되도록 순서 중요):
```java
                .requestMatchers(HttpMethod.POST, "/api/buildings", "/api/buildings/*/parking-lots").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/buildings/*", "/api/parking-lots/*").authenticated()
```

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test --tests "*BuildingControllerTest" -q
```
Expected: PASS (3 tests — 비인증 401, 인증 200/400).

- [ ] **Step 5: 전체 테스트 회귀 확인**

Run:
```bash
cd springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test -q
```
Expected: 전체 PASS.

- [ ] **Step 6: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/java/com/smartparking/server/config/SecurityConfig.java \
        springboot/src/test/java/com/smartparking/server/controller/BuildingControllerTest.java
git commit -m "feat: 건물/주차장 등록·삭제 API 인증 요구 + 테스트"
```

---

## Task 9: 프론트 — 지도 클릭으로 건물 등록

**Files:**
- Modify: `springboot/src/main/resources/static/app.js:776-813` (`createNaverMap`)

- [ ] **Step 1: 지도 클릭 리스너 + 건물 등록 핸들러 추가**

`app.js`의 `createNaverMap()` 함수에서 `state.markers = ...` 블록 다음, `elements.mapFallback.classList.add("hidden");` 앞에 지도 클릭 리스너를 추가한다:
```javascript
    naver.maps.Event.addListener(state.map, "click", (e) => {
        if (!state.currentUser) {
            window.alert("건물 등록은 로그인 후 가능합니다.");
            return;
        }
        promptCreateBuilding(e.coord.lat(), e.coord.lng());
    });
```

그리고 파일 하단(예: `focusMarker` 함수 다음)에 등록 핸들러를 추가한다:
```javascript
async function promptCreateBuilding(lat, lng) {
    const name = window.prompt("건물 이름을 입력하세요");
    if (name === null || name.trim() === "") {
        return;
    }
    try {
        await apiRequest("/api/buildings", {
            method: "POST",
            body: JSON.stringify({ name: name.trim(), lat, lng }),
        });
        state.campusMap = await fetchJson("/api/campus/map");
        renderBuildingList();
        recreateMarkers();
    } catch (error) {
        window.alert(`건물 등록 실패: ${error.message}`);
    }
}

function recreateMarkers() {
    if (!state.map || !window.naver?.maps) {
        return;
    }
    state.markers.forEach((m) => m.setMap(null));
    state.markerByBuildingId.clear();
    state.markers = (state.campusMap?.buildings ?? []).map((building) => {
        const marker = new naver.maps.Marker({
            position: new naver.maps.LatLng(building.lat, building.lng),
            map: state.map,
            title: building.name,
        });
        naver.maps.Event.addListener(marker, "click", () => renderSelectedBuilding(building.id));
        state.markerByBuildingId.set(building.id, marker);
        return marker;
    });
}
```

- [ ] **Step 2: 수동 검증 (지도 클릭 등록)**

Run:
```bash
cd /Users/leehnsong/P-Project && ./run.sh
```
브라우저 http://localhost:8080/ 에서: 회원가입/로그인 → 지도 빈 곳 클릭 → 이름 입력 → 새 마커가 즉시 뜨고 좌측 건물 목록에 추가되는지 확인. 비로그인 상태에서 클릭 시 "로그인 후 가능" 경고가 뜨는지 확인. 확인 후 `Ctrl+C`.

Expected: 로그인 시 클릭→이름→마커 생성, 비로그인 시 경고.

- [ ] **Step 3: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/resources/static/app.js
git commit -m "feat(web): 지도 클릭으로 건물 동적 등록"
```

---

## Task 10: 프론트 — Geocoding 검색으로 위치 이동

**Files:**
- Modify: `springboot/src/main/resources/static/index.html:70-76` (map-panel 헤더)
- Modify: `springboot/src/main/resources/static/app.js` (`loadNaverMapScript`, 신규 검색 함수)

- [ ] **Step 1: 검색 UI 추가 (index.html)**

`index.html`의 map-panel 안 `<div id="map" ...>` 바로 위에 검색창을 추가한다:
```html
                <div class="map-search">
                    <input id="map-search-input" type="text" placeholder="주소·지명 검색 후 지도에서 클릭" />
                    <button id="map-search-button" type="button">검색</button>
                </div>
```

- [ ] **Step 2: 지도 SDK에 geocoder 서브모듈 로드**

`app.js`의 `loadNaverMapScript()`에서 스크립트 URL에 `submodules=geocoder`를 추가한다(769행):
```javascript
        script.src = `https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=${encodeURIComponent(clientId)}&submodules=geocoder&language=ko`;
```

- [ ] **Step 3: 검색 바인딩 + 핸들러 추가**

`createNaverMap()` 끝부분(`elements.mapFallback.classList.add("hidden");` 다음)에 검색 바인딩 호출을 추가:
```javascript
    bindMapSearch();
```

파일 하단에 함수 추가:
```javascript
function bindMapSearch() {
    const input = document.getElementById("map-search-input");
    const button = document.getElementById("map-search-button");
    if (!input || !button) {
        return;
    }
    const run = () => searchAndMove(input.value.trim());
    button.addEventListener("click", run);
    input.addEventListener("keydown", (e) => {
        if (e.key === "Enter") {
            run();
        }
    });
}

function searchAndMove(query) {
    if (!query || !window.naver?.maps?.Service) {
        return;
    }
    naver.maps.Service.geocode({ query }, (status, response) => {
        if (status !== naver.maps.Service.Status.OK || !response.v2.addresses.length) {
            window.alert("검색 결과가 없습니다.");
            return;
        }
        const item = response.v2.addresses[0];
        const latLng = new naver.maps.LatLng(Number(item.y), Number(item.x));
        state.map.setCenter(latLng);
        state.map.setZoom(18);
    });
}
```

- [ ] **Step 4: 수동 검증 (검색 이동)**

Run:
```bash
cd /Users/leehnsong/P-Project && ./run.sh
```
브라우저에서 검색창에 "성남대로 1342" 또는 "가천대학교" 입력→검색→지도가 해당 위치로 이동하는지 확인. 이동 후 클릭으로 핀 등록이 되는지도 확인. 확인 후 `Ctrl+C`.

> 사전 조건: NCP 콘솔에서 Geocoding API가 활성화되어 있어야 한다. 비활성 시 검색은 동작하지 않고 경고만 뜬다(지도/클릭 등록은 정상).

Expected: 검색→이동 동작.

- [ ] **Step 5: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/resources/static/index.html springboot/src/main/resources/static/app.js
git commit -m "feat(web): 네이버 Geocoding 검색으로 지도 이동"
```

---

## Task 11: 프론트 — 주차장 추가(영상 업로드) 폼

**Files:**
- Modify: `springboot/src/main/resources/static/app.js:362-388` (`renderSelectedBuilding`), 하단 신규 함수

- [ ] **Step 1: 건물 상세에 "주차장 추가" 영역 + 핸들러**

`renderSelectedBuilding()`에서 `elements.detailContent.innerHTML = lotsHtml || ...;` 줄을 아래로 교체해 상단에 추가 폼을 붙인다:
```javascript
        const addFormHtml = state.currentUser
            ? `
            <div class="lot-card add-lot-card">
                <h3>주차장 추가</h3>
                <form id="add-lot-form" class="add-lot-form">
                    <input name="name" type="text" placeholder="주차장 이름" required />
                    <label>영상(필수) <input name="video" type="file" accept="video/*" required /></label>
                    <label>사진(선택) <input name="image" type="file" accept="image/*" /></label>
                    <button type="submit">업로드</button>
                    <span id="add-lot-progress"></span>
                </form>
                <button type="button" class="danger" data-delete-building="${building.id}">건물 삭제</button>
            </div>`
            : "";
        elements.detailContent.innerHTML = addFormHtml + (lotsHtml || "<div class='lot-card'>주차장 정보가 없습니다.</div>");
        bindBuildingDetailActions(building.id);
```

> 주의: `bindParkingLotActions(lots);` 호출은 그대로 둔다.

- [ ] **Step 2: 추가/삭제 바인딩 함수 작성**

`app.js` 하단에 추가:
```javascript
function bindBuildingDetailActions(buildingId) {
    const form = document.getElementById("add-lot-form");
    if (form) {
        form.addEventListener("submit", (e) => {
            e.preventDefault();
            submitAddParkingLot(buildingId, form);
        });
    }
    const deleteBuildingBtn = document.querySelector(`[data-delete-building="${buildingId}"]`);
    if (deleteBuildingBtn) {
        deleteBuildingBtn.addEventListener("click", () => deleteBuilding(buildingId));
    }
}

async function submitAddParkingLot(buildingId, form) {
    const progress = document.getElementById("add-lot-progress");
    const data = new FormData();
    data.set("name", form.elements.name.value.trim());
    if (form.elements.video.files[0]) {
        data.set("video", form.elements.video.files[0]);
    }
    if (form.elements.image.files[0]) {
        data.set("image", form.elements.image.files[0]);
    }
    try {
        if (progress) {
            progress.textContent = "업로드 중...";
        }
        await apiRequest(`/api/buildings/${buildingId}/parking-lots`, {
            method: "POST",
            body: data,
        });
        if (progress) {
            progress.textContent = "완료";
        }
        await renderSelectedBuilding(buildingId);
    } catch (error) {
        if (progress) {
            progress.textContent = "";
        }
        window.alert(`주차장 추가 실패: ${error.message}`);
    }
}

async function deleteBuilding(buildingId) {
    if (!window.confirm("이 건물과 하위 주차장을 모두 삭제할까요?")) {
        return;
    }
    try {
        await apiRequest(`/api/buildings/${buildingId}`, { method: "DELETE" });
        state.campusMap = await fetchJson("/api/campus/map");
        state.selectedBuildingId = null;
        renderBuildingList();
        recreateMarkers();
        elements.detailContent.innerHTML = "<div class='lot-card'>건물을 선택하세요.</div>";
    } catch (error) {
        window.alert(`건물 삭제 실패: ${error.message}`);
    }
}
```

- [ ] **Step 2: 수동 검증 (주차장 추가)**

Run:
```bash
cd /Users/leehnsong/P-Project && ./run.sh
```
로그인 → 건물 선택 → "주차장 추가"에 이름+작은 영상 파일 선택 → 업로드 → 주차장 카드가 생기는지, 잠시 후(최대 5초) FastAPI가 영상을 잡아 현황 영역이 갱신되는지 확인. "건물 삭제"로 건물이 사라지는지 확인. 확인 후 `Ctrl+C`.

Expected: 업로드→카드 생성, 삭제 동작.

- [ ] **Step 3: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/resources/static/app.js
git commit -m "feat(web): 주차장 추가(영상 업로드) 및 건물 삭제 UI"
```

---

## Task 12: 프론트 — 주차장 삭제 버튼

**Files:**
- Modify: `springboot/src/main/resources/static/app.js:399` (`renderParkingLotCard`), `:584` (`bindParkingLotActions`)

- [ ] **Step 1: 카드에 삭제 버튼 추가**

`renderParkingLotCard(lot)`이 반환하는 카드 HTML에 (로그인 시) 삭제 버튼을 추가한다. 카드 반환 템플릿의 닫는 태그 직전에 다음을 삽입:
```javascript
            ${state.currentUser ? `<button type="button" class="danger" data-delete-lot="${lot.id}">주차장 삭제</button>` : ""}
```

- [ ] **Step 2: 삭제 바인딩 추가**

`bindParkingLotActions(lots)` 함수 맨 끝에 다음을 추가:
```javascript
    document.querySelectorAll("[data-delete-lot]").forEach((btn) => {
        btn.addEventListener("click", async () => {
            const lotId = Number(btn.dataset.deleteLot);
            if (!window.confirm("이 주차장을 삭제할까요?")) {
                return;
            }
            try {
                await apiRequest(`/api/parking-lots/${lotId}`, { method: "DELETE" });
                if (state.selectedBuildingId) {
                    await renderSelectedBuilding(state.selectedBuildingId);
                }
            } catch (error) {
                window.alert(`주차장 삭제 실패: ${error.message}`);
            }
        });
    });
```

- [ ] **Step 3: 수동 검증 (주차장 삭제)**

Run:
```bash
cd /Users/leehnsong/P-Project && ./run.sh
```
로그인 → 건물 선택 → 주차장 카드의 "주차장 삭제" → 카드가 사라지는지 확인. 확인 후 `Ctrl+C`.

Expected: 주차장 카드 삭제 동작.

- [ ] **Step 4: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/resources/static/app.js
git commit -m "feat(web): 주차장 삭제 버튼"
```

---

## Task 13: 최소 스타일 + 최종 회귀 확인

**Files:**
- Modify: `springboot/src/main/resources/static/app.css`

- [ ] **Step 1: 신규 요소 스타일 추가**

`app.css` 맨 아래에 추가:
```css
.map-search { display: flex; gap: 8px; margin-bottom: 8px; }
.map-search input { flex: 1; padding: 6px 8px; }
.add-lot-card .add-lot-form { display: flex; flex-direction: column; gap: 6px; }
.add-lot-card label { font-size: 0.85rem; color: #475467; }
button.danger { background: #d92d20; color: #fff; border: none; padding: 6px 10px; border-radius: 6px; cursor: pointer; }
#add-lot-progress { font-size: 0.85rem; color: #475467; }
```

- [ ] **Step 2: 백엔드 전체 테스트 회귀**

Run:
```bash
cd springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test -q
```
Expected: 전체 PASS.

- [ ] **Step 3: 엔드투엔드 수동 검증**

Run:
```bash
cd /Users/leehnsong/P-Project && rm -rf springboot/data && ./run.sh
```
브라우저에서 전체 흐름 1회: 로그인 → (검색 이동) → 지도 클릭 건물 등록 → 주차장 추가(영상) → 사진 업로드 → "지도 제작하기"로 슬롯 그리기 → 현황 표시 → 주차장/건물 삭제. 그 다음 `Ctrl+C` 후 다시 `./run.sh`로 재기동해 **등록한 건물이 남아있는지(영속성)** 확인.

Expected: 전체 흐름 동작 + 재시작 후 데이터 유지.

- [ ] **Step 4: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/resources/static/app.css
git commit -m "style(web): 검색·주차장 추가·삭제 UI 최소 스타일 + 회귀 확인"
```

---

## Self-Review 메모 (작성자 확인 완료)

- **Spec 커버리지:** 지도 클릭 건물 생성(T9), 주차장 추가+영상 업로드(T5,T11), 삭제(T6,T7,T12), 검색 Geocoding(T10), 영속성 H2 파일(T1), 하드코딩 제거(T2), 업로드 한도(T1), 권한(T8), 테스트(T4–T8) — 설계 문서 각 섹션 대응 확인.
- **타입 일관성:** `BuildingRegistrationService`의 `createBuilding`/`addParkingLot`/`deleteBuilding`/`deleteParkingLot` 시그니처가 컨트롤러·테스트 호출과 일치. `AssetPathResolver`의 `videoPath/sourceImagePath/generatedMapPath/slotLayoutPath`가 서비스 전반에서 동일하게 사용됨.
- **플레이스홀더:** 없음(모든 코드 단계에 실제 코드 포함).
- **알려진 한계:** 대용량 영상 HTTP 업로드 지연(진행률 텍스트로만 대응), Geocoding은 NCP 콘솔 활성화 필요, 슬롯 정의는 기존 맵 빌더 GUI 수동 — 모두 설계의 비목표/리스크와 일치.
