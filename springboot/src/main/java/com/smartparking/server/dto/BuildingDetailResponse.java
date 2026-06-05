package com.smartparking.server.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BuildingDetailResponse {

    private CampusMapResponse.CampusData campus;
    private CampusMapResponse.BuildingView building;
    private List<ParkingLotView> parkingLots = new ArrayList<>();
}
