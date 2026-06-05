package com.smartparking.server.service;

import com.smartparking.server.dto.ParkingAlertRuleRequest;
import com.smartparking.server.dto.ParkingAlertRuleResponse;
import com.smartparking.server.entity.ParkingAlertRule;
import com.smartparking.server.entity.ParkingLot;
import com.smartparking.server.entity.User;
import com.smartparking.server.repository.ParkingAlertRuleRepository;
import com.smartparking.server.repository.ParkingLotRepository;
import com.smartparking.server.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ParkingAlertRuleService {

    private final UserRepository userRepository;
    private final ParkingLotRepository parkingLotRepository;
    private final ParkingAlertRuleRepository ruleRepository;

    @Transactional
    public ParkingAlertRuleResponse create(String username, ParkingAlertRuleRequest request) {
        if (request == null || request.getParkingLotId() == null || request.getMinimumAvailableSlots() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parkingLotId and minimumAvailableSlots are required");
        }

        User user = getUser(username);
        ParkingLot parkingLot = parkingLotRepository.findById(request.getParkingLotId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parking lot not found"));

        ParkingAlertRule rule = new ParkingAlertRule();
        rule.setUser(user);
        rule.setParkingLot(parkingLot);
        rule.setMinimumAvailableSlots(request.getMinimumAvailableSlots());
        rule.setEnabled(request.getEnabled() == null || request.getEnabled());
        return toResponse(ruleRepository.save(rule));
    }

    @Transactional(readOnly = true)
    public List<ParkingAlertRuleResponse> list(String username) {
        return ruleRepository.findByUserUsernameOrderByIdDesc(username).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ParkingAlertRuleResponse toggle(String username, Long ruleId, boolean enabled) {
        ParkingAlertRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found"));
        if (!rule.getUser().getUsername().equals(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your rule");
        }
        rule.setEnabled(enabled);
        return toResponse(ruleRepository.save(rule));
    }

    @Transactional
    public void delete(String username, Long ruleId) {
        ParkingAlertRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found"));
        if (!rule.getUser().getUsername().equals(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your rule");
        }
        ruleRepository.delete(rule);
    }

    @Transactional
    public List<ParkingAlertRule> findEnabledRules() {
        return ruleRepository.findByEnabledTrue();
    }

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private ParkingAlertRuleResponse toResponse(ParkingAlertRule rule) {
        return new ParkingAlertRuleResponse(
                rule.getId(),
                rule.getParkingLot().getId(),
                rule.getParkingLot().getName(),
                rule.getMinimumAvailableSlots(),
                rule.isEnabled(),
                rule.getLastKnownAvailableSlots(),
                rule.getLastTriggeredAt());
    }
}
