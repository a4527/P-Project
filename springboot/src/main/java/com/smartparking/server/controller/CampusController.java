package com.smartparking.server.controller;

import com.smartparking.server.dto.BuildingDetailResponse;
import com.smartparking.server.dto.CampusMapResponse;
import com.smartparking.server.service.CampusMapService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class CampusController {

    private final CampusMapService campusMapService;

    @GetMapping("/campus/map")
    public ResponseEntity<CampusMapResponse> getCampusMap() {
        return ResponseEntity.ok(campusMapService.getCampusMap());
    }

    @GetMapping("/campus/buildings/{buildingId}")
    public ResponseEntity<BuildingDetailResponse> getBuildingDetail(@PathVariable Long buildingId) {
        return ResponseEntity.ok(campusMapService.getBuildingDetail(buildingId));
    }
}
