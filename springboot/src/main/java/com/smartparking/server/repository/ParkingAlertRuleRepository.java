package com.smartparking.server.repository;

import com.smartparking.server.entity.ParkingAlertRule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingAlertRuleRepository extends JpaRepository<ParkingAlertRule, Long> {
    List<ParkingAlertRule> findByEnabledTrue();

    List<ParkingAlertRule> findByUserUsernameOrderByIdDesc(String username);
}
