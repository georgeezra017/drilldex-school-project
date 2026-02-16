package com.drilldex.drillbackend.admin;

import com.drilldex.drillbackend.admin.dto.RejectRequest;
import com.drilldex.drillbackend.chat.ChatStorageService;
import com.drilldex.drillbackend.inbox.InboxMessageService;
import com.drilldex.drillbackend.kit.Kit;
import com.drilldex.drillbackend.kit.KitRepository;
import com.drilldex.drillbackend.kit.KitService;
import com.drilldex.drillbackend.notification.Notification;
import com.drilldex.drillbackend.notification.NotificationService;
import com.drilldex.drillbackend.notification.NotificationType;
import com.drilldex.drillbackend.notification.RelatedType;
import com.drilldex.drillbackend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/kits")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminKitController {

    private final KitRepository kitRepository;
    private final InboxMessageService inboxMessageService;
    private final ChatStorageService chatStorageService;
    private final NotificationService notificationService;

    /**
     * List kits awaiting moderation.
     */
    @GetMapping("/pending")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> listPending() {
        return kitRepository.findByStatusIgnoreCaseOrderByCreatedAtDesc("pending")
                .stream()
                .map(k -> {
                    String ownerName = resolveOwnerName(k);

                    // Prefer canonical fields; keep nulls if not set (the frontend handles them)
                    String coverUrl = emptyToNull(k.getCoverImagePath());
                    String previewUrl = emptyToNull(k.getPreviewAudioPath());

                    int filesCount =
                            (k.getFilePaths() != null && !k.getFilePaths().isEmpty())
                                    ? k.getFilePaths().size()
                                    : Math.max(0, (k.getSamplesCount() + k.getLoopsCount() + k.getPresetsCount()));

                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", k.getId());
                    m.put("title", emptyToNull(k.getTitle()));
                    m.put("ownerName", ownerName);          // <-- Admin UI expects this
                    m.put("price", k.getPrice());
                    m.put("createdAt", k.getCreatedAt());   // used as submittedAt
                    m.put("status", emptyToNull(k.getStatus()));
                    m.put("coverUrl", coverUrl);
                    m.put("previewUrl", previewUrl);
                    m.put("tags", emptyToNull(k.getTags()));
                    m.put("filesCount", filesCount);
                    return m;
                })
                .toList();
    }

    private static String resolveOwnerName(Kit k) {
        User o = k.getOwner();
        if (o == null) return "Unknown";
        String dn = o.getDisplayName();
        if (dn != null && !dn.isBlank()) return dn;
        String email = o.getEmail();
        if (email != null && !email.isBlank()) {
            int at = email.indexOf('@');
            return at > 0 ? email.substring(0, at) : email;
        }
        return "Unknown";
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }


    /**
     * Detail for one kit (for the expand panel).
     */
    @GetMapping("/{id}")
    public Map<String, Object> getDetail(@PathVariable Long id) {
        Kit k = kitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));

        Map<String, Object> dto = new HashMap<>();
        dto.put("id", k.getId());
        dto.put("title", k.getTitle());
        dto.put("type", k.getType());
        dto.put("description", k.getDescription());
        dto.put("ownerName", k.getOwner() != null ? k.getOwner().getDisplayName() : "Unknown");
        dto.put("price", k.getPrice());
        dto.put("status", k.getStatus());
        dto.put("createdAt", k.getCreatedAt());
        dto.put("tags", k.getTags());
        dto.put("bpmMin", k.getBpmMin());
        dto.put("bpmMax", k.getBpmMax());
        dto.put("key", k.getKeySignature());

        dto.put("coverUrl", KitService.toPublicUrl(k.getCoverImagePath()));
        dto.put("previewUrl", KitService.toPublicUrl(k.getPreviewAudioPath()));

        dto.put("samplesCount", k.getSamplesCount());
        dto.put("loopsCount", k.getLoopsCount());
        dto.put("presetsCount", k.getPresetsCount());

        // expose stored file list as public URLs for quick spot-checks
        dto.put("files", (k.getFilePaths() == null ? List.of() :
                k.getFilePaths().stream()
                        .map(KitService::toPublicUrl)
                        .toList()));

        return dto;
    }

    /**
     * Approve -> publish.
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id) {
        Kit k = kitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));

        k.setStatus("published");
        k.setPublishedAt(Instant.now());
        kitRepository.save(k);

        User owner = k.getOwner();
        if (owner != null && owner.getId() != null) {
            String title = k.getTitle() == null ? "(untitled)" : k.getTitle();

            try {
                Notification n = notificationService.create(
                        owner,
                        NotificationType.SYSTEM,
                        RelatedType.KIT,
                        k.getId(),
                        "Your kit was approved",
                        "Your kit \"" + title + "\" is now live."
                );

                // Push to user via SSE
                notificationService.pushNotificationToUser(owner.getId(), n);
            } catch (Exception ignore) {}
        }

        return ResponseEntity.ok(Map.of("ok", true, "status", "published"));
    }


    @PostMapping("/{id}/reject")
    public ResponseEntity<?> rejectKit(@PathVariable Long id,
                                       @RequestBody(required = false) RejectRequest req) {
        Kit kit = kitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));

        String reason = Optional.ofNullable(req).map(RejectRequest::reason).orElse("").trim();

        kit.setStatus("rejected");
        if (!reason.isBlank()) {
            kit.setModerationNote(reason);
        }

        kit.setFeatured(false);
        kit.setFeaturedAt(null);
        kit.setFeaturedFrom(null);
        kit.setFeaturedUntil(null);
        kit.setFeaturedTier(null);

        kitRepository.save(kit);

        try {
            User owner = kit.getOwner();
            if (owner != null && owner.getId() != null) {
                String title = kit.getTitle() == null ? "(untitled)" : kit.getTitle();

                String body = reason.isBlank()
                        ? "Your kit \"" + title + "\" has been rejected by the moderation team."
                        : "Your kit \"" + title + "\" has been rejected by the moderation team because: " + reason;

                // Chat message
                chatStorageService.sendAdminMessageToUser(owner.getId(), body);

                // System notification with SSE push
                Notification n = notificationService.create(
                        owner,
                        NotificationType.SYSTEM,
                        RelatedType.CHAT,
                        kit.getId(),
                        "Your kit was rejected",
                        "Your kit \"" + title + "\" was rejected by the moderation team."
                );

                notificationService.pushNotificationToUser(owner.getId(), n);
            }
        } catch (Exception ignored) {
        }

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "id", kit.getId(),
                "status", kit.getStatus(),
                "moderationNote", kit.getModerationNote()
        ));
    }

}
