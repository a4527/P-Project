package com.smartparking.server.controller;

import com.smartparking.server.dto.InAppNotificationResponse;
import com.smartparking.server.dto.UnreadCountResponse;
import com.smartparking.server.service.InAppNotificationService;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/me/notifications")
public class MeNotificationController {

    private final InAppNotificationService notificationService;

    @GetMapping
    public ResponseEntity<List<InAppNotificationResponse>> list(Principal principal) {
        return ResponseEntity.ok(notificationService.list(principal.getName()));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> unreadCount(Principal principal) {
        return ResponseEntity.ok(notificationService.unreadCount(principal.getName()));
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<InAppNotificationResponse> read(
            Principal principal,
            @PathVariable Long notificationId) {
        return ResponseEntity.ok(notificationService.markRead(principal.getName(), notificationId));
    }
}
