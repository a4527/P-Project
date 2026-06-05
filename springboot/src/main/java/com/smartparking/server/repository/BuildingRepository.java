package com.smartparking.server.repository;

import com.smartparking.server.entity.Building;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuildingRepository extends JpaRepository<Building, Long> {
    List<Building> findByCampusIdOrderBySortOrderAsc(Long campusId);

    Optional<Building> findByCampusIdAndName(Long campusId, String name);

    Optional<Building> findByMapKey(String mapKey);
}
