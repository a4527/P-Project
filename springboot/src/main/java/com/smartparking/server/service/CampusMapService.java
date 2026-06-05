package com.smartparking.server.service;

import com.smartparking.server.dto.BuildingDetailResponse;
import com.smartparking.server.dto.CampusMapResponse;
import com.smartparking.server.dto.ParkingStatusResponse;
import com.smartparking.server.dto.ParkingLotView;
import com.smartparking.server.dto.UiConfigResponse;
import com.smartparking.server.entity.Building;
import com.smartparking.server.entity.Campus;
import com.smartparking.server.entity.ParkingLot;
import com.smartparking.server.repository.BuildingRepository;
import com.smartparking.server.repository.CampusRepository;
import com.smartparking.server.repository.ParkingLotRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CampusMapService {

    private final CampusRepository campusRepository;
    private final BuildingRepository buildingRepository;
    private final ParkingLotRepository parkingLotRepository;
    private final ParkingStatusService parkingStatusService;
    private final ParkingLotMapService parkingLotMapService;
    private final ParkingLotAssetSyncService parkingLotAssetSyncService;

    public CampusMapResponse getCampusMap() {
        parkingLotAssetSyncService.syncFromFilesystem();
        Campus campus = getDefaultCampus();
        List<CampusMapResponse.BuildingView> buildingSummaries = new ArrayList<>();
        for (Building building : buildingRepository.findByCampusIdOrderBySortOrderAsc(campus.getId())) {
            buildingSummaries.add(toBuildingView(building, false));
        }
        return new CampusMapResponse(toCampusData(campus), buildingSummaries);
    }

    public BuildingDetailResponse getBuildingDetail(Long buildingId) {
        parkingLotAssetSyncService.syncFromFilesystem();
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Building not found: " + buildingId));

        List<ParkingLot> parkingLots = parkingLotRepository.findByBuildingIdOrderBySortOrderAsc(buildingId);
        List<ParkingLotView> parkingLotViews = new ArrayList<>();
        for (ParkingLot parkingLot : parkingLots) {
            parkingLotViews.add(toParkingLotView(parkingLot, true));
        }
        return new BuildingDetailResponse(
                toCampusData(building.getCampus()),
                toBuildingView(building, true),
                parkingLotViews);
    }

    public UiConfigResponse getUiConfig(String naverMapClientId) {
        parkingLotAssetSyncService.syncFromFilesystem();
        return new UiConfigResponse(naverMapClientId, toCampusData(getDefaultCampus()));
    }

    private Campus getDefaultCampus() {
        return campusRepository.findAll().stream()
                .sorted(Comparator.comparing(Campus::getId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No campus data found"));
    }

    private CampusMapResponse.CampusData toCampusData(Campus campus) {
        return new CampusMapResponse.CampusData(
                campus.getId(),
                campus.getName(),
                campus.getCenterLat(),
                campus.getCenterLng(),
                campus.getDefaultZoom());
    }

    private CampusMapResponse.BuildingView toBuildingView(Building building, boolean includeSlots) {
        List<ParkingLotView> parkingLotViews = new ArrayList<>();
        for (ParkingLot parkingLot : parkingLotRepository.findByBuildingIdOrderBySortOrderAsc(building.getId())) {
            parkingLotViews.add(toParkingLotView(parkingLot, includeSlots));
        }
        return new CampusMapResponse.BuildingView(
                building.getId(),
                building.getName(),
                building.getMapKey(),
                building.getLat(),
                building.getLng(),
                building.getSortOrder(),
                parkingLotViews);
    }

    private ParkingLotView toParkingLotView(ParkingLot parkingLot, boolean includeSlots) {
        var parkingLotMap = parkingLotMapService.snapshot(parkingLot);
        ParkingStatusResponse.PartitionData partitionData = parkingStatusService.getPartitionData(parkingLot.getPartitionKey());
        ParkingStatusMetrics metrics = toMetrics(partitionData);

        List<ParkingLotView.Slot> slots = new ArrayList<>();
        if (includeSlots && partitionData != null && partitionData.getSlots() != null) {
            partitionData.getSlots().stream()
                    .sorted(Comparator.comparingInt(slot -> slot.getSlotId()))
                    .forEach(slot -> slots.add(new ParkingLotView.Slot(
                            parkingLot.getPartitionKey(),
                            slot.getSlotId(),
                            slot.getType(),
                            slot.getStatus(),
                            slot.getCenter())));
        }

        return new ParkingLotView(
                parkingLot.getId(),
                parkingLot.getName(),
                parkingLot.getPartitionKey(),
                parkingLotMap.getSourceImageUrl(),
                parkingLotMap.getSlotLayoutJson(),
                parkingLotMap.isSourceImageExists(),
                parkingLotMap.isGeneratedMapExists(),
                parkingLotMap.getSourceImageUrl(),
                parkingLotMap.getGeneratedMapUrl(),
                parkingLotMap.getStatusMessage(),
                new ParkingLotView.Summary(
                        metrics.status,
                        metrics.totalSlots,
                        metrics.availableSlots,
                        metrics.disabledAvailable,
                        metrics.lastUpdate),
                slots);
    }

    private ParkingStatusMetrics toMetrics(ParkingStatusResponse.PartitionData partitionData) {
        if (partitionData == null || partitionData.getSummary() == null) {
            return new ParkingStatusMetrics("NO_DATA", 0, 0, 0, null);
        }

        ParkingStatusResponse.SummaryData summary = partitionData.getSummary();
        int total = summary.getTotal();
        int available = summary.getAvailable();
        int disabledAvailable = summary.getDisabledAvailable();
        String status = available > 0 ? "AVAILABLE" : "FULL";
        Double lastUpdate = parkingStatusService.getCachedStatus() == null
                ? null
                : parkingStatusService.getCachedStatus().getLastUpdate();

        return new ParkingStatusMetrics(
                status,
                total,
                available,
                disabledAvailable,
                lastUpdate);
    }

    private static class ParkingStatusMetrics {
        private final String status;
        private final int totalSlots;
        private final int availableSlots;
        private final int disabledAvailable;
        private final Double lastUpdate;

        private ParkingStatusMetrics(String status, int totalSlots, int availableSlots, int disabledAvailable, Double lastUpdate) {
            this.status = status;
            this.totalSlots = totalSlots;
            this.availableSlots = availableSlots;
            this.disabledAvailable = disabledAvailable;
            this.lastUpdate = lastUpdate;
        }
    }

}
