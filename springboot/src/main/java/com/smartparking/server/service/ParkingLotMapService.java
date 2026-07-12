package com.smartparking.server.service;

import com.smartparking.server.dto.ParkingLotMapResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.server.entity.ParkingLot;
import com.smartparking.server.entity.ParkingLotAsset;
import com.smartparking.server.entity.ParkingLotAssetType;
import com.smartparking.server.entity.User;
import com.smartparking.server.repository.ParkingLotRepository;
import com.smartparking.server.service.storage.StoredObject;
import com.smartparking.server.service.storage.StorageService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ParkingLotMapService {

    private final ParkingLotRepository parkingLotRepository;
    private final StorageService storageService;
    private final CurrentUserService currentUserService;
    private final ParkingLotAssetService parkingLotAssetService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional(readOnly = true)
    public ParkingLotMapResponse getMap(Long parkingLotId) {
        ParkingLot parkingLot = getParkingLot(parkingLotId);
        return toResponse(parkingLot, "주차장 맵 상태를 불러왔습니다.");
    }

    public ParkingLotMapResponse uploadSourceImage(Long parkingLotId, MultipartFile file) {
        ParkingLot parkingLot = getParkingLot(parkingLotId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload file is required");
        }

        try {
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported image format");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            byte[] bytes = out.toByteArray();
            String key = sourceImageKey(parkingLot);
            StoredObject stored = storageService.put(key, new ByteArrayInputStream(bytes), bytes.length, "image/png");
            User user = currentUserService.currentUserOrNull();
            parkingLotAssetService.upsert(
                    parkingLot,
                    ParkingLotAssetType.SOURCE_IMAGE,
                    stored,
                    file.getOriginalFilename(),
                    user);
            parkingLot.setSlotLayoutJson(null);
            parkingLotRepository.save(parkingLot);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store uploaded image", e);
        }

        return toResponse(parkingLot, "사진 업로드가 완료되었습니다.");
    }

    @Transactional
    public ParkingLotMapResponse saveSlotLayout(Long parkingLotId, String slotLayoutJson) {
        ParkingLot parkingLot = getParkingLot(parkingLotId);
        if (slotLayoutJson == null || slotLayoutJson.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot layout is required");
        }
        try {
            JsonNode root = objectMapper.readTree(slotLayoutJson);
            if (!root.isArray()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot layout must be a JSON array");
            }
            parkingLot.setSlotLayoutJson(objectMapper.writeValueAsString(root));
            parkingLotRepository.save(parkingLot);
            return toResponse(parkingLot, "슬롯 레이아웃을 저장했습니다.");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid slot layout JSON", e);
        }
    }

    @Transactional(readOnly = true)
    public byte[] readSourceImage(Long parkingLotId) {
        ParkingLot parkingLot = getParkingLot(parkingLotId);
        ParkingLotAsset sourceImage = requireAsset(parkingLot, ParkingLotAssetType.SOURCE_IMAGE, "Source image not found");
        if (!storageService.exists(sourceImage.getObjectKey())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source image not found");
        }
        return storageService.getBytes(sourceImage.getObjectKey());
    }

    @Transactional(readOnly = true)
    public String readSlotLayoutJson(Long parkingLotId) {
        ParkingLot parkingLot = getParkingLot(parkingLotId);
        if (parkingLot.getSlotLayoutJson() == null || parkingLot.getSlotLayoutJson().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot layout not found");
        }
        return parkingLot.getSlotLayoutJson();
    }

    @Transactional(readOnly = true)
    public ParkingLotMapResponse snapshot(ParkingLot parkingLot) {
        return toResponse(parkingLot, "주차장 맵 상태를 불러왔습니다.");
    }

    private ParkingLot getParkingLot(Long parkingLotId) {
        return parkingLotRepository.findById(parkingLotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parking lot not found: " + parkingLotId));
    }

    private ParkingLotMapResponse toResponse(ParkingLot parkingLot, String message) {
        ParkingLotAsset sourceImage = parkingLotAssetService.find(parkingLot, ParkingLotAssetType.SOURCE_IMAGE).orElse(null);
        boolean sourceImageExists = sourceImage != null && storageService.exists(sourceImage.getObjectKey());
        boolean layoutExists = parkingLot.getSlotLayoutJson() != null
                && !parkingLot.getSlotLayoutJson().isBlank();
        String slotLayoutJson = layoutExists ? parkingLot.getSlotLayoutJson() : null;
        return new ParkingLotMapResponse(
                parkingLot.getId(),
                parkingLot.getName(),
                parkingLot.getPartitionKey(),
                sourceImageExists,
                sourceImageExists ? "/api/parking-lots/" + parkingLot.getId() + "/map/source-image" : null,
                slotLayoutJson,
                message);
    }

    private String sourceImageKey(ParkingLot parkingLot) {
        return "parking-lots/" + parkingLot.getPartitionKey() + "/source-image.png";
    }

    private ParkingLotAsset requireAsset(ParkingLot parkingLot, ParkingLotAssetType assetType, String message) {
        return parkingLotAssetService.find(parkingLot, assetType)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, message));
    }

}
