package com.smartparking.server.dto;

import java.util.List;
import lombok.Value;

@Value
public class AnalysisSourceResponse {
    List<Source> sources;

    @Value
    public static class Source {
        String partitionKey;
        String videoUrl;
        String slotLayoutJson;
    }
}
