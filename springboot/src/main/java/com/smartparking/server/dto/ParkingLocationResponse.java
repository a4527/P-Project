package com.smartparking.server.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ParkingLocationResponse {
    private Long id;
    private Long parkingLotId;
    private String parkingLotName;
    private String partitionKey;
    private Integer slotId;
    private String vehicleLabel;
    private String memo;
    private boolean active;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private LocalDateTime savedAt;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private LocalDateTime releasedAt;
}
