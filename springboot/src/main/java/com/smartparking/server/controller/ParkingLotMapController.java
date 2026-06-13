package com.smartparking.server.controller;

import com.smartparking.server.dto.ParkingLotMapResponse;
import com.smartparking.server.service.ParkingLotMapService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/parking-lots/{parkingLotId}/map")
public class ParkingLotMapController {

    private final ParkingLotMapService parkingLotMapService;

    @GetMapping
    public ResponseEntity<ParkingLotMapResponse> getMap(@PathVariable Long parkingLotId) {
        return ResponseEntity.ok(parkingLotMapService.getMap(parkingLotId));
    }

    @GetMapping(value = "/source-image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<ByteArrayResource> getSourceImage(@PathVariable Long parkingLotId) {
        return imageResponse(parkingLotMapService.readSourceImage(parkingLotId));
    }

    @GetMapping(value = "/generated-image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<ByteArrayResource> getGeneratedImage(@PathVariable Long parkingLotId) {
        return imageResponse(parkingLotMapService.readGeneratedMapImage(parkingLotId));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ParkingLotMapResponse> upload(
            @PathVariable Long parkingLotId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(parkingLotMapService.uploadSourceImage(parkingLotId, file));
    }

    @PostMapping(value = "/polygon-spec", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ParkingLotMapResponse> uploadPolygonSpec(
            @PathVariable Long parkingLotId,
            @RequestBody String specJson) {
        return ResponseEntity.ok(parkingLotMapService.uploadAutoSpec(parkingLotId, specJson));
    }

    @GetMapping(value = "/polygon-spec", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPolygonSpec(@PathVariable Long parkingLotId) {
        return ResponseEntity.ok(parkingLotMapService.readAutoSpecJson(parkingLotId));
    }

    @PostMapping("/build")
    public ResponseEntity<ParkingLotMapResponse> build(@PathVariable Long parkingLotId) {
        return ResponseEntity.ok(parkingLotMapService.launchMapBuilder(parkingLotId));
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
