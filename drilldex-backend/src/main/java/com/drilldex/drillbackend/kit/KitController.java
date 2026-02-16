package com.drilldex.drillbackend.kit;

import com.drilldex.drillbackend.auth.CustomUserDetails;
import com.drilldex.drillbackend.dto.SearchCardDto;
import com.drilldex.drillbackend.kit.dto.*;
import com.drilldex.drillbackend.notification.*;
import com.drilldex.drillbackend.pack.dto.FeatureStartReq;
import com.drilldex.drillbackend.promotions.Promotion;
import com.drilldex.drillbackend.promotions.PromotionRepository;
import com.drilldex.drillbackend.purchase.PurchaseRepository;
import com.drilldex.drillbackend.shared.PaginatedResponse;
import com.drilldex.drillbackend.shared.SlugUtil;
import com.drilldex.drillbackend.user.CurrentUserService;
import com.drilldex.drillbackend.user.Role;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import com.drilldex.drillbackend.util.AudioUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/api/kits")
@RequiredArgsConstructor
public class KitController {

    private final KitService kitService;
    private final KitRepository kitRepository;
    private final KitFeatureService featureService;
    private final KitRepository kitRepo;
    private final KitRepository repo;
    private final UserRepository userRepository;
    private final PurchaseRepository purchaseRepository;
    private final CurrentUserService currentUserService;
    private final PromotionRepository promotionRepository;
    private final FollowerNotificationService followerNotificationService;


    @Value("${app.storage.local.web-base:/uploads}")
    private String webBase;

    private static final int NEW_WINDOW_DAYS        = 14;
    private static final int POPULAR_WINDOW_DAYS    = 30;
    private static final double TRENDING_HALFLIFE_D = 3.0; // days

    /** Recency-decayed score (plays + 3*likes) * decay(ageDays). */
    private static double trendingScore(Instant createdAt, long plays, int likes, Instant now) {
        if (createdAt == null) return 0.0;
        double base = plays + 3.0 * likes;
        double ageDays = Math.max(0.01, Duration.between(createdAt, now).toHours() / 24.0);
        double decay = Math.pow(0.5, ageDays / TRENDING_HALFLIFE_D); // half-life
        return base * decay;
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadKit(
            @RequestPart("meta") KitUploadMeta meta,
            @RequestPart(value = "cover", required = false) MultipartFile cover,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestPart(value = "zip",   required = false) MultipartFile zip,
            @AuthenticationPrincipal CustomUserDetails principal
    ) throws IOException {

        // Basic meta validation (service also validates)
        if (meta == null || meta.name() == null || meta.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Kit name is required"));
        }
        BigDecimal price = meta.price() == null ? BigDecimal.ZERO : meta.price();
        if (price.signum() < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Price must be >= 0"));
        }

        // Zip-only: reject any files[] payloads
        boolean hasFiles = files != null && files.stream().anyMatch(f -> f != null && !f.isEmpty());
        if (hasFiles) {
            return ResponseEntity.badRequest().body(Map.of("error", "Kits are zip-only. Upload a single .zip file."));
        }

        // Zip must be present and look like a zip
        if (zip == null || zip.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Upload a .zip (kits are zip-only)"));
        }
        if (!looksLikeZip(zip)) {
            return ResponseEntity.badRequest().body(Map.of("error", "File must be a .zip"));
        }

        User owner = principal.getUser();

        String plan = owner.getPlan() != null ? owner.getPlan() : "FREE";

        if ("FREE".equalsIgnoreCase(plan)) {
            long uploadedKits = kitRepository.countByOwnerId(owner.getId());

            if (uploadedKits >= 1) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You’ve reached the free plan limit: 1 kit. Upgrade to upload more."));
            }
        }

        Kit kit = kitService.createKit(owner, meta, cover, null, zip);

        followerNotificationService.notifyFollowersOfNewKit(owner, kit);


        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "Kit uploaded successfully and is awaiting admin approval.",
                "kitId", kit.getId(),
                "slug", kit.getSlug(),
                "published", false
        ));
    }



    private static boolean looksLikeZip(MultipartFile zip) {
        String name = zip.getOriginalFilename();
        String ct   = zip.getContentType();
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String c = ct   == null ? "" : ct.toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || c.contains("zip");
    }

    /** List the current user’s kits (for dashboard) */
    @GetMapping("/mine")
    public ResponseEntity<PaginatedResponse<KitSummaryDto>> listMine(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int limit
    ) {
        var owner = principal.getUser();
        Long ownerId = owner.getId();

        int pg = Math.max(0, page);
        int lim = Math.max(1, limit);
        Pageable pageable = PageRequest.of(pg, lim);

        // Fetch paginated kits
        Page<Kit> kitPage = kitRepository.findByOwnerId(ownerId, pageable);

        // Fetch all sales/earnings for this owner
        List<Object[]> stats = purchaseRepository.getKitSalesAndEarningsByOwner(ownerId);
        Map<Long, Object[]> statsMap = stats.stream()
                .collect(Collectors.toMap(row -> ((Number) row[0]).longValue(), row -> row));

        // Map kits to DTOs
        List<KitSummaryDto> dtos = kitPage.getContent().stream()
                .map(kit -> {
                    Object[] s = statsMap.get(kit.getId());
                    int sales = 0;
                    BigDecimal earnings = BigDecimal.ZERO;

                    if (s != null) {
                        sales = ((Number) s[1]).intValue();
                        Object amount = s[2];
                        if (amount instanceof BigDecimal bd) earnings = bd;
                        else if (amount instanceof Number n) earnings = BigDecimal.valueOf(n.doubleValue());
                        kit.setDownloads(sales);
                    }

                    return KitSummaryDto.from(kit, earnings, sales, kit.getDurationInSeconds(), owner);
                })
                .toList();

        return ResponseEntity.ok(new PaginatedResponse<>(
                dtos,
                (int) kitPage.getTotalElements(),
                page,
                limit
        ));
    }


    @GetMapping("/approved")
    public ResponseEntity<PaginatedResponse<Map<String, Object>>> listApproved(
            @RequestParam(defaultValue = "60") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        int pg = Math.max(0, page); // zero-based
        int offset = pg * lim;

        Instant now = Instant.now();
        Instant newCutoff = now.minus(NEW_WINDOW_DAYS, ChronoUnit.DAYS);
        Instant popularCutoff = now.minus(POPULAR_WINDOW_DAYS, ChronoUnit.DAYS);

        // Fetch all approved kits (repository returns List<Kit>)
        List<Kit> allKits = kitRepository.findPublishedForBrowse(now, Pageable.unpaged());

        // Manual pagination
        List<Kit> kitPage = allKits.stream()
                .skip(offset)
                .limit(lim)
                .toList();

        List<Map<String, Object>> paginated = kitPage.stream()
                .map(k -> {
                    String ownerName = (k.getOwner() != null)
                            ? nz(nz(k.getOwner().getDisplayName(), k.getOwner().getEmail()), "Unknown")
                            : "Unknown";

                    boolean featured = featureService.isKitCurrentlyFeatured(k.getId());
                    boolean isNew = k.getCreatedAt() != null && k.getCreatedAt().isAfter(newCutoff);

                    long plays = k.getPlayCount();
                    int likes = k.getLikeCount();
                    boolean isPopular = k.getCreatedAt() != null
                            && k.getCreatedAt().isAfter(popularCutoff)
                            && (plays + 3L * likes) >= 50;

                    double trendScore = trendingScore(k.getCreatedAt(), plays, likes, now);
                    boolean isTrending = trendScore >= 20.0;

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", k.getId());
                    m.put("slug", k.getSlug());
                    m.put("title", nz(k.getTitle(), "(untitled)"));
                    m.put("artistName", ownerName);
                    m.put("ownerName", ownerName);
                    m.put("ownerId", k.getOwner() != null ? k.getOwner().getId() : null);
                    m.put("price", k.getPrice());
                    m.put("coverUrl", nullIfBlank(k.getCoverImagePath()));
                    m.put("previewUrl", resolveKitPreviewUrl(k));
                    m.put("tags", splitTags(k.getTags()));
                    m.put("genre", nullIfBlank(k.getType()));
                    m.put("durationInSeconds", k.getDurationInSeconds());
                    m.put("createdAt", k.getCreatedAt());
                    m.put("featured", featured);
                    m.put("isNew", isNew);
                    m.put("isPopular", isPopular);
                    m.put("isTrending", isTrending);
                    return m;
                })
                .toList();

        return ResponseEntity.ok(new PaginatedResponse<>(paginated,
                allKits.size(), pg, lim));
    }

    /* --- helpers --- */
    private static String nz(String s, String def) { return (s == null || s.isBlank()) ? def : s; }
    private static String nullIfBlank(String s) { return (s == null || s.isBlank()) ? null : s; }
    private static List<String> splitTags(String t) {
        if (t == null || t.isBlank()) return List.of();
        return Arrays.stream(t.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Simple delete (owner only). */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteKit(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails principal
    ) throws IOException {
        var owner = principal.getUser();
        kitService.deleteKit(id, owner);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    @GetMapping("/{id}/preview-playlist")
    public List<Map<String, Object>> getKitPreviewPlaylist(@PathVariable Long id) {
        Kit kit = kitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));

        if (!"published".equalsIgnoreCase(kit.getStatus())) {
            return List.of();
        }

        // originals under kits/audio (local preview names are randomized, so use the originals)
        List<String> files = Optional.ofNullable(kit.getFilePaths()).orElse(List.of());
        List<String> originals = files.stream()
                .filter(Objects::nonNull)
                .map(s -> s.replace('\\', '/'))
                .filter(s -> s.toLowerCase(Locale.ROOT).contains("/kits/audio/"))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        List<String> previews = originals;

        // fallback to kit-level preview if no per-file audio
        if (previews.isEmpty()) {
            String fallback = nullIfBlank(kit.getPreviewAudioPath());
            if (fallback == null) return List.of();
            previews = List.of(fallback);
        }

        String ownerName = (kit.getOwner() != null)
                ? nz(nz(kit.getOwner().getDisplayName(), kit.getOwner().getEmail()), "Unknown")
                : "Unknown";
        String title  = nz(kit.getTitle(), "(untitled)");
        String cover  = nullIfBlank(kit.getCoverImagePath());

        List<Map<String, Object>> out = new ArrayList<>(previews.size());
        for (int i = 0; i < previews.size(); i++) {
            String key = previews.get(i);
            String url = absUrl(key);                     // ← ABSOLUTE URL
            String fileName = key.substring(key.lastIndexOf('/') + 1);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", kit.getId() + ":" + i);
            item.put("title", previews.size() == 1 ? title : (title + " — " + fileName));
            item.put("artistName", ownerName);
            item.put("coverUrl", absUrl(cover));
            item.put("previewUrl", url);                  // ← ABSOLUTE URL
            out.add(item);
        }
        return out;
    }

    /** key like ".../kits/audio/Name.ext" → ".../kits/previews/Name.mp3" (preserves any prefix like "app-prod/"). */
    private static String toPreviewKeyMp3(String originalKey) {
        if (originalKey == null || originalKey.isBlank()) return null;
        String s = AudioUtils.normalizeStorageKey(originalKey);
        if (s == null || s.isBlank()) return null;
        s = s.replace('\\', '/');
        String lower = s.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("/kits/audio/");
        if (idx < 0) return null;
        String prefix = s.substring(0, idx); // e.g. ""
        String rest   = s.substring(idx + "/kits/audio/".length());
        int dot = rest.lastIndexOf('.');
        String base = (dot >= 0 ? rest.substring(0, dot) : rest);
        return (prefix.isEmpty() ? "" : prefix + "/") + "kits/previews/" + base + ".mp3";
    }

    /** Turn a stored key into an absolute URL using local web base; pass through if already absolute. */
    private String absUrl(String maybeKeyOrUrl) {
        if (maybeKeyOrUrl == null || maybeKeyOrUrl.isBlank()) return null;
        String s = maybeKeyOrUrl.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) return s;
        if (s.startsWith("/uploads/")) return s;
        String base = (webBase == null ? "/uploads" : webBase).replaceAll("/+$", "");
        String key  = s.replaceAll("^/+", "");
        return base + "/" + key;
    }

    private String resolveKitPreviewUrl(Kit kit) {
        if (kit == null) return null;
        List<String> files = kit.getFilePaths() == null ? List.of() : kit.getFilePaths();
        String preview = nullIfBlank(kit.getPreviewAudioPath());
        if (preview != null && (preview.startsWith("http://") || preview.startsWith("https://"))) {
            String local = files.stream()
                    .filter(Objects::nonNull)
                    .map(s -> s.replace('\\', '/'))
                    .filter(s -> s.toLowerCase(Locale.ROOT).contains("/kits/audio/"))
                    .findFirst()
                    .orElse(null);
            if (local != null) return absUrl(local);
        }
        if (preview != null) return absUrl(preview);
        return files.stream()
                .filter(Objects::nonNull)
                .map(s -> s.replace('\\', '/'))
                .filter(s -> s.toLowerCase(Locale.ROOT).contains("/kits/audio/"))
                .findFirst()
                .map(this::absUrl)
                .orElse(null);
    }

    @GetMapping("/featured")
    public ResponseEntity<PaginatedResponse<KitSummaryDto>> getFeaturedKits(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        int lim = Math.max(1, Math.min(limit, 100));
        int pg = Math.max(1, page);
        int offset = (pg - 1) * lim;

        // Total count of active KIT promotions
        int totalCount = promotionRepository.countActiveByType(
                Promotion.TargetType.KIT.name(),  // string as expected
                Instant.now()
        );

        // Fetch paginated kits from service
        List<KitSummaryDto> kits = kitService.getFeaturedKitsFromPromotions(offset, lim);

        return ResponseEntity.ok(new PaginatedResponse<>(kits, totalCount, pg, lim));
    }

    @PostMapping("/{id}/play")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markPlay(@PathVariable Long id) {
        kitService.incrementPlayCount(id);
    }

    @GetMapping("/by-slug/{slug}")
    public Map<String, Object> getKitBySlug(@PathVariable String slug) {
        String normalized = SlugUtil.toSlug(slug);
        Kit kit = kitRepository.findBySlug(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));

        if (!"published".equals(kit.getStatus()) || kit.getPublishedAt() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not available");
        }

        String ownerName = (kit.getOwner() != null)
                ? Optional.ofNullable(kit.getOwner().getDisplayName()).filter(s -> !s.isBlank()).orElse("Unknown")
                : "Unknown";

        String coverUrl = KitService.toPublicUrl(kit.getCoverImagePath());

        // flags
        Instant now = Instant.now();
        Instant newCutoff = now.minus(NEW_WINDOW_DAYS, ChronoUnit.DAYS);
        Instant popularCutoff = now.minus(POPULAR_WINDOW_DAYS, ChronoUnit.DAYS);

        boolean featured = featureService.isKitCurrentlyFeatured(kit.getId());

        boolean isNew = kit.getCreatedAt() != null && kit.getCreatedAt().isAfter(newCutoff);

        long plays = kit.getPlayCount();   // primitives on Kit, so never null
        int likes  = kit.getLikeCount();

        boolean isPopular = kit.getCreatedAt() != null
                && kit.getCreatedAt().isAfter(popularCutoff)
                && (plays + 3L * likes) >= 50; // adjust threshold to taste

        double trendScore = trendingScore(kit.getCreatedAt(), plays, likes, now);
        boolean isTrending = trendScore >= 20.0; // adjust threshold to taste

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", kit.getId());
        m.put("slug", kit.getSlug());
        m.put("title", kit.getTitle());
        m.put("artistName", ownerName);
        m.put("ownerName", ownerName);
        m.put("coverUrl", coverUrl);
        m.put("price", kit.getPrice());
        m.put("durationInSeconds", kit.getDurationInSeconds());
        m.put("createdAt", kit.getCreatedAt());
        m.put("tags", splitTags(kit.getTags()));
        m.put("genre", nullIfBlank(kit.getType()));

        // extras
        m.put("playCount", plays);
        m.put("likeCount", likes);
        m.put("featured", featured);
        m.put("isNew", isNew);
        m.put("isPopular", isPopular);
        m.put("isTrending", isTrending);
        m.put("trendingScore", trendScore); // optional for client sorting/debug

        return m;
    }

    // src/main/java/com/drilldex/drillbackend/kit/KitController.java
    // imports you likely already have:
// import org.springframework.web.bind.annotation.*;
// import org.springframework.web.server.ResponseStatusException;
// import org.springframework.http.HttpStatus;
// import java.util.*;
// import java.util.stream.*;

    // --- KIT CONTENTS BY ID ---
    @GetMapping("/{id}/contents")
    public List<Map<String, Object>> getKitContents(@PathVariable Long id) {
        Kit kit = kitRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));

        final String kitCover = absUrl(kit.getCoverImagePath());

        final String artist =
                (kit.getOwner() != null && kit.getOwner().getDisplayName() != null && !kit.getOwner().getDisplayName().isBlank())
                        ? kit.getOwner().getDisplayName().trim()
                        : "Unknown";

        // Build content rows from the stored file paths.
        // If you later model real sub-items (Samples/Loops/Presets entities), replace this mapping.
        List<String> files = kit.getFilePaths() == null ? List.of() : kit.getFilePaths();

        return IntStream.range(0, files.size())
                .mapToObj(i -> {
                    String path = Optional.ofNullable(files.get(i)).orElse("").replace('\\','/').trim();

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", i);                               // synthetic id based on index
                    m.put("title", fileBaseName(path));           // filename without extension
                    m.put("artistName", artist);
                    m.put("durationInSeconds", 0);                // unknown at this level
                    m.put("bpm", null);                           // unknown at this level
                    m.put("coverUrl", kitCover);

                    // Use audio files as previews (local storage randomizes preview names)
                    if (!path.isEmpty()) {
                        String normalized = path.replace('\\','/').toLowerCase(Locale.ROOT);
                        if (normalized.contains("/kits/audio/")) {
                            m.put("previewUrl", absUrl(path));
                        } else {
                            m.put("previewUrl", null);
                        }
                    } else {
                        m.put("previewUrl", null);
                    }
                    return m;
                })
                .toList();
    }

    /** Helper: extract base name (filename minus extension) for titles. */
    private static String fileBaseName(String path) {
        if (path == null || path.isBlank()) return "Untitled";
        String p = path.replace('\\','/');                 // normalize separators
        String fname = p.substring(p.lastIndexOf('/') + 1);
        String base  = fname.replaceAll("\\.[^.]+$", "");
        return base.isBlank() ? "Untitled" : base;
    }

    // --- KIT CONTENTS BY SLUG ---
    @GetMapping("/by-slug/{slug}/contents")
    public List<Map<String, Object>> getKitContentsBySlug(@PathVariable String slug) {
        var kit = kitRepository.findBySlug(SlugUtil.toSlug(slug))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));

        if (!"published".equals(kit.getStatus()) || kit.getPublishedAt() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not available");
        }

        return getKitContents(kit.getId()); // delegate to the method above
    }

    @GetMapping("/{id}/comments")
    @PreAuthorize("permitAll()") // public read
    public java.util.List<com.drilldex.drillbackend.kit.dto.KitCommentDto> getKitComments(@PathVariable Long id) {
        Kit kit = kitRepository.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Kit not found"));

        return kitRepository.findComments(kit.getId())
                .stream()
                .map(com.drilldex.drillbackend.kit.dto.KitCommentDto::from)
                .toList();
    }


    @PostMapping("/{id}/comments")
    @PreAuthorize("isAuthenticated()")
    public KitCommentDto addKitComment(
            @PathVariable Long id,
            @RequestBody CommentRequest body,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        if (body == null || body.text() == null || body.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text is required");
        }

        Kit kit = kitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));

        var c = new KitComment();
        c.setKit(kit);
        c.setUser(principal.getUser());
        c.setText(body.text().trim());

        // persist via Kit
        kit.getComments().add(c);
        kit.setCommentCount(kit.getCommentCount() + 1);
        kitRepository.save(kit);

        return KitCommentDto.from(c);
    }

    @DeleteMapping("/{kitId}/comments/{commentId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteKitComment(
            @PathVariable Long kitId,
            @PathVariable Long commentId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Kit kit = kitRepository.findById(kitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));

        var comment = kitRepository.findComment(kitId, commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));

        var user = principal.getUser();
        boolean isOwner = comment.getUser().getId().equals(user.getId());
        boolean isAdmin = user.getRole() == Role.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to delete this comment");
        }

        kit.getComments().removeIf(cc -> java.util.Objects.equals(cc.getId(), commentId));
        kit.setCommentCount(Math.max(0, kit.getCommentCount() - 1));
        kitRepository.save(kit);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{kitId}/comments/{commentId}")
    @PreAuthorize("isAuthenticated()")
    public KitCommentDto editKitComment(
            @PathVariable Long kitId,
            @PathVariable Long commentId,
            @RequestBody CommentRequest body,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        if (body == null || body.text() == null || body.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text is required");
        }

        var comment = kitRepository.findComment(kitId, commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));

        var user = principal.getUser();
        boolean isOwner = comment.getUser().getId().equals(user.getId());
        boolean isAdmin = user.getRole() == Role.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to edit this comment");
        }

        comment.setText(body.text().trim());

        // Save via owning Kit so cascading persists the updated comment
        var kit = kitRepository.findById(kitId).orElseThrow();
        kitRepository.save(kit);

        return KitCommentDto.from(comment);
    }

    @PostMapping("/{kitId}/comments/{commentId}/like")
    @PreAuthorize("isAuthenticated()")
    public java.util.Map<String, Object> likeKitComment(
            @PathVariable Long kitId,
            @PathVariable Long commentId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        var comment = kitRepository.findComment(kitId, commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));

        // naive counter (no per-user dedupe here)
        comment.setLikeCount((comment.getLikeCount() == null ? 0 : comment.getLikeCount()) + 1);

        var kit = kitRepository.findById(kitId).orElseThrow();
        kitRepository.save(kit);
        return java.util.Map.of("liked", true, "likeCount", comment.getLikeCount());
    }

    @PostMapping("/{kitId}/comments/{commentId}/unlike")
    @PreAuthorize("isAuthenticated()")
    public java.util.Map<String, Object> unlikeKitComment(
            @PathVariable Long kitId,
            @PathVariable Long commentId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        var comment = kitRepository.findComment(kitId, commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));

        int current = (comment.getLikeCount() == null ? 0 : comment.getLikeCount());
        comment.setLikeCount(Math.max(0, current - 1));

        var kit = kitRepository.findById(kitId).orElseThrow();
        kitRepository.save(kit);
        return java.util.Map.of("liked", false, "likeCount", comment.getLikeCount());
    }

    @GetMapping("/search")
    public ResponseEntity<PaginatedResponse<SearchCardDto>> search(
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "limit", defaultValue = "24") int limit,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        int lim = Math.max(1, Math.min(limit, 60));
        int pg = Math.max(1, page);
        PageRequest pageable = PageRequest.of(pg - 1, lim);

        List<Kit> kits;

        if (q == null || q.trim().isBlank()) {
            kits = repo.listRecentPaginated(pageable).getContent();
        } else {
            String qLike = q.trim().toLowerCase();
            String qNoSpace = qLike.replaceAll("[^a-z0-9]", "");
            kits = repo.searchFlexiblePaginated(qLike, qNoSpace, pageable).getContent();
        }

        Long userId = (principal != null) ? principal.getUser().getId() : null;
        Set<Long> likedIds = (userId != null && !kits.isEmpty())
                ? new HashSet<>(repo.findLikedKitIds(userId, kits.stream().map(Kit::getId).toList()))
                : Set.of();

        Instant now = Instant.now();

        List<SearchCardDto> out = kits.stream().map(k -> {
            String coverUrl = KitService.toPublicUrl(k.getCoverImagePath());
            String ownerName = (k.getOwner() != null) ? displayNameOf(k.getOwner()) : "Unknown";

            // badges
            boolean featured = k.getFeaturedUntil() != null && k.getFeaturedUntil().isAfter(now)
                    || k.isFeatured();
            boolean popular = kitService.getPopularKitsByOwner(k.getOwner().getId(), PageRequest.of(0, 20))
                    .stream().anyMatch(pk -> pk.getId().equals(k.getId()));

            boolean trending = kitService.getTrendingKitsByOwner(k.getOwner().getId(), PageRequest.of(0, 20))
                    .stream().anyMatch(tk -> tk.getId().equals(k.getId()));

            boolean isNew = kitService.getNewKitsByOwner(k.getOwner().getId(), PageRequest.of(0, 20))
                    .stream().anyMatch(nk -> nk.getId().equals(k.getId()));

            Long ownerId = (k.getOwner() != null) ? k.getOwner().getId() : null;

            return SearchCardDto.builder()
                    .id(k.getId())
                    .slug(k.getSlug())
                    .title(k.getTitle())
                    .coverUrl(coverUrl)
                    .price(k.getPrice())
                    .createdAt(k.getCreatedAt())
                    ._kind("KIT")
                    .artistName(ownerName)
                    .artistId(ownerId)
                    .ownerName(ownerName)
                    .tags(k.getTags())
                    .likeCount(k.getLikeCount())
                    .liked(likedIds.contains(k.getId()))
                    .genre(k.getType())
                    .durationSec(k.getDurationInSeconds())
                    .bpmMin(k.getBpmMin())
                    .bpmMax(k.getBpmMax())
                    .bpm(null)
                    .location(null)
                    .featured(featured)
                    .featuredTier(k.getFeaturedTier())
                    .popular(popular)
                    .trending(trending)
                    .isNew(isNew)
                    .build();
        }).toList();

        return ResponseEntity.ok(new PaginatedResponse<>(out, kits.size(), pg, lim));
    }

    private static String displayNameOf(com.drilldex.drillbackend.user.User u) {
        if (u == null) return null;
        var dn = u.getDisplayName();
        if (dn != null && !dn.isBlank()) return dn;
        var email = u.getEmail();
        return (email != null && !email.isBlank()) ? email : null;
    }

    @PreAuthorize("permitAll()")
    @GetMapping("/{id}")
    public Map<String, Object> getKitPublic(@PathVariable Long id) {
        Kit kit = kitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));

        String ownerName = (kit.getOwner() != null)
                ? Optional.ofNullable(kit.getOwner().getDisplayName()).filter(s -> !s.isBlank()).orElse("Unknown")
                : "Unknown";

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", kit.getId());
        m.put("slug", kit.getSlug());
        m.put("title", kit.getTitle());
        m.put("artistName", ownerName);
        m.put("ownerName", ownerName);
        m.put("coverUrl", KitService.toPublicUrl(kit.getCoverImagePath()));
        m.put("price", kit.getPrice());                       // BigDecimal -> JSON number
        m.put("durationInSeconds", kit.getDurationInSeconds());
        m.put("createdAt", kit.getCreatedAt());
        m.put("tags", kit.getTags() == null ? List.of() :
                Arrays.stream(kit.getTags().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        m.put("genre", (kit.getType() == null || kit.getType().isBlank()) ? null : kit.getType());
        return m;
    }

    private Long currentUserId(org.springframework.security.core.Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        Object p = auth.getPrincipal();
        if (p instanceof com.drilldex.drillbackend.auth.CustomUserDetails cud) {
            return cud.getUser().getId();
        }
        if (p instanceof com.drilldex.drillbackend.user.User u) {
            return u.getId();
        }
        return null;
    }

    @GetMapping("/{id}/liked")
    @PreAuthorize("permitAll()")
    public java.util.Map<String, Object> isKitLiked(
            @PathVariable Long id,
            org.springframework.security.core.Authentication auth
    ) {
        if (!kitRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found");
        }
        Long uid = currentUserId(auth);
        if (uid == null) return java.util.Map.of("liked", false);

        boolean liked = kitRepository.findLikedKitIds(uid, java.util.List.of(id)).contains(id);
        return java.util.Map.of("liked", liked);
    }

    @PostMapping("/{id}/like")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> likeKit(@PathVariable Long id, Authentication auth) {
        var kit = kitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));
        Long uid = currentUserId(auth);
        if (uid == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var user = userRepository.findById(uid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        if (kit.getUpvotedBy().add(user)) {
            kit.setLikeCount(Math.max(0, kit.getLikeCount()) + 1);
            kitRepository.save(kit);
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/like")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> unlikeKit(@PathVariable Long id, Authentication auth) {
        var kit = kitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));
        Long uid = currentUserId(auth);
        if (uid == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);

        boolean removed = kit.getUpvotedBy().removeIf(u -> u.getId().equals(uid));
        if (removed) {
            kit.setLikeCount(Math.max(0, kit.getLikeCount() - 1));
            kitRepository.save(kit);
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/new")
    public ResponseEntity<PaginatedResponse<KitSummaryDto>> getNewKits(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        int lim = Math.max(1, Math.min(100, limit));
        int pg  = Math.max(0, page);

        // 1️⃣ Delegate fetching new kits + owner-scoped badges to the service
        KitService.NewKitsPage newKitsPage = kitService.getGlobalNewKits(pg, lim, currentUserService.getCurrentUserOrNull());

        // 2️⃣ Return paginated response
        return ResponseEntity.ok(new PaginatedResponse<>(
                newKitsPage.kits(),
                newKitsPage.totalCount(),
                pg,
                lim
        ));
    }

    @GetMapping("/popular")
    public ResponseEntity<PaginatedResponse<KitSummaryDto>> getPopularKits(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        int lim = Math.max(1, Math.min(100, limit));
        int pg  = Math.max(0, page);

        // 1️⃣ Fetch global popular kits (public)
        KitService.NewKitsPage popularPage = kitService.getGlobalPopularKits(pg, lim, null);

        // 2️⃣ Return paginated response
        return ResponseEntity.ok(new PaginatedResponse<>(
                popularPage.kits(),
                popularPage.totalCount(),
                pg,
                lim
        ));
    }

    @GetMapping("/trending")
    public ResponseEntity<PaginatedResponse<KitSummaryDto>> getTrendingKits(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        int lim = Math.max(1, Math.min(100, limit));
        int pg  = Math.max(0, page);

        // 1️⃣ Delegate fetching global trending kits
        KitService.NewKitsPage trendingPage = kitService.getGlobalTrendingKits(pg, lim, null);

        // 2️⃣ Return paginated response
        return ResponseEntity.ok(new PaginatedResponse<>(
                trendingPage.kits(),
                trendingPage.totalCount(),
                pg,
                lim
        ));
    }

}
