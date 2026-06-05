package com.smartparking.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BuildingMapResponse {

    private Long buildingId;
    private String buildingName;
    private String mapKey;
    private boolean sourceImageExists;
    private boolean generatedMapExists;
    private String sourceImageUrl;
    private String generatedMapUrl;
    private String slotLayoutJson;
    private String statusMessage;
}
