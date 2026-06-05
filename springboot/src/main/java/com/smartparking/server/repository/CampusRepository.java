package com.smartparking.server.repository;

import com.smartparking.server.entity.Campus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampusRepository extends JpaRepository<Campus, Long> {
    Optional<Campus> findByName(String name);
}
