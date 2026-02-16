package com.drilldex.drillbackend.pack;

import com.drilldex.drillbackend.auth.CustomUserDetails;
import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.dto.SearchCardDto;
import com.drilldex.drillbackend.notification.*;
import com.drilldex.drillbackend.pack.dto.*;
import com.drilldex.drillbackend.pack.mapper.PackMapper;
import com.drilldex.drillbackend.purchase.PurchaseRepository;
import com.drilldex.drillbackend.shared.PaginatedResponse;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.shared.SlugUtil;
import com.drilldex.drillbackend.user.UserRepository;
import com.drilldex.drillbackend.util.AudioUtils;
import lombok.RequiredArgsConstructor;
import com.drilldex.drillbackend.shared.SlugUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import com.drilldex.drillbackend.util.AudioUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import java.io.IOException;
import java.io.InputStream;
import java.time.temporal.ChronoUnit;
import java.util.*;


@RestController
@RequestMapping("/api/packs")
@RequiredArgsConstructor
public class PackController {

    private final PackService packService;
    private final PackRepository packRepo;
    private final PackService service;
    private final PackRepository packRepository;
    private final PackRepository repo;
    private final UserRepository userRepository;
    private final PurchaseRepository purchaseRepository;
    private final NotificationService notificationService;
    private final FollowerNotificationService followerNotificationService;


    @org.springframework.beans.factory.annotation.Value("${app.storage.local.web-base:/uploads}")
    private String webBase;

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadPack(
            @RequestPart("meta") PackUploadMeta meta,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestPart(value = "zip",   required = false) MultipartFile zip,
            @RequestPart(value = "cover", required = false) MultipartFile cover,
            @RequestPart(value = "stems", required = false) MultipartFile stemsZip,
            @AuthenticationPrincipal CustomUserDetails principal
    ) throws IOException {

        // ---- meta/text fields required ----
        if (meta == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "meta is required"));
        }
        if (meta.name() == null || meta.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Pack name is required"));
        }
        if (meta.description() == null || meta.description().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Pack description is required"));
        }
        if (meta.tags() == null || meta.tags().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "At least one tag is required"));
        }

        // ---- cover required ----
        if (cover == null || cover.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cover image is required"));
        }

        // ---- licenses: at least one enabled with positive price and valid type ----
        boolean hasValidLicense = false;
        if (meta.licenses() != null) {
            for (PackUploadMeta.LicenseLine l : meta.licenses()) {
                if (l == null || !Boolean.TRUE.equals(l.enabled())) continue;

                if (l.price() == null || l.price().signum() <= 0) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Enabled licenses must have a positive price"));
                }
                try {
                    com.drilldex.drillbackend.beat.LicenseType.valueOf(
                            l.type().toUpperCase(java.util.Locale.ROOT));
                } catch (Exception ex) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Invalid license type: " + l.type()));
                }
                hasValidLicense = true;
            }
        }
        if (!hasValidLicense) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Enable at least one license with a positive price"));
        }

        boolean premiumOrExclusiveEnabled = meta.licenses() != null &&
                meta.licenses().stream()
                        .filter(l -> l != null && Boolean.TRUE.equals(l.enabled()))
                        .map(l -> l.type().toUpperCase(Locale.ROOT))
                        .anyMatch(t -> t.equals("PREMIUM") || t.equals("EXCLUSIVE"));

        if (premiumOrExclusiveEnabled && (stemsZip == null || stemsZip.isEmpty())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Premium or Exclusive licenses require uploading a stems ZIP file."
            ));
        }

        // ---- files OR zip required (not both) ----
        List<MultipartFile> safeFiles = (files == null ? java.util.List.of() : files);
        boolean hasFiles = safeFiles.stream().anyMatch(f -> f != null && !f.isEmpty());
        boolean hasZip   = (zip != null && !zip.isEmpty());

        if (!hasFiles && !hasZip) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Provide audio files (.mp3/.wav) or a single .zip"));
        }
        if (hasFiles && hasZip) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Upload either audio files OR a zip, not both"));
        }

        // ---- validate zip looks like a zip ----
        if (hasZip && !looksLikeZip(zip)) {
            return ResponseEntity.badRequest().body(Map.of("error", "File must be a .zip"));
        }

        // ---- validate each provided file is mp3/wav and not macOS junk ----
        if (hasFiles) {
            for (MultipartFile f : safeFiles) {
                if (f == null || f.isEmpty()) continue;
                String name = java.util.Optional.ofNullable(f.getOriginalFilename()).orElse("");
                if (isMacJunkName(name)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Remove macOS metadata files (e.g. __MACOSX/ or ._ prefixed files)"));
                }
                if (!isAudioName(name)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Only .mp3 or .wav files are allowed"));
                }
            }
        }

        var artist = principal.getUser();

        String plan = artist.getPlan() != null ? artist.getPlan() : "FREE";

        if ("FREE".equalsIgnoreCase(plan)) {
            long uploadedPacks = packRepository.countByOwnerId(artist.getId());

            if (uploadedPacks >= 1) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You’ve reached the free plan limit: 1 pack. Upgrade to upload more."));
            }
        }

        var pack = packService.uploadPackMixed(
                artist,
                meta,
                cover,
                hasFiles ? safeFiles : null,
                hasZip ? zip : null,
                stemsZip
        );

        followerNotificationService.notifyFollowersOfNewPack(artist, pack);

        // stays pending admin approval; service sets approved=false
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "Pack uploaded successfully and is awaiting admin approval.",
                "packId", pack.getId(),
                "slug",  pack.getSlug(),
                "approved", false
        ));
    }

    // ---- helpers (same controller class) ----
    private static boolean isAudioName(String name) {
        if (name == null) return false;
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        base = (slash >= 0 ? base.substring(slash + 1) : base).toLowerCase(java.util.Locale.ROOT);
        return base.endsWith(".mp3") || base.endsWith(".wav");
    }

    private static boolean isMacJunkName(String name) {
        if (name == null) return false;
        String n = name.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        String base = n.substring(n.lastIndexOf('/') + 1);
        return n.startsWith("__macosx/") || base.startsWith("._") || base.equals(".ds_store");
    }

    private static boolean looksLikeZip(MultipartFile f) {
        String name = Optional.ofNullable(f.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) return true;

        String ct = Optional.ofNullable(f.getContentType()).orElse("").toLowerCase(Locale.ROOT);
        if (ct.contains("zip") || ct.equals("application/octet-stream")) {
            try (InputStream in = f.getInputStream()) {
                byte[] sig = in.readNBytes(4);
                return sig.length >= 2 && sig[0] == 'P' && sig[1] == 'K';
            } catch (IOException ignore) {}
        }

        // final fallback: some clients lie about both name and content-type
        try (InputStream in = f.getInputStream()) {
            byte[] sig = in.readNBytes(4);
            return sig.length >= 2 && sig[0] == 'P' && sig[1] == 'K';
        } catch (IOException ignore) {}

        return false;
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> editPack(
            @PathVariable Long id,
            @RequestBody PackUpdateRequest req,
            @AuthenticationPrincipal CustomUserDetails principal
    ) throws IOException {
        User artist = principal.getUser();
        packService.updatePack(id, req, artist);
        return ResponseEntity.ok(Map.of("message", "Pack updated"));
    }

    @GetMapping("/approved")
    public ResponseEntity<PaginatedResponse<PackDto>> getApprovedPacks(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        Pageable pageable = PageRequest.of(page, limit);
        Page<Pack> resultPage = packService.getApprovedNotRejectedPacks(pageable);

        List<PackDto> dtos = resultPage.getContent().stream()
                .map(PackMapper::toDto)
                .toList();

        return ResponseEntity.ok(new PaginatedResponse<>(dtos,
                (int) resultPage.getTotalElements(), page, limit));
    }

    @GetMapping("/{id}/licenses")
    public List<Map<String, Object>> listPackLicenses(@PathVariable Long id) {
        Pack pack = packRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        return pack.getLicenses().stream()
                .filter(PackLicense::isEnabled)
                .map(pl -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", pl.getId());
                    m.put("type", pl.getType().name());
                    m.put("price", pl.getPrice());
                    return m;
                })
                .toList();
    }

    @PostMapping("/{id}/play")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markPlay(@PathVariable Long id) {
        packService.incrementPlayCount(id);
    }

    @GetMapping("/{id}/preview-playlist")
    public ResponseEntity<?> getPackPreviewPlaylist(@PathVariable Long id) {
        Pack pack = packRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));

        final String packCover = toPublicUrl(pack.getCoverImagePath());

        List<Map<String, Object>> items = pack.getBeats().stream()
                .map(b -> {
                    String url = beatPreviewUrl(b);
                    if (url == null || url.isBlank()) return null;

                    Map<String, Object> m = new HashMap<>();
                    m.put("id", b.getId());
                    m.put("title", b.getTitle());
                    m.put("artistName", b.getArtist());
                    m.put("coverUrl", packCover);   // always use pack cover
                    m.put("previewUrl", url);
                    return m;
                })
                .filter(Objects::nonNull)
                .toList();

        return ResponseEntity.ok(items);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getPackDetail(@PathVariable Long id) {
        Pack pack = packRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));

        final String packCover = toPublicUrl(pack.getCoverImagePath());

        Map<String, Object> dto = new HashMap<>();
        dto.put("id", pack.getId());
        dto.put("slug", pack.getSlug());
        dto.put("title", pack.getTitle());
        dto.put("description", pack.getDescription());
        dto.put("ownerName", pack.getOwner() != null ? pack.getOwner().getDisplayName() : "Unknown");
        dto.put("tags", pack.getTags());
        dto.put("price", pack.getPrice());
        dto.put("coverUrl", packCover);

        List<Map<String, Object>> beats = pack.getBeats().stream()
                .map(b -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", b.getId());
                    m.put("title", b.getTitle());
                    m.put("artistName", b.getArtist());
                    m.put("durationInSeconds", b.getDurationInSeconds());
                    m.put("bpm", b.getBpm());
                    m.put("coverUrl", packCover);  // always use pack cover

                    m.put("previewUrl", beatPreviewUrl(b));

                    return m;
                })
                .toList();

        dto.put("beats", beats);
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/new")
    public ResponseEntity<PaginatedResponse<PackSummaryDto>> getNewPacks(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        int capped = Math.max(1, Math.min(limit, 100));
        int pg = Math.max(0, page);

        Page<Pack> packPage = service.getGlobalNewPacksPage(pg, capped);
        int totalCount = service.getTotalNewPacks();

        List<PackSummaryDto> dtos = packPage.getContent().stream()
                .map(p -> PackSummaryDto.from(
                        p,
                        computeEarnings(p),
                        computeSales(p),
                        computeDuration(p)
                ))
                .toList();

        return ResponseEntity.ok(new PaginatedResponse<>(dtos, totalCount, pg, capped));
    }

    @GetMapping("/popular")
    public ResponseEntity<PaginatedResponse<PackSummaryDto>> getPopularPacks(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        int cappedLimit = Math.max(1, Math.min(limit, 100));
        int pg = Math.max(0, page);

        List<Pack> packs = service.getGlobalPopularPacks(pg, cappedLimit);

        List<PackSummaryDto> dtos = packs.stream()
                .map(p -> PackSummaryDto.from(
                        p,
                        computeEarnings(p),
                        computeSales(p),
                        computeDuration(p)
                ))
                .toList();

        return ResponseEntity.ok(new PaginatedResponse<>(dtos, packs.size(), pg, cappedLimit));
    }

    @GetMapping("/trending")
    public ResponseEntity<PaginatedResponse<PackSummaryDto>> getTrendingPacks(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        // Use the new global trending service method
        List<Pack> packs = service.getGlobalTrendingPacks(page, limit);

        // Map to DTOs
        List<PackSummaryDto> dtos = packs.stream()
                .map(p -> PackSummaryDto.from(
                        p,
                        computeEarnings(p),
                        computeSales(p),
                        computeDuration(p)
                ))
                .toList();

        // Return as paginated response
        return ResponseEntity.ok(new PaginatedResponse<>(dtos, dtos.size(), page, limit));
    }

    // --- helpers ---

    private static List<Pack> paginate(List<Pack> packs, int limit, int page) {
        int start = page * limit;
        int end = Math.min(start + limit, packs.size());
        if (start >= end) return List.of();
        return packs.subList(start, end);
    }

    private static int computeDuration(Pack pack) {
        if (pack.getBeats() == null || pack.getBeats().isEmpty()) return 0;

        return pack.getBeats().stream().mapToInt(beat -> {
            try {
                return AudioUtils.getDurationInSeconds(beat.getAudioFilePath());
            } catch (Exception e) {
                return 0; // fallback if duration fails
            }
        }).sum();
    }

    private BigDecimal computeEarnings(Pack pack) {
        var rows = purchaseRepository.getPackSalesAndEarnings(pack.getId());
        Object[] stats = (rows != null && !rows.isEmpty()) ? rows.get(0) : null;

        if (stats != null && stats.length == 2) {
            Object rawEarnings = stats[1];
            if (rawEarnings instanceof BigDecimal bd) return bd;
            if (rawEarnings instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        }

        return BigDecimal.ZERO;
    }

    private int computeSales(Pack pack) {
        var rows = purchaseRepository.getPackSalesAndEarnings(pack.getId());
        Object[] stats = (rows != null && !rows.isEmpty()) ? rows.get(0) : null;

        if (stats != null && stats.length >= 1) {
            Object rawSales = stats[0];
            if (rawSales instanceof Number n) return n.intValue();
        }

        return 0;
    }

    /* ---------- helpers (private) ---------- */

    @PostMapping("/{id}/feature")
    public ResponseEntity<FeaturedPackDto> startFeatureForPackLegacy(
            @PathVariable Long id,
            @RequestBody(required = false) FeatureStartReq req,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required");
        }

        String tier = (req == null ? "standard" : req.tierOrDefault());
        int days    = (req == null ? 7 : req.daysOrDefault());

        // ⏱️ Add promotion
        service.startPaidFeature(id, principal.getUser(), tier, days);

        // ✅ Then fetch Pack from the repo again to return fresh state
        Pack pack = service.getPackById(id);

        return ResponseEntity.ok(FeaturedPackDto.from(pack));
    }

    @GetMapping("/featured")
    public ResponseEntity<PaginatedResponse<FeaturedPackDto>> featured(
            @RequestParam(defaultValue = "12") int limit,
            @RequestParam(defaultValue = "0") int page) {

        int cappedLimit = Math.max(1, Math.min(limit, 60));
        int pageIndex = Math.max(0, page);

        List<Pack> allPacks = service.getFeaturedPacks(cappedLimit * (pageIndex + 1));

        if (allPacks.isEmpty()) {
            return ResponseEntity.ok(new PaginatedResponse<>(List.of(), 0, pageIndex, cappedLimit));
        }

        int from = Math.min(pageIndex * cappedLimit, allPacks.size());
        int to = Math.min(from + cappedLimit, allPacks.size());
        List<Pack> paginated = allPacks.subList(from, to);

        List<FeaturedPackDto> dtos = paginated.stream()
                .map(FeaturedPackDto::from)   // ✅ corrected
                .toList();

        return ResponseEntity.ok(new PaginatedResponse<>(dtos, allPacks.size(), pageIndex, cappedLimit));
    }


    @GetMapping("/by-slug/{slug}")
    public PackDto getBySlug(@PathVariable String slug) {
        String normalized = SlugUtil.toSlug(slug);

        Pack pack = packRepository.findBySlug(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));

        if (!pack.isApproved() || pack.isRejected()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This pack is not available");
        }

        // --- Total duration across beats ---
        int totalDurationSec = 0;
        if (pack.getBeats() != null) {
            for (var b : pack.getBeats()) {
                Integer secs = b.getDurationInSeconds();
                if (secs == null || secs <= 0) {
                    try {
                        if (b.getAudioFilePath() != null && !b.getAudioFilePath().isBlank()) {
                            secs = AudioUtils.getDurationInSeconds(b.getAudioFilePath());
                        }
                    } catch (Exception ignore) {
                        secs = 0;
                    }
                }
                totalDurationSec += (secs != null ? secs : 0);
            }
        }

        int beatsCount = (pack.getBeats() == null) ? 0 : pack.getBeats().size();

        String coverUrl = firstNonBlank(
                pack.getAlbumCoverUrl(),
                toPublicUrl(pack.getCoverImagePath())
        );

        String ownerName = pack.getOwner() != null ? nullIfBlank(pack.getOwner().getDisplayName()) : null;
        Long ownerId = pack.getOwner() != null ? pack.getOwner().getId() : null;

        // ---- Flags ----
        Instant now = Instant.now();
        Instant newCutoff = now.minus(Duration.ofDays(14));
        boolean isNew = pack.getCreatedAt() != null && pack.getCreatedAt().isAfter(newCutoff);

        long plays = pack.getPlayCount();
        boolean isPopular = plays >= 50;

        double ageDays = (pack.getCreatedAt() == null)
                ? 9999.0
                : Math.max(0.01, Duration.between(pack.getCreatedAt(), now).toHours() / 24.0);
        double trendingScore = plays * Math.pow(0.5, ageDays / 3.0);
        boolean isTrending = trendingScore >= 1.0;

        // ✅ Featured based on promotion system
        boolean featured = pack.isFeatured() &&
                (pack.getFeaturedUntil() == null || pack.getFeaturedUntil().isAfter(now));

        List<Object[]> rows = purchaseRepository.getPackSalesAndEarnings(pack.getId());
        Object[] rawStats = (rows != null && !rows.isEmpty()) ? rows.get(0) : null;

        int sales = 0;
        BigDecimal earnings = BigDecimal.ZERO;

        if (rawStats != null && rawStats.length == 2) {
            Object rawSales = rawStats[0];
            Object rawEarnings = rawStats[1];

            if (rawSales instanceof Number n) {
                sales = n.intValue();
            }

            if (rawEarnings instanceof BigDecimal bd) {
                earnings = bd;
            } else if (rawEarnings instanceof Number n) {
                earnings = BigDecimal.valueOf(n.doubleValue());
            }
        }
        // ✅ Return updated PackDto
        return new PackDto(
                pack.getId(),
                pack.getSlug(),
                pack.getTitle(),
                pack.getDescription(),
                coverUrl,
                ownerName,                  // ownerName
                ownerId,                    // ownerId
                ownerName,                  // artistName (or displayName)
                pack.getDisplayPrice(),      // price
                totalDurationSec,
                beatsCount,
                plays,
                pack.getCreatedAt(),
                splitTags(pack.getTags()),
                featured,
                pack.getFeaturedFrom(),
                isNew,
                isPopular,
                isTrending,
                pack.getLikeCount(),
                false,                       // liked
                sales,
                earnings
        );
    }

    private static List<String> splitTags(String t) {
        if (t == null || t.isBlank()) return List.of();
        return Arrays.stream(t.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    // --- Helpers inside PackController ---

    /** Return the first string that is not null/blank */
    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** Return null instead of blank/empty string */
    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Convert relative stored path → public URL */
    private String toPublicUrl(String path) {
        if (path == null || path.isBlank()) return null;
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path; // already absolute
        }
        if (path.startsWith("/uploads/")) return path;
        String base = (webBase == null ? "/uploads" : webBase).replaceAll("/+$", "");
        String clean = path.replaceFirst("^/+", "");
        return base + "/" + clean;
    }

    @GetMapping("/{id}/contents")
    public List<Map<String, Object>> getPackContents(@PathVariable Long id) {
        Pack pack = packRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));

        final String packCover = toPublicUrl(pack.getCoverImagePath());

        return pack.getBeats().stream()
                .map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", b.getId());
                    m.put("title", b.getTitle());
                    m.put("artistName", b.getArtist());
                    m.put("durationInSeconds", b.getDurationInSeconds());
                    m.put("bpm", b.getBpm());
                    m.put("coverUrl", packCover);

                    m.put("previewUrl", beatPreviewUrl(b));
                    return m;
                })
                .toList();
    }

    private String beatPreviewUrl(Beat b) {
        if (b == null) return null;
        String preview = b.getPreviewAudioPath();
        if (preview != null && (preview.startsWith("http://") || preview.startsWith("https://"))) {
            String local = b.getAudioFilePath();
            if (local != null && !local.isBlank()) {
                preview = local;
            }
        }
        if (preview == null || preview.isBlank()) preview = b.getAudioFilePath();
        return toPublicUrl(preview);
    }


    @GetMapping("/by-slug/{slug}/contents")
    public List<Map<String, Object>> getPackContentsBySlug(@PathVariable String slug) {
        var pack = packRepository.findBySlug(SlugUtil.toSlug(slug))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));

        if (!pack.isApproved() || pack.isRejected()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This pack is not available");
        }

        return getPackContents(pack.getId()); // delegate to the method above
    }

    // === COMMENTS (PACKS) ===

    @GetMapping("/{id}/comments")
    public java.util.List<com.drilldex.drillbackend.pack.dto.PackCommentDto> getPackComments(
            @PathVariable Long id
    ) {
        Pack pack = packRepository.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Pack not found"));

        return packRepository.findComments(pack.getId())
                .stream()
                .map(com.drilldex.drillbackend.pack.dto.PackCommentDto::from)
                .toList();
    }

    @PostMapping("/{id}/comments")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public com.drilldex.drillbackend.pack.dto.PackCommentDto addPackComment(
            @PathVariable Long id,
            @RequestBody com.drilldex.drillbackend.pack.dto.CommentRequest body,
            @AuthenticationPrincipal com.drilldex.drillbackend.auth.CustomUserDetails principal
    ) {
        if (body == null || body.text() == null || body.text().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Text is required");
        }

        Pack pack = packRepository.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Pack not found"));

        var c = new com.drilldex.drillbackend.pack.PackComment();
        c.setPack(pack);
        c.setUser(principal.getUser());
        c.setText(body.text().trim());

        // persist via Pack
        pack.getComments().add(c);
        pack.setCommentCount(pack.getCommentCount() + 1);
        packRepository.save(pack);

        return com.drilldex.drillbackend.pack.dto.PackCommentDto.from(c);
    }

    @DeleteMapping("/{packId}/comments/{commentId}")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public org.springframework.http.ResponseEntity<Void> deletePackComment(
            @PathVariable Long packId,
            @PathVariable Long commentId,
            @AuthenticationPrincipal com.drilldex.drillbackend.auth.CustomUserDetails principal
    ) {
        Pack pack = packRepository.findById(packId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Pack not found"));

        var comment = packRepository.findComment(packId, commentId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Comment not found"));

        var user = principal.getUser();
        boolean isOwner = comment.getUser().getId().equals(user.getId());
        boolean isAdmin = user.getRole() == com.drilldex.drillbackend.user.Role.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Not allowed to delete this comment");
        }

        pack.getComments().removeIf(cc -> java.util.Objects.equals(cc.getId(), commentId));
        pack.setCommentCount(Math.max(0, pack.getCommentCount() - 1));
        packRepository.save(pack);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    /* Optional: edit + like/unlike to match your frontend helpers */

    @PatchMapping("/{packId}/comments/{commentId}")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public com.drilldex.drillbackend.pack.dto.PackCommentDto editPackComment(
            @PathVariable Long packId,
            @PathVariable Long commentId,
            @RequestBody com.drilldex.drillbackend.pack.dto.CommentRequest body,
            @AuthenticationPrincipal com.drilldex.drillbackend.auth.CustomUserDetails principal
    ) {
        if (body == null || body.text() == null || body.text().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Text is required");
        }
        var comment = packRepository.findComment(packId, commentId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Comment not found"));
        var user = principal.getUser();
        boolean isOwner = comment.getUser().getId().equals(user.getId());
        boolean isAdmin = user.getRole() == com.drilldex.drillbackend.user.Role.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Not allowed to edit this comment");
        }
        comment.setText(body.text().trim());
        // save via owning pack
        var pack = packRepository.findById(packId).orElseThrow();
        packRepository.save(pack);
        return com.drilldex.drillbackend.pack.dto.PackCommentDto.from(comment);
    }

    @PostMapping("/{packId}/comments/{commentId}/like")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public java.util.Map<String, Object> likePackComment(
            @PathVariable Long packId,
            @PathVariable Long commentId,
            @AuthenticationPrincipal com.drilldex.drillbackend.auth.CustomUserDetails principal
    ) {
        var comment = packRepository.findComment(packId, commentId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Comment not found"));
        comment.getLikedBy().add(principal.getUser());
        var pack = packRepository.findById(packId).orElseThrow();
        packRepository.save(pack);
        return java.util.Map.of("liked", true, "likeCount", comment.getLikeCount());
    }

    @PostMapping("/{packId}/comments/{commentId}/unlike")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public java.util.Map<String, Object> unlikePackComment(
            @PathVariable Long packId,
            @PathVariable Long commentId,
            @AuthenticationPrincipal com.drilldex.drillbackend.auth.CustomUserDetails principal
    ) {
        var comment = packRepository.findComment(packId, commentId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Comment not found"));
        comment.getLikedBy().remove(principal.getUser());
        var pack = packRepository.findById(packId).orElseThrow();
        packRepository.save(pack);
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

        Page<Pack> resultPage;
        int totalCount;

        if (q == null || q.trim().isBlank()) {
            resultPage = repo.listRecentPaginated(pageable);
            totalCount = (int) repo.countAllApproved();
        } else {
            String qNormalized = q.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
            resultPage = repo.searchFlexiblePaginated(qNormalized, pageable);
            totalCount = (int) resultPage.getTotalElements();
        }

        List<Pack> rows = resultPage.getContent();
        Long userId = (principal != null) ? principal.getUser().getId() : null;
        List<Long> ids = rows.stream().map(Pack::getId).toList();

        Set<Long> likedIds = (userId != null && !ids.isEmpty())
                ? new HashSet<>(repo.findLikedPackIds(userId, ids))
                : Set.of();

        List<SearchCardDto> out = rows.stream().map(p -> {
            String rawCover = (p.getAlbumCoverUrl() != null && !p.getAlbumCoverUrl().isBlank())
                    ? p.getAlbumCoverUrl()
                    : p.getCoverImagePath();
            String coverUrl = toPublicUrl(rawCover);

            Long ownerId = (p.getOwner() != null) ? p.getOwner().getId() : 0;
            String ownerName = (p.getOwner() != null) ? displayNameOf(p.getOwner()) : "Unknown";

            // ✅ Use stored duration
            int beatsCount = (p.getBeats() != null) ? p.getBeats().size() : 0;
            int durationSec = (p.getBeats() == null) ? 0 :
                    p.getBeats().stream()
                            .mapToInt(Beat::getDurationInSeconds)
                            .sum();

            // Determine board statuses
            int largeLimit = 200;
            boolean featured = packService.getFeaturedPacksByOwner(ownerId, largeLimit)
                    .stream().anyMatch(fp -> fp.getId().equals(p.getId()));
            boolean popular = packService.getPopularPacksByOwner(ownerId, largeLimit, 0)
                    .stream().anyMatch(pp -> pp.getId().equals(p.getId()));
            boolean trending = packService.getTrendingPacksByOwner(ownerId, largeLimit, 0)
                    .stream().anyMatch(tp -> tp.getId().equals(p.getId()));
            boolean isNew = packService.getNewPacksByOwner(ownerId, largeLimit, 0)
                    .stream().anyMatch(np -> np.getId().equals(p.getId()));

            return SearchCardDto.builder()
                    .id(p.getId())
                    .slug(p.getSlug())
                    .title(p.getTitle())
                    .coverUrl(coverUrl)
                    .price(p.getDisplayPrice())
                    .createdAt(p.getCreatedAt())
                    ._kind("PACK")
                    .artistName(ownerName)
                    .artistId(ownerId)
                    .ownerName(ownerName)
                    .tags(p.getTags())
                    .likeCount(p.getLikeCount())
                    .liked(likedIds.contains(p.getId()))
                    .genre(p.getGenre())
                    .bpm(null)
                    .durationSec(durationSec)
                    .beatsCount(beatsCount)
                    .bpmMin(null)
                    .bpmMax(null)
                    .location(null)
                    .featured(featured)
                    .featuredTier(p.getFeaturedTier())
                    .popular(popular)
                    .trending(trending)
                    .isNew(isNew)
                    .build();
        }).toList();

        return ResponseEntity.ok(new PaginatedResponse<>(out, totalCount, pg, lim));
    }

    private static String displayNameOf(com.drilldex.drillbackend.user.User u) {
        if (u == null) return null;
        var dn = u.getDisplayName();
        if (dn != null && !dn.isBlank()) return dn;
        var email = u.getEmail();
        return (email != null && !email.isBlank()) ? email : null;
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
    public java.util.Map<String, Object> isPackLiked(
            @PathVariable Long id,
            org.springframework.security.core.Authentication auth
    ) {
        if (!packRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found");
        }
        Long uid = currentUserId(auth);
        if (uid == null) return java.util.Map.of("liked", false);

        boolean liked = packRepository.findLikedPackIds(uid, java.util.List.of(id)).contains(id);
        return java.util.Map.of("liked", liked);
    }

    @PostMapping("/{id}/like")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> likePack(@PathVariable Long id, Authentication auth) {
        var pack = packRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));
        Long uid = currentUserId(auth);
        if (uid == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var user = userRepository.findById(uid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        // ensure relation exists on Pack entity: Set<User> upvotedBy
        if (pack.getUpvotedBy().add(user)) {
            pack.setLikeCount(Math.max(0, pack.getLikeCount()) + 1);
            packRepository.save(pack);
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/like")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> unlikePack(@PathVariable Long id, Authentication auth) {
        var pack = packRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));
        Long uid = currentUserId(auth);
        if (uid == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        // remove by id to avoid lazy loading equals issues
        boolean removed = pack.getUpvotedBy().removeIf(u -> u.getId().equals(uid));
        if (removed) {
            pack.setLikeCount(Math.max(0, pack.getLikeCount() - 1));
            packRepository.save(pack);
        }
        return ResponseEntity.ok().build();
    }



}
