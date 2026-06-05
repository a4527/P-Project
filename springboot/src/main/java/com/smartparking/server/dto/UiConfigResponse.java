package com.smartparking.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UiConfigResponse {

    private String naverMapClientId;

    private CampusMapResponse.CampusData campus;
}
