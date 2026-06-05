package com.smartparking.server.controller;

import com.smartparking.server.dto.UiConfigResponse;
import com.smartparking.server.service.CampusMapService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ui")
public class UiController {

    private final CampusMapService campusMapService;

    @Value("${smartparking.naver-map.client-id:}")
    private String naverMapClientId;

    @GetMapping("/config")
    public ResponseEntity<UiConfigResponse> getConfig() {
        return ResponseEntity.ok(campusMapService.getUiConfig(naverMapClientId));
    }
}
