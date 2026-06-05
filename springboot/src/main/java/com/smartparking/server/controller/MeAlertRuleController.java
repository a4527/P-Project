package com.smartparking.server.controller;

import com.smartparking.server.dto.ParkingAlertRuleRequest;
import com.smartparking.server.dto.ParkingAlertRuleResponse;
import com.smartparking.server.service.ParkingAlertRuleService;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/me/alert-rules")
public class MeAlertRuleController {

    private final ParkingAlertRuleService alertRuleService;

    @PostMapping
    public ResponseEntity<ParkingAlertRuleResponse> create(
            Principal principal,
            @RequestBody ParkingAlertRuleRequest request) {
        return ResponseEntity.ok(alertRuleService.create(principal.getName(), request));
    }

    @GetMapping
    public ResponseEntity<List<ParkingAlertRuleResponse>> list(Principal principal) {
        return ResponseEntity.ok(alertRuleService.list(principal.getName()));
    }

    @PutMapping("/{ruleId}")
    public ResponseEntity<ParkingAlertRuleResponse> toggle(
            Principal principal,
            @PathVariable Long ruleId,
            @RequestBody ParkingAlertRuleRequest request) {
        boolean enabled = request.getEnabled() == null || request.getEnabled();
        return ResponseEntity.ok(alertRuleService.toggle(principal.getName(), ruleId, enabled));
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> delete(Principal principal, @PathVariable Long ruleId) {
        alertRuleService.delete(principal.getName(), ruleId);
        return ResponseEntity.noContent().build();
    }
}
