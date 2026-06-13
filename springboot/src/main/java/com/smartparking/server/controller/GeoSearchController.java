package com.smartparking.server.controller;

import com.smartparking.server.dto.GeoSearchResult;
import com.smartparking.server.service.NaverSearchService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/geo")
public class GeoSearchController {

    private final NaverSearchService naverSearchService;

    @GetMapping("/search")
    public List<GeoSearchResult> search(@RequestParam String query) {
        return naverSearchService.search(query);
    }
}
