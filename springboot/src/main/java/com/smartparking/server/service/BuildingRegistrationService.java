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
