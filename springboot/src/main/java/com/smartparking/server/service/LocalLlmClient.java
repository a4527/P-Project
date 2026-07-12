package com.smartparking.server.service;

import java.time.Duration;
import java.util.Map;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
public class LocalLlmClient {

    private final WebClient ollamaWebClient;
    private final String model;

    public LocalLlmClient(
            @Qualifier("ollamaWebClient") WebClient ollamaWebClient,
            @Value("${smartparking.ollama.model:qwen2.5:1.5b}") String model) {
        this.ollamaWebClient = ollamaWebClient;
        this.model = model;
    }

    public String generate(String prompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "prompt", prompt,
                "stream", false,
                "options", Map.of(
                        "temperature", 0.2,
                        "num_predict", 120));

        try {
            OllamaGenerateResponse response = ollamaWebClient.post()
                    .uri("/api/generate")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(OllamaGenerateResponse.class)
                    .timeout(Duration.ofSeconds(12))
                    .block();

            if (response == null || response.getResponse() == null || response.getResponse().isBlank()) {
                return null;
            }
            return response.getResponse().trim();
        } catch (Exception e) {
            log.warn("Ollama 호출 실패: {}", e.getMessage());
            return null;
        }
    }

    @Data
    static class OllamaGenerateResponse {
        private String response;
    }
}
