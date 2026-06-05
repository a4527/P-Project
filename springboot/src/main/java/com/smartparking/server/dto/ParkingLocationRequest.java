package com.smartparking.server.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ParkingLocationRequest {
    private Long parkingLotId;
    private Integer slotId;
    private String vehicleLabel;
    private String memo;
}
