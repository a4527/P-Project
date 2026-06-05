package com.smartparking.server.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CampusMapResponse {

    private CampusData campus;
    private List<BuildingView> buildings = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CampusData {
        private Long id;
        private String name;
        private Double centerLat;
        private Double centerLng;
        private Integer defaultZoom;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BuildingView {
        private Long id;
        private String name;
        private String mapKey;
        private Double lat;
        private Double lng;
        private Integer sortOrder;
        private List<ParkingLotView> parkingLots = new ArrayList<>();
    }
}
