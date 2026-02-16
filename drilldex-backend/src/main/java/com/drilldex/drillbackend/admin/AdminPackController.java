// src/main/java/com/drilldex/drillbackend/admin/AdminPackController.java
package com.drilldex.drillbackend.admin;

import com.drilldex.drillbackend.admin.dto.RejectRequest;
import com.drilldex.drillbackend.chat.ChatStorageService;
import com.drilldex.drillbackend.inbox.InboxMessageService;
import com.drilldex.drillbackend.notification.Notification;
import com.drilldex.drillbackend.notification.NotificationService;
import com.drilldex.drillbackend.notification.NotificationType;
import com.drilldex.drillbackend.notification.RelatedType;
import com.drilldex.drillbackend.pack.Pack;
import com.drilldex.drillbackend.pack.PackModerationService;
import com.drilldex.drillbackend.pack.PackRepository;
import com.drilldex.drillbackend.pack.PackService;
import com.drilldex.drillbackend.pack.dto.AdminPackDetailDto;
import com.drilldex.drillbackend.pack.dto.AdminPackRowDto;
import com.drilldex.drillbackend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/packs")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminPackController {

    private final PackModerationService service;
    private final PackRepository packRepository;
    private final InboxMessageService inboxMessageService;
    private final PackService packService;
    private final ChatStorageService chatStorageService;
    private final NotificationService notificationService;

    @GetMapping("/pending")
    public List<AdminPackRowDto> listPending() {
        return service.listPending();
    }

    @GetMapping("/{packId}/verify")
    public Map<String, Object> verify(@PathVariable Long packId) {
        return service.verify(packId);
    }

    @PostMapping("/{packId}/approve")
    public ResponseEntity<?> approve(@PathVariable Long packId) {
        // Approve the pack
        service.approve(packId);

        // Fetch the pack to get the owner
        Pack pack = service.getById(packId); // assuming getById exists in service
        User owner = pack.getOwner();

        if (owner != null && owner.getId() != null) {
            String title = pack.getTitle() == null ? "(untitled)" : pack.getTitle();
            try {
                Notification n = notificationService.create(
                        owner,
                        NotificationType.SYSTEM,
                        RelatedType.PACK,
                        pack.getId(),
                        "Your pack was approved",
                        "Your pack \"" + title + "\" is now live."
                );
                // Push notification to user via SSE
                notificationService.pushNotificationToUser(owner.getId(), n);
            } catch (Exception ignore) {}
        }

        return ResponseEntity.ok(Map.of("message", "Pack approved"));
    }

    // src/main/java/com/drilldex/drillbackend/pack/AdminPackController.java
    @PostMapping("/{packId}/reject")
    public ResponseEntity<?> rejectPack(@PathVariable Long packId,
                                        @RequestBody(required = false) RejectRequest req) {
        String reason = Optional.ofNullable(req).map(RejectRequest::reason).orElse("").trim();
        Pack updated = packService.rejectAndNotify(packId, reason);

        User owner = updated.getOwner();
        if (owner != null && owner.getId() != null) {
            String title = updated.getTitle() == null ? "(untitled)" : updated.getTitle();

            // Send chat message with reason
            try {
                chatStorageService.sendAdminMessageToUser(
                        owner.getId(),
                        "Your pack \"" + title + "\" was rejected by the moderation team" +
                                (reason.isBlank() ? "." : " because: " + reason)
                );
            } catch (Exception ignore) {
            }

            // Send system notification
            try {
                Notification n = notificationService.create(
                        owner,
                        NotificationType.SYSTEM,
                        RelatedType.CHAT,
                        updated.getId(),
                        "Your pack was rejected",
                        "Your pack \"" + title + "\" was rejected by the moderation team."
                );
                notificationService.pushNotificationToUser(owner.getId(), n);
            } catch (Exception ignore) {
            }
        }

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "id", updated.getId(),
                "status", getStatusForResponse(updated),
                "moderationNote", getModerationNoteForResponse(updated)
        ));
    }

    // Small helpers so this compiles regardless of your Pack schema
    private static String getStatusForResponse(Pack p) {
        try {
            var m = p.getClass().getMethod("getStatus");
            Object v = m.invoke(p);
            return v == null ? "rejected" : String.valueOf(v);
        } catch (Exception ignore) {
            return "rejected";
        }
    }
    private static String getModerationNoteForResponse(Pack p) {
        try {
            var m = p.getClass().getMethod("getModerationNote");
            Object v = m.invoke(p);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception ignore) {
            return "";
        }
    }

    // src/main/java/com/drilldex/drillbackend/admin/AdminPackController.java
    @GetMapping("/{packId}")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminPackDetailDto getPack(@PathVariable Long packId) {
        Pack p = packRepository.findWithBeats(packId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String coverUrl = publicUrl(p.getAlbumCoverUrl() != null && !p.getAlbumCoverUrl().isBlank()
                ? p.getAlbumCoverUrl()
                : p.getCoverImagePath());

        // compute status from flags
        String status = p.isApproved() ? "approved" : (p.isRejected() ? "rejected" : "pending");

        var beats = (p.getBeats() == null ? List.<AdminPackDetailDto.BeatInPackDto>of()
                : p.getBeats().stream().map(b ->
                new AdminPackDetailDto.BeatInPackDto(
                        b.getId(),
                        b.getTitle(),
                        b.getBpm(),
                        b.getGenre(),
                        b.getDurationInSeconds(),
                        b.isApproved(),
                        toPublicUrl(b.getAudioFilePath()),   // full audio (or preview later)
                        toPublicUrl(firstNonBlank(b.getAlbumCoverUrl(), b.getCoverImagePath()))
                )
        ).toList());

        return new AdminPackDetailDto(
                p.getId(),
                p.getTitle(),
                p.getDescription(),
                p.getGenre(),
                p.getDisplayPrice(),
                coverUrl,
                p.getCoverImagePath(),
                p.getOwner() != null ? p.getOwner().getDisplayName() : null,
                p.getCreatedAt(),
                p.getUpdatedAt(),
                status,
                beats
        );
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String toPublicUrl(String any) {
        if (any == null || any.isBlank()) return null;
        if (any.startsWith("http://") || any.startsWith("https://")) return any;

        String p = any.replace('\\','/').trim();

        int idx = p.indexOf("/uploads/");
        if (idx >= 0) {
            // keep from the /uploads/... segment onwards
            p = p.substring(idx);
        } else {
            // normalize things like "uploads/audio/...", "audio/...", "covers/..."
            p = p.replaceFirst("^/?uploads/?", "uploads/");
            if (!p.startsWith("uploads/")) p = "uploads/" + p.replaceFirst("^/?", "");
            p = "/" + p;
        }
        return p; // -> /uploads/...
    }

    private static String publicUrl(String path) {
        if (path == null || path.isBlank()) return null;
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        String s = path.replace("\\", "/");
        // normalize to /uploads/**
        if (!s.startsWith("/uploads/")) s = "/uploads/" + s.replaceFirst("^/?uploads/?", "");
        return s;
    }
}