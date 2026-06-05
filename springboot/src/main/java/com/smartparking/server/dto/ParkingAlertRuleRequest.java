package com.smartparking.server.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ParkingAlertRuleRequest {
    private Long parkingLotId;
    private Integer minimumAvailableSlots;
    private Boolean enabled;
}
