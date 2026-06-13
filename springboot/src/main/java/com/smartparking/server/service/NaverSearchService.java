package com.smartparking.server.service;

import com.smartparking.server.dto.GeoSearchResult;
import com.smartparking.server.dto.NaverLocalSearchResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
public class NaverSearchService {

    private final WebClient naverSearchWebClient;

    public NaverSearchService(WebClient naverSearchWebClient) {
        this.naverSearchWebClient = naverSearchWebClient;
    }

    /**
     * 네이버 지역 검색(Local Search)으로 장소명/주소를 검색해 좌표를 돌려준다.
     * mapx/mapy는 WGS84 좌표를 10^7배한 정수 문자열이므로 1e7로 나눈다.
     */
    public List<GeoSearchResult> search(String query) {
        List<GeoSearchResult> results = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return results;
        }

        NaverLocalSearchResponse body;
        try {
            body = naverSearchWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/search/local.json")
                            .queryParam("query", query)
                            .queryParam("display", 5)
                            .build())
                    .retrieve()
                    .bodyToMono(NaverLocalSearchResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
        } catch (Exception e) {
            log.warn("네이버 지역 검색 실패: query={}, error={}", query, e.getMessage());
            return results;
        }

        if (body == null || body.getItems() == null) {
            return results;
        }

        for (NaverLocalSearchResponse.Item item : body.getItems()) {
            String mapx = item.getMapx();
            String mapy = item.getMapy();
            if (mapx == null || mapx.isBlank() || mapy == null || mapy.isBlank()) {
                continue;
            }
            try {
                double lng = Double.parseDouble(mapx) / 1e7;
                double lat = Double.parseDouble(mapy) / 1e7;
                String name = stripTags(item.getTitle() == null ? "" : item.getTitle());
                String address = item.getRoadAddress();
                if (address == null || address.isBlank()) {
                    address = item.getAddress();
                }
                results.add(new GeoSearchResult(name, lat, lng, address));
            } catch (NumberFormatException ignored) {
                // 좌표 파싱 실패한 항목은 건너뜀
            }
        }
        return results;
    }

    private String stripTags(String value) {
        return value.replaceAll("<[^>]*>", "");
    }
}
