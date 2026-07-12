package com.smartparking.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParkingLotMapResponse {

    private Long parkingLotId;
    private String parkingLotName;
    private String partitionKey;
    private boolean sourceImageExists;
    private String sourceImageUrl;
    private String slotLayoutJson;
    private String statusMessage;
}
