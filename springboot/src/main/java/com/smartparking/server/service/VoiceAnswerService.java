package com.smartparking.server.service;

import com.smartparking.server.dto.CampusMapResponse;
import com.smartparking.server.dto.ParkingLotView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VoiceAnswerService {

    private static final String FALLBACK = "지금은 답변을 가져올 수 없어요. 잠시 후 다시 시도해 주세요.";

    private final CampusMapService campusMapService;
    private final GeminiClient geminiClient;

    public String ask(String question) {
        if (question == null || question.isBlank()) {
            return "무엇을 도와드릴까요?";
        }
        CampusMapResponse map = campusMapService.getCampusMap();
        String summary = buildSummary(map);
        String prompt = buildPrompt(summary, question);
        String answer = geminiClient.generate(prompt);
        return (answer == null || answer.isBlank()) ? FALLBACK : answer;
    }

    static String buildSummary(CampusMapResponse map) {
        StringBuilder sb = new StringBuilder();
        if (map == null || map.getBuildings() == null || map.getBuildings().isEmpty()) {
            return "(등록된 주차장이 없습니다)";
        }
        for (CampusMapResponse.BuildingView building : map.getBuildings()) {
            sb.append("건물: ").append(building.getName()).append("\n");
            if (building.getParkingLots() == null || building.getParkingLots().isEmpty()) {
                sb.append(" - (주차장 없음)\n");
                continue;
            }
            for (ParkingLotView lot : building.getParkingLots()) {
                ParkingLotView.Summary s = lot.getSummary();
                if (s != null && s.getTotalSlots() != null && s.getTotalSlots() > 0) {
                    sb.append(" - 주차장 \"").append(lot.getName()).append("\"(")
                            .append(lot.getPartitionKey()).append("): 총 ")
                            .append(s.getTotalSlots()).append("칸, 빈자리 ")
                            .append(s.getAvailableSlots() == null ? 0 : s.getAvailableSlots())
                            .append("\n");
                } else {
                    sb.append(" - 주차장 \"").append(lot.getName()).append("\"(")
                            .append(lot.getPartitionKey()).append("): 점유 정보 없음\n");
                }
            }
        }
        return sb.toString().trim();
    }

    static String buildPrompt(String summary, String question) {
        return "너는 주차 안내 도우미다. 아래 [현황]만 근거로 한국어 한두 문장으로 자연스럽게 답하라. "
                + "[현황]에 없는 곳을 물으면 모른다고 답하라. 숫자를 지어내지 마라.\n\n"
                + "[현황]\n" + summary + "\n\n[질문] " + question;
    }
}
