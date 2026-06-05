package com.smartparking.server.controller;

import com.smartparking.server.dto.BuildingMapResponse;
import com.smartparking.server.service.BuildingMapService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/campus/buildings/{buildingId}/map")
public class BuildingMapController {

    private final BuildingMapService buildingMapService;

    @GetMapping
    public ResponseEntity<BuildingMapResponse> getMap(@PathVariable Long buildingId) {
        return ResponseEntity.ok(buildingMapService.getMap(buildingId));
    }

    @GetMapping(value = "/source-image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<ByteArrayResource> getSourceImage(@PathVariable Long buildingId) {
        return imageResponse(buildingMapService.readSourceImage(buildingId));
    }

    @GetMapping(value = "/generated-image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<ByteArrayResource> getGeneratedImage(@PathVariable Long buildingId) {
        return imageResponse(buildingMapService.readGeneratedMapImage(buildingId));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BuildingMapResponse> upload(
            @PathVariable Long buildingId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(buildingMapService.uploadSourceImage(buildingId, file));
    }

    @PostMapping("/build")
    public ResponseEntity<BuildingMapResponse> build(@PathVariable Long buildingId) {
        return ResponseEntity.ok(buildingMapService.launchMapBuilder(buildingId));
    }

    private ResponseEntity<ByteArrayResource> imageResponse(byte[] bytes) {
        ByteArrayResource resource = new ByteArrayResource(bytes);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(bytes.length)
                .body(resource);
    }
}
