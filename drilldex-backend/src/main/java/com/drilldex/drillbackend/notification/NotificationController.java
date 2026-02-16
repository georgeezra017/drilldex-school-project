package com.drilldex.drillbackend.notification;

import com.drilldex.drillbackend.auth.CustomUserDetails;
import com.drilldex.drillbackend.auth.JwtService;
import com.drilldex.drillbackend.notification.dto.NotificationDto;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final SseNotificationBroadcaster broadcaster;

    private User resolveUser(CustomUserDetails principal, String authHeader) {
        if (principal != null) {
            return principal.getUser();
        }
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        try {
            String token = authHeader.substring(7);
            String email = jwtService.extractUsername(token);
            return userRepository.findByEmail(email).orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }


    /**
     * Fetch unread notifications with pagination.
     * Defaults: page=0, size=20
     */
    @GetMapping("/unread")
    public Page<NotificationDto> getUnread(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        User user = resolveUser(principal, authHeader);
        if (user == null) {
            return Page.empty(PageRequest.of(page, size));
        }
        Page<Notification> notifications = service.getUnread(user, PageRequest.of(page, size));

        return notifications.map(service::toDto);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNotifications(@RequestParam Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("Missing userId");
        }

        return broadcaster.subscribe(userId);
    }

    /**
     * Fetch all notifications with pagination.
     * Defaults: page=0, size=20
     */
    @GetMapping("/all")
    public Page<Notification> getAll(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        User user = resolveUser(principal, authHeader);
        if (user == null) {
            return Page.empty(PageRequest.of(page, size));
        }
        return service.getAll(user, PageRequest.of(page, size));
    }

    /**
     * Mark a single notification as read.
     */
    @PostMapping("/{id}/read")
    public ResponseEntity<?> markAsRead(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        User user = resolveUser(principal, authHeader);
        if (user != null) {
            service.markAsRead(id, user);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Mark all notifications as read.
     */
    @PostMapping("/read-all")
    public ResponseEntity<?> markAllAsRead(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        User user = resolveUser(principal, authHeader);
        if (user != null) {
            service.markAllAsRead(user);
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteNotification(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        User user = resolveUser(principal, authHeader);
        if (user != null) {
            service.delete(id, user); // implement this in NotificationService
        }
        return ResponseEntity.ok().build();
    }

}
