package com.smartparking.server.service;

import com.smartparking.server.dto.CampusMapResponse;
import com.smartparking.server.dto.ParkingLotView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceAnswerService {

    private final CampusMapService campusMapService;
    private final GeminiClient geminiClient;
    private final LocalLlmClient localLlmClient;

    public String ask(String question) {
        if (question == null || question.isBlank()) {
            return "무엇을 도와드릴까요?";
        }

        try {
            CampusMapResponse map = campusMapService.getCampusMap();
            String factualAnswer = buildFactualAnswer(map, question);
            String prompt = buildRewritePrompt(buildSummary(map), question, factualAnswer);

            String answer = geminiClient.generate(prompt);
            if (answer == null || answer.isBlank()) {
                answer = localLlmClient.generate(prompt);
            }
            return (answer == null || answer.isBlank()) ? factualAnswer : answer;
        } catch (Exception e) {
            log.warn("음성 답변 생성 실패: {}", e.getMessage());
            return "현재 주차 현황을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        }
    }

    static String buildFactualAnswer(CampusMapResponse map, String question) {
        List<LotFact> facts = collectFacts(map);
        if (facts.isEmpty()) {
            return "등록된 주차장이 없습니다.";
        }

        String normalizedQuestion = normalize(question);
        List<LotFact> scoped = facts.stream()
                .filter(fact -> normalizedQuestion.contains(normalize(fact.getBuildingName()))
                        || normalizedQuestion.contains(normalize(fact.getLotName()))
                        || normalizedQuestion.contains(normalize(fact.getPartitionKey())))
                .toList();
        List<LotFact> target = scoped.isEmpty() ? facts : scoped;

        if (containsAny(normalizedQuestion, "제일", "가장", "많은", "여유", "추천")) {
            LotFact best = target.stream()
                    .filter(LotFact::hasStatus)
                    .max(Comparator.comparingInt(LotFact::availableSlotsValue))
                    .orElse(null);
            if (best == null) {
                return "현재 비교할 수 있는 점유 정보가 없습니다.";
            }
            return String.format(Locale.KOREA,
                    "현재 가장 여유로운 곳은 %s %s이며, 총 %d칸 중 %d칸이 비어 있습니다.",
                    best.getBuildingName(), best.getLotName(), best.totalSlotsValue(), best.availableSlotsValue());
        }

        if (containsAny(normalizedQuestion, "만차", "가득", "없어")) {
            List<LotFact> fullLots = target.stream()
                    .filter(LotFact::hasStatus)
                    .filter(fact -> fact.availableSlotsValue() <= 0)
                    .toList();
            if (fullLots.isEmpty()) {
                return scoped.isEmpty()
                        ? "현재 확인 가능한 주차장 중 만차인 곳은 없습니다."
                        : "질문한 장소에는 현재 만차인 주차장이 없습니다.";
            }
            return "현재 만차인 곳은 " + joinLotNames(fullLots) + "입니다.";
        }

        if (containsAny(normalizedQuestion, "장애", "장애인")) {
            List<LotFact> disabledLots = target.stream()
                    .filter(LotFact::hasStatus)
                    .filter(fact -> fact.getDisabledAvailable() > 0)
                    .toList();
            if (disabledLots.isEmpty()) {
                return "현재 확인 가능한 범위에서 비어 있는 장애인석은 없습니다.";
            }
            return disabledLots.size() == 1
                    ? formatDisabledAnswer(disabledLots.get(0))
                    : "비어 있는 장애인석이 있는 곳은 " + joinDisabledLotNames(disabledLots) + "입니다.";
        }

        if (!scoped.isEmpty()) {
            return scoped.size() == 1
                    ? formatAvailabilityAnswer(scoped.get(0))
                    : "질문한 장소의 현황은 " + joinAvailability(scoped) + "입니다.";
        }

        LotFact best = facts.stream()
                .filter(LotFact::hasStatus)
                .max(Comparator.comparingInt(LotFact::getAvailableSlots))
                .orElse(null);
        if (best == null) {
            return "현재 점유 정보가 있는 주차장이 없습니다.";
        }
        int totalAvailable = facts.stream().filter(LotFact::hasStatus).mapToInt(LotFact::getAvailableSlots).sum();
        int totalSlots = facts.stream().filter(LotFact::hasStatus).mapToInt(LotFact::getTotalSlots).sum();
        return String.format(Locale.KOREA,
                "현재 전체 주차장은 총 %d칸 중 %d칸이 비어 있고, 가장 여유로운 곳은 %s %s입니다.",
                totalSlots, totalAvailable, best.getBuildingName(), best.getLotName());
    }

    static String buildSummary(CampusMapResponse map) {
        List<LotFact> facts = collectFacts(map);
        if (facts.isEmpty()) {
            return "(등록된 주차장이 없습니다)";
        }

        StringBuilder sb = new StringBuilder();
        for (LotFact fact : facts) {
            if (fact.hasStatus()) {
                sb.append("- ").append(fact.getBuildingName()).append(" / ").append(fact.getLotName())
                        .append(" (").append(fact.getPartitionKey()).append("): 총 ")
                        .append(fact.totalSlotsValue()).append("칸, 빈자리 ")
                        .append(fact.availableSlotsValue()).append("칸, 장애인석 빈자리 ")
                        .append(fact.getDisabledAvailable()).append("칸\n");
            } else {
                sb.append("- ").append(fact.getBuildingName()).append(" / ").append(fact.getLotName())
                        .append(" (").append(fact.getPartitionKey()).append("): 점유 정보 없음\n");
            }
        }
        return sb.toString().trim();
    }

    static String buildRewritePrompt(String summary, String question, String factualAnswer) {
        return "너는 주차 안내 도우미다. [정확한 답변]의 장소명과 숫자를 절대 바꾸지 말고, "
                + "사용자 질문 표현에 맞춰 한국어 한두 문장으로 자연스럽게 다듬어라. "
                + "새 숫자나 새 장소를 만들지 말고, 마크다운 없이 답변 문장만 출력하라.\n\n"
                + "[현황]\n" + summary + "\n\n"
                + "[질문]\n" + question + "\n\n"
                + "[정확한 답변]\n" + factualAnswer;
    }

    private static List<LotFact> collectFacts(CampusMapResponse map) {
        List<LotFact> facts = new ArrayList<>();
        if (map == null || map.getBuildings() == null) {
            return facts;
        }

        for (CampusMapResponse.BuildingView building : map.getBuildings()) {
            if (building.getParkingLots() == null) {
                continue;
            }
            for (ParkingLotView lot : building.getParkingLots()) {
                ParkingLotView.Summary summary = lot.getSummary();
                facts.add(new LotFact(
                        safe(building.getName(), "이름 없는 장소"),
                        safe(lot.getName(), "주차장"),
                        safe(lot.getPartitionKey(), "-"),
                        summary != null && summary.getTotalSlots() != null ? summary.getTotalSlots() : null,
                        summary != null && summary.getAvailableSlots() != null ? summary.getAvailableSlots() : null,
                        summary != null && summary.getDisabledAvailable() != null ? summary.getDisabledAvailable() : 0));
            }
        }
        return facts;
    }

    private static String formatAvailabilityAnswer(LotFact fact) {
        if (!fact.hasStatus()) {
            return fact.getBuildingName() + " " + fact.getLotName() + "은 현재 점유 정보가 없습니다.";
        }
        return String.format(Locale.KOREA,
                "%s %s은 현재 총 %d칸 중 %d칸이 비어 있습니다.",
                    fact.getBuildingName(), fact.getLotName(), fact.getTotalSlots(), fact.getAvailableSlots());
    }

    private static String formatDisabledAnswer(LotFact fact) {
        return String.format(Locale.KOREA,
                "%s %s에는 현재 비어 있는 장애인석이 %d칸 있습니다.",
                fact.getBuildingName(), fact.getLotName(), fact.getDisabledAvailable());
    }

    private static String joinAvailability(List<LotFact> facts) {
        return facts.stream()
                .map(fact -> fact.hasStatus()
                        ? String.format(Locale.KOREA, "%s %s %d/%d칸 가능",
                                fact.getBuildingName(), fact.getLotName(),
                                fact.availableSlotsValue(), fact.totalSlotsValue())
                        : fact.getBuildingName() + " " + fact.getLotName() + " 점유 정보 없음")
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private static String joinLotNames(List<LotFact> facts) {
        return facts.stream()
                .map(fact -> fact.getBuildingName() + " " + fact.getLotName())
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private static String joinDisabledLotNames(List<LotFact> facts) {
        return facts.stream()
                .map(fact -> String.format(Locale.KOREA, "%s %s %d칸",
                        fact.getBuildingName(), fact.getLotName(), fact.getDisabledAvailable()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return safe(value, "").replaceAll("\\s+", "").toLowerCase(Locale.KOREA);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Value
    static class LotFact {
        String buildingName;
        String lotName;
        String partitionKey;
        Integer totalSlots;
        Integer availableSlots;
        int disabledAvailable;

        boolean hasStatus() {
            return totalSlots != null && availableSlots != null && totalSlots > 0;
        }

        int totalSlotsValue() {
            return totalSlots == null ? 0 : totalSlots;
        }

        int availableSlotsValue() {
            return availableSlots == null ? 0 : availableSlots;
        }
    }
}
