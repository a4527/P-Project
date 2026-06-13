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
