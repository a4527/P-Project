package com.smartparking.server.service;

import com.smartparking.server.dto.ParkingStatusResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.*;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;

@Slf4j
@Service
public class ParkingStatusService {

    private final WebClient yoloWebClient;

    @Getter
    private ParkingStatusResponse cachedStatus;

    public ParkingStatusService(WebClient yoloWebClient) {
        this.yoloWebClient = yoloWebClient;
    }

    // 5초마다 FastAPI 서버에서 상태 가져오기
    @Scheduled(fixedRate = 5000)
    public void updateStatus() {

        try {
            ParkingStatusResponse status = yoloWebClient.get()
                    .uri("/status")
                    .retrieve()
                    .bodyToMono(ParkingStatusResponse.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();

            this.cachedStatus = status;

            log.info("YOLO 상태 갱신됨: {}", status);

        } catch (Exception e) {
            log.error("YOLO 서버로부터 상태를 가져오지 못함.", e);
        }
    }
}
