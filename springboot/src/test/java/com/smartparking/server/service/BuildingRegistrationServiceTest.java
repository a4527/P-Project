package com.smartparking.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartparking.server.dto.BuildingCreateRequest;
import com.smartparking.server.dto.BuildingResponse;
import com.smartparking.server.dto.ParkingLotCreatedResponse;
import com.smartparking.server.entity.Campus;
import com.smartparking.server.entity.ParkingLotAssetType;
import com.smartparking.server.TestcontainersConfiguration;
import com.smartparking.server.repository.BuildingRepository;
import com.smartparking.server.repository.CampusRepository;
import com.smartparking.server.repository.ParkingLotAssetRepository;
import com.smartparking.server.repository.ParkingLotRepository;
import com.smartparking.server.service.storage.StorageService;
import com.smartparking.server.service.storage.StoredObject;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class BuildingRegistrationServiceTest {

    @Autowired
    private BuildingRegistrationService service;
    @Autowired
    private CampusRepository campusRepository;
    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private ParkingLotRepository parkingLotRepository;
    @Autowired
    private ParkingLotAssetRepository parkingLotAssetRepository;
    @MockitoBean
    private StorageService storageService;

    @BeforeEach
    void ensureCampus() {
        when(storageService.put(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenAnswer(invocation -> new StoredObject(
                        invocation.getArgument(0),
                        invocation.getArgument(3),
                        invocation.getArgument(2)));
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

    @Test
    void addsParkingLotAndStoresVideoMetadataAndObject() throws Exception {
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
        verify(storageService).put(
                eq("parking-lots/" + lot.getPartitionKey() + "/video.mp4"),
                any(InputStream.class), eq(11L), eq("video/mp4"));
        assertThat(parkingLotAssetRepository.findByParkingLotIdAndAssetType(lot.getId(), ParkingLotAssetType.VIDEO))
                .hasValueSatisfying(asset -> {
            assertThat(asset.getObjectKey()).isEqualTo("parking-lots/" + lot.getPartitionKey() + "/video.mp4");
            assertThat(asset.getSizeBytes()).isEqualTo(11);
        });
    }

    private boolean parkingLotRepositoryExists(Long id) {
        return parkingLotRepository.findById(id).isPresent();
    }

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
        service.deleteParkingLot(lot.getId());

        assertThat(parkingLotRepositoryExists(lot.getId())).isFalse();
        verify(storageService).delete("parking-lots/" + lot.getPartitionKey() + "/video.mp4");
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
}
