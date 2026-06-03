package com.smartparking.server.controller;

import com.smartparking.server.dto.ParkingStatusResponse;
import com.smartparking.server.service.ParkingStatusService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/parking")
@RequiredArgsConstructor
public class ParkingStatusController {

    private final ParkingStatusService parkingStatusService;

    @GetMapping("/status")
    public ResponseEntity<ParkingStatusResponse> getParkingStatus() {

        ParkingStatusResponse status = parkingStatusService.getCachedStatus();

        if (status == null) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(status);
    }
}
