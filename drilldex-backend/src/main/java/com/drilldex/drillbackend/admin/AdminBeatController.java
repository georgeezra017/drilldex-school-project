package com.drilldex.drillbackend.admin;

import com.drilldex.drillbackend.admin.dto.RejectRequest;
import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.beat.BeatDto;
import com.drilldex.drillbackend.beat.BeatRepository;
import com.drilldex.drillbackend.chat.ChatStorageService;
import com.drilldex.drillbackend.inbox.InboxMessageService;
import com.drilldex.drillbackend.notification.Notification;
import com.drilldex.drillbackend.notification.NotificationService;
import com.drilldex.drillbackend.notification.NotificationType;
import com.drilldex.drillbackend.notification.RelatedType;
import com.drilldex.drillbackend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/beats")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminBeatController {

    private final BeatRepository beatRepository;
    private final InboxMessageService inboxMessageService;
    private final ChatStorageService chatStorageService;
    private final NotificationService notificationService;

    @GetMapping("/pending")
    public ResponseEntity<List<BeatDto>> getPendingBeats() {
        List<Beat> pendingBeats = beatRepository.findPendingStandaloneBeats();
        List<BeatDto> dtos = pendingBeats.stream()
                .map(this::mapToDto)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approveBeat(@PathVariable Long id) {
        Beat beat = beatRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        beat.setApproved(true);
        beat.setRejected(false);
        beatRepository.save(beat);

        User owner = beat.getOwner();
        if (owner != null && owner.getId() != null) {
            String title = beat.getTitle() == null ? "(untitled)" : beat.getTitle();

            try {
                // Create system notification
                Notification n = notificationService.create(
                        owner,
                        NotificationType.SYSTEM,
                        RelatedType.BEAT,
                        beat.getId(),
                        "Your beat was approved",
                        "Your beat \"" + title + "\" is now live."
                );

                // Push via SSE
                notificationService.pushNotificationToUser(owner.getId(), n);
            } catch (Exception ignore) {}
        }

        return ResponseEntity.ok("Beat approved.");
    }


    @PostMapping("/{id}/reject")
    public ResponseEntity<?> rejectBeat(@PathVariable Long id,
                                        @RequestBody(required = false) RejectRequest req) {
        Beat beat = beatRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found"));

        beat.setApproved(false);
        beat.setRejected(true);

        beat.setFeatured(false);
        beat.setFeaturedAt(null);
        beat.setFeaturedFrom(null);
        beat.setFeaturedUntil(null);
        beat.setFeaturedTier(null);

        beatRepository.save(beat);

        String reason = Optional.ofNullable(req).map(RejectRequest::reason).orElse("").trim();
        User owner = beat.getOwner();
        if (owner != null && owner.getId() != null) {
            String title = beat.getTitle() == null ? "(untitled)" : beat.getTitle();

            String body = "Your beat \"%s\" was rejected by the moderation team%s.".formatted(
                    title,
                    reason.isBlank()
                            ? ""
                            : " because: " + reason.trim()
            );

            try {
                chatStorageService.sendAdminMessageToUser(owner.getId(), body);
            } catch (Exception ignore) {
            }

            try {
                Notification n = notificationService.create(
                        owner,
                        NotificationType.SYSTEM,
                        RelatedType.CHAT,
                        beat.getId(),
                        "Your beat was rejected",
                        "Your beat \"" + title + "\" was rejected by the moderation team."
                );
                notificationService.pushNotificationToUser(owner.getId(), n);
            } catch (Exception ignore) {
            }
        }

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "status", "rejected",
                "id", beat.getId()
        ));
    }

    private BeatDto mapToDto(Beat beat) {
        return new BeatDto(
                beat.getId(),
                beat.getSlug(),
                beat.getTitle(),
                beat.getOwner() != null ? beat.getOwner().getDisplayName() : null,
                beat.getOwner() != null ? beat.getOwner().getId() : null,
                null,                                 // audioUrl
                beat.getPlayCount(),
                beat.getDurationInSeconds(),
                beat.getGenre(),
                beat.getTags(),
                beat.getPrice(),
                beat.getCoverImagePath(),
                beat.getLikeCount(),
                Objects.requireNonNullElse(beat.getCommentCount(), 0),
                beat.isFeatured(),
                beat.getFeaturedAt(),
                toPublicUrl(beat.getCoverImagePath()),
                null,                                 // previewUrl
                beat.getBpm(),
                beat.getCreatedAt(),
                false,                                // liked (admin not relevant)
                0,                                     // sales
                java.math.BigDecimal.ZERO             // earnings
        );
    }

    private static String toPublicUrl(String path) {
        return (path != null && !path.isBlank()) ? "/uploads/" + path : null;
    }
}