// src/main/java/com/drilldex/drillbackend/pack/PackModerationService.java
package com.drilldex.drillbackend.pack;

import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.inbox.InboxMessageService;
import com.drilldex.drillbackend.pack.dto.AdminPackDetailDto;
import com.drilldex.drillbackend.pack.dto.AdminPackRowDto;
import com.drilldex.drillbackend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
public class PackModerationService {

    private final PackRepository packRepository;
    private final InboxMessageService inboxMessageService;

    public List<AdminPackRowDto> listPending() {
        return packRepository.findByApprovedFalseAndRejectedFalseOrderByCreatedAtDesc()
                .stream()
                .map(this::toRow)
                .toList();
    }

    public Pack getById(Long id) {
        return packRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Pack not found"));
    }

    public AdminPackDetailDto getDetail(Long id) {
        Pack p = packRepository.findWithBeatsAndOwnerById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Pack not found"));
        return toDetail(p);
    }

    /** Return quick verification result (ok + issues). */
    public Map<String, Object> verify(Long id) {
        Pack p = packRepository.findWithBeatsAndOwnerById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Pack not found"));

        List<String> issues = new ArrayList<>();
        boolean ok = true;

        if (p.getBeats() == null || p.getBeats().isEmpty()) {
            ok = false;
            issues.add("Pack has no beats.");
        }

        if (p.getBeats() != null) {
            for (Beat b : p.getBeats()) {
                if (b.getOwner() == null || p.getOwner() == null ||
                        !Objects.equals(b.getOwner().getId(), p.getOwner().getId())) {
                    ok = false;
                    issues.add("Beat \"" + safe(b.getTitle()) + "\" is not owned by the pack owner.");
                }
                if (!b.isApproved() || b.isRejected()) {
                    ok = false;
                    issues.add("Beat \"" + safe(b.getTitle()) + "\" is not approved.");
                }
                if (!StringUtils.hasText(b.getAudioFilePath())) {
                    ok = false;
                    issues.add("Beat \"" + safe(b.getTitle()) + "\" is missing audio.");
                }
            }
        }

        if (!StringUtils.hasText(firstNonBlank(p.getAlbumCoverUrl(), p.getCoverImagePath()))) {
            ok = false;
            issues.add("Pack has no cover image.");
        }

        if (p.getPrice() == null || p.getPrice().signum() <= 0) {
            ok = false;
            issues.add("Price must be greater than 0.");
        }

        return Map.of("ok", ok, "issues", issues);
    }

    public void approve(Long id) {
        Pack p = packRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Pack not found"));
        p.setApproved(true);
        p.setRejected(false);
        packRepository.save(p);
    }

    /** Mirrors your beats flow: delete DB row; optionally delete local cover if relative path. */
    public void reject(Long id, String reason) {
        Pack p = packRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Pack not found"));

        // Notify before delete (best-effort)
        try {
            User owner = p.getOwner();
            if (owner != null && owner.getId() != null) {
                String subject = "Your pack was rejected";
                String body = (reason == null || reason.isBlank()
                        ? "Your submission was rejected by the moderation team."
                        : reason) + "\n\nPack: " + Optional.ofNullable(p.getTitle()).orElse("(untitled)");

                inboxMessageService.send(owner.getId(), subject, body);
            }
        } catch (Exception ex) {
            // Do not block rejection if messaging fails
            // log.warn("Failed to send rejection inbox message for pack {}", id, ex);
        }

        // Delete cover if it looks like a local upload path (keep your existing helper)
        String cover = p.getCoverImagePath();
        if (StringUtils.hasText(cover) && isRelativeUploadPath(cover)) {
            try {
                Path uploadsRoot = Path.of("uploads"); // adjust if your uploads root differs
                Files.deleteIfExists(uploadsRoot.resolve(cover));
            } catch (Exception ignored) {}
        }

        // Finally remove DB record
        packRepository.delete(p);
    }

    /* ---------------- mapping helpers ---------------- */

    private AdminPackRowDto toRow(Pack p) {
        String ownerName = p.getOwner() == null ? "Unknown"
                : (StringUtils.hasText(p.getOwner().getDisplayName())
                ? p.getOwner().getDisplayName()
                : p.getOwner().getEmail());

        String coverUrl = toPublicUrl(firstNonBlank(p.getAlbumCoverUrl(), p.getCoverImagePath()));
        String status = p.isApproved() ? "approved" : (p.isRejected() ? "rejected" : "pending");

        // 👉 prefer displayPrice (min enabled license); fallback to p.getPrice(); fallback to 0
        BigDecimal display = Optional.ofNullable(p.getDisplayPrice())
                .orElse(Optional.ofNullable(p.getPrice()).orElse(BigDecimal.ZERO))
                .setScale(2, java.math.RoundingMode.HALF_UP);

        return new AdminPackRowDto(
                p.getId(),
                nvl(p.getTitle(), "(untitled)"),
                ownerName,
                display,
                toInstant(p.getCreatedAt()),
                coverUrl,
                status,
                p.getBeats() == null ? 0 : p.getBeats().size()
        );
    }

    private AdminPackDetailDto toDetail(Pack p) {
        String ownerName = p.getOwner() == null ? "Unknown"
                : (StringUtils.hasText(p.getOwner().getDisplayName())
                ? p.getOwner().getDisplayName()
                : p.getOwner().getEmail());

        String coverUrl = toPublicUrl(firstNonBlank(p.getAlbumCoverUrl(), p.getCoverImagePath()));
        String status = p.isApproved() ? "approved" : (p.isRejected() ? "rejected" : "pending");

        BigDecimal display = Optional.ofNullable(p.getDisplayPrice())
                .orElse(Optional.ofNullable(p.getPrice()).orElse(BigDecimal.ZERO))
                .setScale(2, java.math.RoundingMode.HALF_UP);

        var beats = (p.getBeats() == null ? List.<Beat>of() : p.getBeats())
                .stream()
                .map(b -> new AdminPackDetailDto.BeatInPackDto(
                        b.getId(),
                        nvl(b.getTitle(), "(untitled)"),
                        b.getBpm(),
                        b.getGenre(),
                        b.getDurationInSeconds(),
                        b.isApproved() && !b.isRejected(),
                        toPublicUrl(b.getAudioFilePath()),
                        toPublicUrl(firstNonBlank(b.getAlbumCoverUrl(), b.getCoverImagePath()))
                ))
                .toList();

        return new AdminPackDetailDto(
                p.getId(),
                nvl(p.getTitle(), "(untitled)"),
                p.getDescription(),
                /* genre */ p.getGenre(),               // if you want to surface it
                display,                                // 👈 use display price
                coverUrl,
                p.getCoverImagePath(),
                ownerName,
                toInstant(p.getCreatedAt()),
                p.getUpdatedAt(),                        // if you track it on entity
                status,
                beats
        );
    }

    private static String toPublicUrl(String path) {
        if (!StringUtils.hasText(path)) return null;
        String p = path.replace("\\", "/");
        if (p.startsWith("http://") || p.startsWith("https://")) return p;
        if (!p.startsWith("/uploads/")) {
            if (p.startsWith("uploads/")) p = "/" + p;
            else p = "/uploads/" + p;
        }
        return p;
    }

    private static boolean isRelativeUploadPath(String p) {
        if (!StringUtils.hasText(p)) return false;
        String n = p.replace("\\", "/");
        return !n.startsWith("http://") && !n.startsWith("https://");
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.hasText(a)) return a;
        return StringUtils.hasText(b) ? b : null;
    }

    private static <T> T nvl(T v, T def) { return v == null ? def : v; }

    private static String nvl(String v, String def) { return (v == null || v.isBlank()) ? def : v; }

    private static Instant toInstant(Object t) {
        if (t == null) return null;
        if (t instanceof Instant i) return i;
        if (t instanceof java.time.LocalDateTime ldt) return ldt.atZone(ZoneId.systemDefault()).toInstant();
        if (t instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return null;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}