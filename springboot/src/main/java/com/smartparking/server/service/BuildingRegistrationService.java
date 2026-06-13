package com.smartparking.server.service;

import com.smartparking.server.dto.BuildingCreateRequest;
import com.smartparking.server.dto.BuildingResponse;
import com.smartparking.server.dto.ParkingLotCreatedResponse;
import com.smartparking.server.entity.Building;
import com.smartparking.server.entity.Campus;
import com.smartparking.server.entity.ParkingLot;
import com.smartparking.server.repository.BuildingRepository;
import com.smartparking.server.repository.CampusRepository;
import com.smartparking.server.repository.ParkingAlertRuleRepository;
import com.smartparking.server.repository.ParkingLotRepository;
import com.smartparking.server.repository.SavedParkingLocationRepository;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private final AssetPathResolver assetPathResolver;
    private final SavedParkingLocationRepository savedParkingLocationRepository;
    private final ParkingAlertRuleRepository parkingAlertRuleRepository;

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

    @Transactional
    public ParkingLotCreatedResponse addParkingLot(
            Long buildingId, String name, MultipartFile video, MultipartFile image) {
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Building not found: " + buildingId));
        if (video == null || video.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video file is required");
        }

        String partitionKey = generateUniquePartitionKey(building);

        ParkingLot lot = new ParkingLot();
        lot.setBuilding(building);
        lot.setName(name != null && !name.isBlank() ? name : partitionKey);
        lot.setPartitionKey(partitionKey);
        lot.setMapImageUrl(null);
        lot.setSlotLayoutJson(null);
        lot.setSortOrder(parkingLotRepository.findByBuildingIdOrderBySortOrderAsc(buildingId).size() + 1);
        parkingLotRepository.save(lot);

        try {
            storeVideo(partitionKey, video);
            if (image != null && !image.isEmpty()) {
                storeImage(partitionKey, image);
            }
        } catch (RuntimeException e) {
            deleteQuietly(assetPathResolver.videoPath(partitionKey));
            deleteQuietly(assetPathResolver.sourceImagePath(partitionKey));
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

    private void storeVideo(String partitionKey, MultipartFile video) {
        Path target = assetPathResolver.videoPath(partitionKey);
        try {
            Files.createDirectories(target.getParent());
            try (java.io.InputStream in = video.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store video", e);
        }
    }

    private void storeImage(String partitionKey, MultipartFile image) {
        Path target = assetPathResolver.sourceImagePath(partitionKey);
        try {
            Files.createDirectories(target.getParent());
            BufferedImage img;
            try (java.io.InputStream in = image.getInputStream()) {
                img = ImageIO.read(in);
            }
            if (img == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported image format");
            }
            ImageIO.write(img, "png", target.toFile());
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
        String key = lot.getPartitionKey();
        deleteQuietly(assetPathResolver.videoPath(key));
        deleteQuietly(assetPathResolver.sourceImagePath(key));
        deleteQuietly(assetPathResolver.generatedMapPath(key));
        deleteQuietly(assetPathResolver.slotLayoutPath(key));
        parkingLotRepository.delete(lot);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
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
