package com.smartparking.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.smartparking.server.dto.CampusMapResponse;
import com.smartparking.server.dto.ParkingLotView;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoiceAnswerServiceTest {

    @Mock
    private com.smartparking.server.service.CampusMapService campusMapService;
    @Mock
    private GeminiClient geminiClient;
    @InjectMocks
    private VoiceAnswerService voiceAnswerService;

    private CampusMapResponse sampleMap() {
        ParkingLotView lot = new ParkingLotView();
        lot.setName("지하 1층");
        lot.setPartitionKey("gachon_ai_1");
        lot.setSummary(new ParkingLotView.Summary("AVAILABLE", 41, 4, 0, null));

        CampusMapResponse.BuildingView building = new CampusMapResponse.BuildingView();
        building.setName("AI공학관");
        building.setParkingLots(List.of(lot));

        CampusMapResponse map = new CampusMapResponse();
        map.setBuildings(List.of(building));
        return map;
    }

    @Test
    void buildSummaryIncludesBuildingLotNameAndCounts() {
        String summary = VoiceAnswerService.buildSummary(sampleMap());
        assertThat(summary).contains("AI공학관");
        assertThat(summary).contains("지하 1층");
        assertThat(summary).contains("gachon_ai_1");
        assertThat(summary).contains("41");
        assertThat(summary).contains("4");
    }

    @Test
    void askReturnsGeminiAnswer() {
        when(campusMapService.getCampusMap()).thenReturn(sampleMap());
        when(geminiClient.generate(anyString())).thenReturn("현재 지하 1층은 4자리 비어 있어요.");

        String answer = voiceAnswerService.ask("AI공학관 지하1층 빈자리 있어?");

        assertThat(answer).isEqualTo("현재 지하 1층은 4자리 비어 있어요.");
    }

    @Test
    void askReturnsFallbackWhenGeminiFails() {
        when(campusMapService.getCampusMap()).thenReturn(sampleMap());
        when(geminiClient.generate(anyString())).thenReturn(null);

        String answer = voiceAnswerService.ask("빈자리 있어?");

        assertThat(answer).isNotBlank();
        assertThat(answer).contains("다시");
    }
}
