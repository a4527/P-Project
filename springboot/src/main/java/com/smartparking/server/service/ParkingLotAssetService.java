package com.smartparking.server.service;

import com.smartparking.server.entity.ParkingLot;
import com.smartparking.server.entity.ParkingLotAsset;
import com.smartparking.server.entity.ParkingLotAssetType;
import com.smartparking.server.entity.User;
import com.smartparking.server.repository.ParkingLotAssetRepository;
import com.smartparking.server.service.storage.StoredObject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ParkingLotAssetService {

    private final ParkingLotAssetRepository parkingLotAssetRepository;

    public ParkingLotAsset upsert(
            ParkingLot parkingLot,
            ParkingLotAssetType assetType,
            StoredObject storedObject,
            String originalFilename,
            User uploadedBy) {
        ParkingLotAsset asset = parkingLotAssetRepository
                .findByParkingLotIdAndAssetType(parkingLot.getId(), assetType)
                .orElseGet(ParkingLotAsset::new);
        Instant now = Instant.now();
        if (asset.getId() == null) {
            asset.setParkingLot(parkingLot);
            asset.setAssetType(assetType);
            asset.setCreatedAt(now);
        }
        asset.setObjectKey(storedObject.getKey());
        asset.setOriginalFilename(originalFilename);
        asset.setContentType(storedObject.getContentType());
        asset.setSizeBytes(storedObject.getSizeBytes());
        asset.setUploadedBy(uploadedBy);
        asset.setUpdatedAt(now);
        return parkingLotAssetRepository.save(asset);
    }

    public Optional<ParkingLotAsset> find(ParkingLot parkingLot, ParkingLotAssetType assetType) {
        if (parkingLot.getId() == null) {
            return Optional.empty();
        }
        return parkingLotAssetRepository.findByParkingLotIdAndAssetType(parkingLot.getId(), assetType);
    }

    public List<ParkingLotAsset> findAll(ParkingLot parkingLot) {
        return parkingLotAssetRepository.findByParkingLotId(parkingLot.getId());
    }

    public void deleteMetadata(ParkingLot parkingLot) {
        parkingLotAssetRepository.deleteByParkingLotId(parkingLot.getId());
    }
}
