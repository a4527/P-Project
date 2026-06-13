package com.smartparking.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GeoSearchResult {
    private String name;
    private double lat;
    private double lng;
    private String address;
}
