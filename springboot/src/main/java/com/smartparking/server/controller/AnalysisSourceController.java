package com.smartparking.server.controller;

import com.smartparking.server.dto.AnalysisSourceResponse;
import com.smartparking.server.entity.ParkingLot;
import com.smartparking.server.entity.ParkingLotAsset;
import com.smartparking.server.entity.ParkingLotAssetType;
import com.smartparking.server.repository.ParkingLotAssetRepository;
import com.smartparking.server.repository.ParkingLotRepository;
import com.smartparking.server.service.storage.StorageService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/analysis")
public class AnalysisSourceController {

    private final ParkingLotRepository parkingLotRepository;
    private final ParkingLotAssetRepository parkingLotAssetRepository;
    private final StorageService storageService;

    @GetMapping("/sources")
    public AnalysisSourceResponse sources() {
        List<AnalysisSourceResponse.Source> sources = parkingLotRepository.findAll().stream()
                .filter(lot -> lot.getPartitionKey() != null
                        && lot.getSlotLayoutJson() != null
                        && !lot.getSlotLayoutJson().isBlank()
                        && videoAsset(lot) != null
                        && storageService.exists(videoAsset(lot).getObjectKey()))
                .map(lot -> new AnalysisSourceResponse.Source(
                        lot.getPartitionKey(),
                        "/api/internal/analysis/videos/" + lot.getPartitionKey(),
                        lot.getSlotLayoutJson()))
                .toList();
        return new AnalysisSourceResponse(sources);
    }

    @GetMapping("/videos/{partitionKey}")
    public ResponseEntity<InputStreamResource> video(@PathVariable String partitionKey) {
        ParkingLot lot = parkingLotRepository.findByPartitionKey(partitionKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parking lot not found"));
        ParkingLotAsset video = parkingLotAssetRepository
                .findByParkingLotIdAndAssetType(lot.getId(), ParkingLotAssetType.VIDEO)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
        if (!storageService.exists(video.getObjectKey())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
        InputStreamResource resource = new InputStreamResource(storageService.getStream(video.getObjectKey()));
        BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(
                        video.getContentType() == null ? "video/mp4" : video.getContentType()));
        if (video.getSizeBytes() != null && video.getSizeBytes() >= 0) {
            response.contentLength(video.getSizeBytes());
        }
        return response.body(resource);
    }

    private ParkingLotAsset videoAsset(ParkingLot lot) {
        return parkingLotAssetRepository
                .findByParkingLotIdAndAssetType(lot.getId(), ParkingLotAssetType.VIDEO)
                .orElse(null);
    }
}
