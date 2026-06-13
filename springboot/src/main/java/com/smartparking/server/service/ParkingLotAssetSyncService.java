package com.smartparking.server.service;

import com.smartparking.server.entity.Building;
import com.smartparking.server.entity.ParkingLot;
import com.smartparking.server.repository.BuildingRepository;
import com.smartparking.server.repository.ParkingLotRepository;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParkingLotAssetSyncService {

    private static final Pattern LOT_SUFFIX_PATTERN = Pattern.compile("^(.*)_([0-9]+)$");

    private final BuildingRepository buildingRepository;
    private final ParkingLotRepository parkingLotRepository;

    @Transactional
    public synchronized void syncFromFilesystem() {
        Path videoTestRoot = resolveVideoTestRoot();
        Set<String> videoKeys = collectVideoKeys(videoTestRoot.resolve("videos"));

        cleanupDuplicateParkingLots();
        for (String partitionKey : videoKeys) {
            ensureParkingLot(partitionKey);
        }
    }

    private void cleanupDuplicateParkingLots() {
        List<ParkingLot> lots = parkingLotRepository.findAll();
        Map<String, List<ParkingLot>> grouped = lots.stream()
                .collect(Collectors.groupingBy(ParkingLot::getPartitionKey, LinkedHashMap::new, Collectors.toList()));

        for (List<ParkingLot> duplicates : grouped.values()) {
            if (duplicates.size() <= 1) {
                continue;
            }

            duplicates.sort((left, right) -> Long.compare(left.getId(), right.getId()));
            List<ParkingLot> extras = duplicates.subList(1, duplicates.size());
            parkingLotRepository.deleteAll(extras);
            log.info("Removed duplicate parking lots for partition key: {}", duplicates.get(0).getPartitionKey());
        }
    }

    private void ensureParkingLot(String partitionKey) {
        if (parkingLotRepository.existsByPartitionKey(partitionKey)) {
            return;
        }

        String buildingMapKey = resolveBuildingMapKey(partitionKey);
        Optional<Building> buildingOpt = buildingRepository.findByMapKey(buildingMapKey);
        if (buildingOpt.isEmpty()) {
            log.info("Skipping lot creation because building mapKey is missing: {}", buildingMapKey);
            return;
        }

        Building building = buildingOpt.get();
        ParkingLot parkingLot = new ParkingLot();
        parkingLot.setBuilding(building);
        parkingLot.setName(partitionKey);
        parkingLot.setPartitionKey(partitionKey);
        parkingLot.setMapImageUrl(null);
        parkingLot.setSlotLayoutJson(null);
        parkingLot.setSortOrder(resolveSortOrder(partitionKey));
        parkingLotRepository.save(parkingLot);
        log.info("Created parking lot from assets: {} -> building {}", partitionKey, buildingMapKey);
    }

    private Set<String> collectVideoKeys(Path directory) {
        Set<String> keys = new HashSet<>();
        if (!Files.exists(directory)) {
            return keys;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                String filename = path.getFileName().toString();
                String lowerFilename = filename.toLowerCase();
                if ((!lowerFilename.endsWith(".mp4") && !lowerFilename.endsWith(".mov")) || !filename.contains("_video")) {
                    continue;
                }

                int videoMarkerIndex = filename.indexOf("_video");
                if (videoMarkerIndex <= 0) {
                    continue;
                }

                String key = filename.substring(0, videoMarkerIndex);
                if (!key.isBlank()) {
                    keys.add(key);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan asset directory: {}", directory, e);
        }

        return keys;
    }

    private String resolveBuildingMapKey(String partitionKey) {
        Matcher matcher = LOT_SUFFIX_PATTERN.matcher(partitionKey);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return partitionKey;
    }

    private Integer resolveSortOrder(String partitionKey) {
        Matcher matcher = LOT_SUFFIX_PATTERN.matcher(partitionKey);
        if (matcher.matches()) {
            return Integer.valueOf(matcher.group(2));
        }
        return 1;
    }

    private Path resolveVideoTestRoot() {
        return resolveExistingPath(
                Paths.get("fastapi", "video_test"),
                Paths.get("..", "fastapi", "video_test"),
                Paths.get("..", "..", "fastapi", "video_test"));
    }

    private Path resolveExistingPath(Path... candidates) {
        for (Path candidate : candidates) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.exists(absolute)) {
                return absolute;
            }
        }
        return candidates[0].toAbsolutePath().normalize();
    }
}
