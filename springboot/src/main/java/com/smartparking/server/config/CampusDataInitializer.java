package com.smartparking.server.config;

import com.smartparking.server.entity.Building;
import com.smartparking.server.entity.Campus;
import com.smartparking.server.repository.BuildingRepository;
import com.smartparking.server.repository.CampusRepository;
import com.smartparking.server.service.ParkingLotAssetSyncService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class CampusDataInitializer {

    private final CampusRepository campusRepository;
    private final BuildingRepository buildingRepository;
    private final ParkingLotAssetSyncService parkingLotAssetSyncService;

    @Bean
    public CommandLineRunner seedCampusData() {
        return args -> {
            if (campusRepository.count() > 0) {
                return;
            }

            Campus campus = new Campus();
            campus.setName("가천대학교 글로벌캠퍼스");
            campus.setCenterLat(37.4535458);
            campus.setCenterLng(127.1325556);
            campus.setDefaultZoom(17);
            campusRepository.save(campus);

            List<BuildingSeed> seeds = List.of(
                    new BuildingSeed("AI공학관", "gachon_ai", 37.455108, 127.133759, 1),
                    new BuildingSeed("제1기숙사", "gachon_dorm1", 37.456453, 127.135359, 2),
                    new BuildingSeed("제2기숙사", "gachon_dorm2", 37.456072, 127.134535, 3),
                    new BuildingSeed("중앙도서관", "gachon_library", 37.452203, 127.133012, 4),
                    new BuildingSeed("교육대학원", "gachon_gradschool", 37.451993, 127.131813, 5));

            for (BuildingSeed seed : seeds) {
                Building building = new Building();
                building.setCampus(campus);
                building.setName(seed.name);
                building.setMapKey(seed.mapKey);
                building.setLat(seed.lat);
                building.setLng(seed.lng);
                building.setSortOrder(seed.sortOrder);
                buildingRepository.save(building);
            }

            parkingLotAssetSyncService.syncFromFilesystem();
        };
    }

    private record BuildingSeed(String name, String mapKey, double lat, double lng, int sortOrder) {
    }
}
