package com.smartparking.server.repository;

import com.smartparking.server.entity.ParkingLot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingLotRepository extends JpaRepository<ParkingLot, Long> {
    List<ParkingLot> findByBuildingIdOrderBySortOrderAsc(Long buildingId);

    Optional<ParkingLot> findByPartitionKey(String partitionKey);

    boolean existsByPartitionKey(String partitionKey);
}
