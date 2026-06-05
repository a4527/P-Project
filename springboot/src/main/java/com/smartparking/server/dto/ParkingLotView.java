package com.smartparking.server.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParkingLotView {

    private Long id;
    private String name;
    private String partitionKey;
    private String mapImageUrl;
    private String slotLayoutJson;
    private boolean sourceImageExists;
    private boolean generatedMapExists;
    private String sourceImageUrl;
    private String generatedMapUrl;
    private String statusMessage;
    private Summary summary;
    private List<Slot> slots = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private String status;
        private Integer totalSlots;
        private Integer availableSlots;
        private Integer disabledAvailable;
        private Double lastUpdate;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Slot {
        private String partitionKey;
        private Integer slotId;
        private String type;
        private String status;
        private List<Double> center = new ArrayList<>();
    }
}
