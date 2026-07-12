package com.smartparking.server.service;

import com.smartparking.server.dto.BuildingCreateRequest;
import com.smartparking.server.dto.BuildingResponse;
import com.smartparking.server.dto.ParkingLotCreatedResponse;
import com.smartparking.server.entity.Building;
import com.smartparking.server.entity.Campus;
import com.smartparking.server.entity.ParkingLot;
import com.smartparking.server.entity.ParkingLotAsset;
import com.smartparking.server.entity.ParkingLotAssetType;
import com.smartparking.server.entity.User;
import com.smartparking.server.repository.BuildingRepository;
import com.smartparking.server.repository.CampusRepository;
import com.smartparking.server.repository.ParkingAlertRuleRepository;
import com.smartparking.server.repository.ParkingLotRepository;
import com.smartparking.server.repository.SavedParkingLocationRepository;
import com.smartparking.server.service.storage.StoredObject;
import com.smartparking.server.service.storage.StorageService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class BuildingRegistrationService {

    private final CampusRepository campusRepository;
    private final BuildingRepository buildingRepository;
    private final ParkingLotRepository parkingLotRepository;
    private final SavedParkingLocationRepository savedParkingLocationRepository;
    private final ParkingAlertRuleRepository parkingAlertRuleRepository;
    private final StorageService storageService;
    private final CurrentUserService currentUserService;
    private final ParkingLotAssetService parkingLotAssetService;

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
        building.setCreatedBy(currentUserService.currentUserOrNull());
        buildingRepository.save(building);

        return toResponse(building);
    }

    @Transactional
    public ParkingLotCreatedResponse addParkingLot(
            Long buildingId, String name, MultipartFile video, MultipartFile image) {
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Building not found: " + buildingId));
        if (video == null || video.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video file is required");
        }

        String partitionKey = generateUniquePartitionKey(building);
        User currentUser = currentUserService.currentUserOrNull();

        ParkingLot lot = new ParkingLot();
        lot.setBuilding(building);
        lot.setName(name != null && !name.isBlank() ? name : partitionKey);
        lot.setPartitionKey(partitionKey);
        lot.setMapImageUrl(null);
        lot.setSlotLayoutJson(null);
        lot.setSortOrder(parkingLotRepository.findByBuildingIdOrderBySortOrderAsc(buildingId).size() + 1);
        lot.setCreatedBy(currentUser);
        parkingLotRepository.save(lot);

        try {
            storeVideo(lot, video, currentUser);
            if (image != null && !image.isEmpty()) {
                storeImage(lot, image, currentUser);
            }
        } catch (RuntimeException e) {
            parkingLotAssetService.findAll(lot)
                    .forEach(asset -> storageService.delete(asset.getObjectKey()));
            throw e;
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

    private ParkingLotAsset storeVideo(ParkingLot lot, MultipartFile video, User user) {
        String key = "parking-lots/" + lot.getPartitionKey() + "/video.mp4";
        try {
            try (java.io.InputStream in = video.getInputStream()) {
                StoredObject stored = storageService.put(key, in, video.getSize(), contentType(video, "video/mp4"));
                return parkingLotAssetService.upsert(
                        lot,
                        ParkingLotAssetType.VIDEO,
                        stored,
                        video.getOriginalFilename(),
                        user);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store video", e);
        }
    }

    private ParkingLotAsset storeImage(ParkingLot lot, MultipartFile image, User user) {
        String key = "parking-lots/" + lot.getPartitionKey() + "/source-image.png";
        try {
            BufferedImage img;
            try (java.io.InputStream in = image.getInputStream()) {
                img = ImageIO.read(in);
            }
            if (img == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported image format");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            byte[] bytes = out.toByteArray();
            StoredObject stored = storageService.put(key, new ByteArrayInputStream(bytes), bytes.length, "image/png");
            return parkingLotAssetService.upsert(
                    lot,
                    ParkingLotAssetType.SOURCE_IMAGE,
                    stored,
                    image.getOriginalFilename(),
                    user);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store image", e);
        }
    }

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
        parkingLotAssetService.findAll(lot).forEach(asset -> storageService.delete(asset.getObjectKey()));
        parkingLotAssetService.deleteMetadata(lot);
        parkingLotRepository.delete(lot);
    }

    private String contentType(MultipartFile file, String fallback) {
        String contentType = file.getContentType();
        return contentType == null || contentType.isBlank() ? fallback : contentType;
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
