package com.smartparking.server.service;

import com.smartparking.server.entity.ParkingAlertRule;
import com.smartparking.server.entity.ParkingLot;
import com.smartparking.server.repository.ParkingAlertRuleRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParkingAlertMonitorService {

    private final ParkingAlertRuleRepository ruleRepository;
    private final ParkingStatusService parkingStatusService;
    private final InAppNotificationService notificationService;

    @Scheduled(fixedRate = 15000)
    @Transactional
    public void monitorAvailability() {
        for (ParkingAlertRule rule : ruleRepository.findByEnabledTrue()) {
            ParkingLot parkingLot = rule.getParkingLot();
            var partitionData = parkingStatusService.getPartitionData(parkingLot.getPartitionKey());
            if (partitionData == null || partitionData.getSummary() == null) {
                continue;
            }

            int available = partitionData.getSummary().getAvailable();
            Integer threshold = rule.getMinimumAvailableSlots();
            Integer lastKnown = rule.getLastKnownAvailableSlots();

            if (available >= threshold && (lastKnown == null || lastKnown < threshold)) {
                String title = parkingLot.getName() + " 빈자리 알림";
                String message = parkingLot.getName() + "에 현재 " + available + "자리가 남았습니다.";
                notificationService.createNotification(rule.getUser().getUsername(), title, message, "parking-alert");
                rule.setLastTriggeredAt(LocalDateTime.now());
            }

            rule.setLastKnownAvailableSlots(available);
            ruleRepository.save(rule);
        }
    }
}
