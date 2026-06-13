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
