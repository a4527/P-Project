package com.smartparking.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ParkingLotCreatedResponse {
    private Long id;
    private Long buildingId;
    private String name;
    private String partitionKey;
}
