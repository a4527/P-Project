package com.smartparking.server.service;

import com.smartparking.server.dto.ParkingLocationRequest;
import com.smartparking.server.dto.ParkingLocationResponse;
import com.smartparking.server.entity.ParkingLot;
import com.smartparking.server.entity.SavedParkingLocation;
import com.smartparking.server.entity.User;
import com.smartparking.server.repository.ParkingLotRepository;
import com.smartparking.server.repository.SavedParkingLocationRepository;
import com.smartparking.server.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ParkingLocationService {

    private final UserRepository userRepository;
    private final ParkingLotRepository parkingLotRepository;
    private final SavedParkingLocationRepository savedParkingLocationRepository;

    @Transactional
    public ParkingLocationResponse saveCurrentLocation(String username, ParkingLocationRequest request) {
        if (request == null || request.getParkingLotId() == null || request.getSlotId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parkingLotId and slotId are required");
        }

        User user = getUser(username);
        ParkingLot parkingLot = parkingLotRepository.findById(request.getParkingLotId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parking lot not found"));

        releaseAllActive(user);

        SavedParkingLocation location = new SavedParkingLocation();
        location.setUser(user);
        location.setParkingLot(parkingLot);
        location.setSlotId(request.getSlotId());
        location.setVehicleLabel(request.getVehicleLabel());
        location.setMemo(request.getMemo());
        location.setActive(true);
        location.setSavedAt(LocalDateTime.now());
        savedParkingLocationRepository.save(location);

        return toResponse(location);
    }

    @Transactional(readOnly = true)
    public ParkingLocationResponse getCurrentLocation(String username) {
        return savedParkingLocationRepository.findFirstByUserUsernameAndActiveTrueOrderBySavedAtDesc(username)
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional
    public ParkingLocationResponse releaseCurrentLocation(String username) {
        SavedParkingLocation current = savedParkingLocationRepository
                .findFirstByUserUsernameAndActiveTrueOrderBySavedAtDesc(username)
                .orElse(null);

        if (current == null) {
            return null;
        }

        current.setActive(false);
        current.setReleasedAt(LocalDateTime.now());
        savedParkingLocationRepository.save(current);
        return toResponse(current);
    }

    private void releaseAllActive(User user) {
        for (SavedParkingLocation active : savedParkingLocationRepository.findByUserUsernameAndActiveTrue(user.getUsername())) {
            active.setActive(false);
            active.setReleasedAt(LocalDateTime.now());
            savedParkingLocationRepository.save(active);
        }
    }

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private ParkingLocationResponse toResponse(SavedParkingLocation location) {
        return new ParkingLocationResponse(
                location.getId(),
                location.getParkingLot().getId(),
                location.getParkingLot().getName(),
                location.getParkingLot().getPartitionKey(),
                location.getSlotId(),
                location.getVehicleLabel(),
                location.getMemo(),
                location.isActive(),
                location.getSavedAt(),
                location.getReleasedAt());
    }
}
