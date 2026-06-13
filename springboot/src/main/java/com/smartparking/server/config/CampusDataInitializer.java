package com.smartparking.server.config;

import com.smartparking.server.entity.Campus;
import com.smartparking.server.repository.CampusRepository;
import com.smartparking.server.service.ParkingLotAssetSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class CampusDataInitializer {

    private final CampusRepository campusRepository;
    private final ParkingLotAssetSyncService parkingLotAssetSyncService;

    @Bean
    public CommandLineRunner seedCampusData() {
        return args -> {
            if (campusRepository.count() == 0) {
                Campus campus = new Campus();
                campus.setName("가천대학교 글로벌캠퍼스");
                campus.setCenterLat(37.4535458);
                campus.setCenterLng(127.1325556);
                campus.setDefaultZoom(17);
                campusRepository.save(campus);
            }
            parkingLotAssetSyncService.syncFromFilesystem();
        };
    }
}
