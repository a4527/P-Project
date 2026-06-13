package com.smartparking.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BuildingResponse {
    private Long id;
    private String name;
    private String mapKey;
    private Double lat;
    private Double lng;
    private Integer sortOrder;
}
