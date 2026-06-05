package com.smartparking.server.service;

import com.smartparking.server.dto.InAppNotificationResponse;
import com.smartparking.server.dto.UnreadCountResponse;
import com.smartparking.server.entity.InAppNotification;
import com.smartparking.server.entity.User;
import com.smartparking.server.repository.InAppNotificationRepository;
import com.smartparking.server.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class InAppNotificationService {

    private final UserRepository userRepository;
    private final InAppNotificationRepository notificationRepository;

    @Transactional
    public void createNotification(String username, String title, String message, String category) {
        User user = getUser(username);
        InAppNotification notification = new InAppNotification();
        notification.setUser(user);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setCategory(category);
        notification.setReadFlag(false);
        notification.setCreatedAt(LocalDateTime.now());
        notificationRepository.save(notification);
    }

    @Transactional(readOnly = true)
    public List<InAppNotificationResponse> list(String username) {
        return notificationRepository.findByUserUsernameOrderByCreatedAtDesc(username).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(String username) {
        return new UnreadCountResponse(notificationRepository.countByUserUsernameAndReadFlagFalse(username));
    }

    @Transactional
    public InAppNotificationResponse markRead(String username, Long notificationId) {
        InAppNotification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));
        if (!notification.getUser().getUsername().equals(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your notification");
        }
        notification.setReadFlag(true);
        notification.setReadAt(LocalDateTime.now());
        return toResponse(notificationRepository.save(notification));
    }

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private InAppNotificationResponse toResponse(InAppNotification notification) {
        return new InAppNotificationResponse(
                notification.getId(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getCategory(),
                notification.isReadFlag(),
                notification.getCreatedAt(),
                notification.getReadAt());
    }
}
