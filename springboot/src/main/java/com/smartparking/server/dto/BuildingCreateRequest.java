package com.smartparking.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BuildingCreateRequest {
    @NotBlank
    private String name;
    @NotNull
    private Double lat;
    @NotNull
    private Double lng;
}
