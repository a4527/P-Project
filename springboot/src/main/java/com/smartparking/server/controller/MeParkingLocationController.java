package com.smartparking.server.controller;

import com.smartparking.server.dto.ParkingLocationRequest;
import com.smartparking.server.dto.ParkingLocationResponse;
import com.smartparking.server.service.ParkingLocationService;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/me/parking-location")
public class MeParkingLocationController {

    private final ParkingLocationService parkingLocationService;

    @PostMapping
    public ResponseEntity<ParkingLocationResponse> save(
            Principal principal,
            @RequestBody ParkingLocationRequest request) {
        return ResponseEntity.ok(parkingLocationService.saveCurrentLocation(principal.getName(), request));
    }

    @GetMapping("/current")
    public ResponseEntity<ParkingLocationResponse> current(Principal principal) {
        ParkingLocationResponse response = parkingLocationService.getCurrentLocation(principal.getName());
        return response == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(response);
    }

    @DeleteMapping("/current")
    public ResponseEntity<ParkingLocationResponse> release(Principal principal) {
        ParkingLocationResponse response = parkingLocationService.releaseCurrentLocation(principal.getName());
        return response == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(response);
    }
}
