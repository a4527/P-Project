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
