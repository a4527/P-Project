package com.smartparking.server.repository;

import com.smartparking.server.entity.ParkingLotAsset;
import com.smartparking.server.entity.ParkingLotAssetType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingLotAssetRepository extends JpaRepository<ParkingLotAsset, Long> {
    Optional<ParkingLotAsset> findByParkingLotIdAndAssetType(Long parkingLotId, ParkingLotAssetType assetType);

    List<ParkingLotAsset> findByParkingLotId(Long parkingLotId);

    void deleteByParkingLotId(Long parkingLotId);
}
