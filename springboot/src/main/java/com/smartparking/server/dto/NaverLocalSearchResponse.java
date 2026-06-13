package com.smartparking.server.dto;

import java.util.List;
import lombok.Data;

/**
 * 네이버 지역 검색(Local Search) 응답 매핑. 필요한 필드만 둔다.
 * (Spring Boot 기본 설정상 알 수 없는 필드는 무시됨)
 */
@Data
public class NaverLocalSearchResponse {

    private List<Item> items;

    @Data
    public static class Item {
        private String title;
        private String address;
        private String roadAddress;
        private String mapx;
        private String mapy;
    }
}
