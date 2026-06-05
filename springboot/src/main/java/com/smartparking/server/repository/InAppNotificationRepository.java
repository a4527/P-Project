package com.smartparking.server.repository;

import com.smartparking.server.entity.InAppNotification;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InAppNotificationRepository extends JpaRepository<InAppNotification, Long> {
    List<InAppNotification> findByUserUsernameOrderByCreatedAtDesc(String username);

    long countByUserUsernameAndReadFlagFalse(String username);
}
