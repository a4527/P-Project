package com.smartparking.server.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ParkingAlertRuleResponse {
    private Long id;
    private Long parkingLotId;
    private String parkingLotName;
    private Integer minimumAvailableSlots;
    private boolean enabled;
    private Integer lastKnownAvailableSlots;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private LocalDateTime lastTriggeredAt;
}
