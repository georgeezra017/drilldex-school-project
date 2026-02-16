package com.drilldex.drillbackend.beat;

import com.drilldex.drillbackend.auth.CustomUserDetails;
import com.drilldex.drillbackend.beat.dto.BeatLicenseDto;
import com.drilldex.drillbackend.beat.dto.UploadBeatMeta;
import com.drilldex.drillbackend.notification.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.drilldex.drillbackend.dto.SearchCardDto;
import com.drilldex.drillbackend.preview.PreviewGenerator;
import com.drilldex.drillbackend.promotions.Promotion;
import com.drilldex.drillbackend.promotions.PromotionService;
import com.drilldex.drillbackend.purchase.PurchaseRepository;
import com.drilldex.drillbackend.shared.PaginatedResponse;
import com.drilldex.drillbackend.user.Role;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.util.AudioUtils;
import com.drilldex.drillbackend.util.TagUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping("/api/beats")
@RequiredArgsConstructor
public class BeatController {



    @Value("${app.upload.root}")
    private String uploadRoot;

    private final BeatService beatService;
    private final BeatRepository beatRepository;
    private final BeatLicenseService beatLicenseService;
    private final com.drilldex.drillbackend.storage.StorageService storage;
    private final PreviewGenerator previewGenerator;
    private final BeatService service;
    private final BeatRepository repo;
    private final com.drilldex.drillbackend.user.CurrentUserService currentUserService;
    private final PromotionService promotionService;
    private final PurchaseRepository purchaseRepository;
    private final FollowerNotificationService followerNotificationService;




    // logical folders; StorageService decides where/how to persist (local storage)
    private static final String AUDIO_UPLOAD_DIR = "audio";
    private static final String COVER_UPLOAD_DIR = "covers";
    private static final String PREVIEW_UPLOAD_DIR = "previews";
    private static final String STEMS_UPLOAD_DIR = "stems";


    private final ExecutorService processingExecutor = Executors.newFixedThreadPool(8);
    private final ExecutorService previewExecutor = Executors.newFixedThreadPool(4);

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadBeat(
            @RequestPart("meta") UploadBeatMeta meta,
            @RequestPart("audio") MultipartFile audio,
            @RequestPart(value = "cover", required = false) MultipartFile cover,
            @RequestPart(value = "stems", required = false) MultipartFile stemsZip,
            Authentication authentication
    ) throws IOException {

        // ---- basic validations ----
        if (meta == null) return ResponseEntity.badRequest().body(Map.of("error", "Missing meta"));
        if (meta.title() == null || meta.title().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Title is required"));
        if (meta.bpm() == null || meta.bpm() < 30 || meta.bpm() > 300)
            return ResponseEntity.badRequest().body(Map.of("error", "BPM out of range"));
        if (meta.licenses() == null || meta.licenses().isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "At least one license required"));
        if (audio == null || audio.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Audio file is required"));

        if (looksLikeZip(audio)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "ZIP not allowed for single beat upload. Use the pack upload."
            ));
        }
        if (!isSupportedAudio(audio)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unsupported audio format. Please upload an MP3 or WAV."
            ));
        }

        var enabled = meta.licenses().stream().filter(l -> Boolean.TRUE.equals(l.enabled())).toList();
        if (enabled.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Enable at least one license"));
        for (var l : enabled) {
            try { LicenseType.valueOf(l.type().toUpperCase()); }
            catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown license type: " + l.type()));
            }
            if (l.price() == null || l.price().signum() <= 0)
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid price for " + l.type()));
        }

        boolean premiumOrExclusiveEnabled = enabled.stream()
                .map(l -> l.type().toUpperCase())
                .anyMatch(t -> t.equals("PREMIUM") || t.equals("EXCLUSIVE"));

        if (premiumOrExclusiveEnabled && (stemsZip == null || stemsZip.isEmpty())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Premium or Exclusive licenses require uploading a stems ZIP file."
            ));
        }

        var principal = (CustomUserDetails) authentication.getPrincipal();
        var user = principal.getUser();

        String plan = user.getPlan() != null ? user.getPlan() : "FREE";

        if ("FREE".equalsIgnoreCase(plan)) {
            long uploadedBeats = beatRepository.countByOwnerId(user.getId());
            if (uploadedBeats >= 3) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You’ve reached the free plan limit: 3 beats. Upgrade to upload more."));
            }
        }

        // ---- copy the servlet part to our own temp file ONCE ----
        Path tmpAudio = Files.createTempFile("beat-audio-", ".upload");
        long audioSize;
        try (InputStream in = audio.getInputStream()) {
            audioSize = Files.copy(in, tmpAudio, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException io) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Failed to process audio upload"));
        }

        // ---- compute duration (parallel) ----
        final int[] durationInSeconds = {0};
        final boolean[] durationNeedsFix = {false};
        var durationFuture = processingExecutor.submit(() -> {
            try {
                durationInSeconds[0] = AudioUtils.getDurationInSeconds(tmpAudio.toString());
            } catch (Exception e) {
                log.warn("⚠️ Could not detect audio duration for file {}: {}", tmpAudio, e.getMessage());
                durationNeedsFix[0] = true;
            }
        });

        // ---- preview generation (parallel) ----
        CompletableFuture<String> previewFuture = CompletableFuture.supplyAsync(() -> {
            Path previewTmp = null;
            try {
                previewTmp = Files.createTempFile("beat-preview-", ".mp3");
                previewGenerator.generatePreview(tmpAudio, previewTmp);
                try (InputStream pin = Files.newInputStream(previewTmp)) {
                    long psize = Files.size(previewTmp);
                    String base = (audio.getOriginalFilename() == null ? "beat"
                            : audio.getOriginalFilename().replaceAll("\\.[^.]+$", ""));
                    String safeName = base + "-preview.mp3";
                    return storage.save(pin, psize, safeName, PREVIEW_UPLOAD_DIR, "audio/mpeg");
                }
            } catch (Exception ex) {
                log.error("Preview generation failed", ex);
                throw new RuntimeException("Preview failed: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            } finally {
                try { if (previewTmp != null) Files.deleteIfExists(previewTmp); } catch (Exception ignore) {}
            }
        }, previewExecutor);

        // ---- master upload (sync) ----
        String audioStored;
        try (InputStream in = Files.newInputStream(tmpAudio)) {
            audioStored = storage.save(
                    in,
                    audioSize,
                    audio.getOriginalFilename(),
                    AUDIO_UPLOAD_DIR,
                    audio.getContentType()
            );
        }

        // ---- cover upload (sync) ----
        String coverStored = null;
        if (cover != null && !cover.isEmpty()) {
            coverStored = storage.save(cover, COVER_UPLOAD_DIR);
        }

        String stemsFolderPath = null;
        if (stemsZip != null && !stemsZip.isEmpty()) {
            try {
                String safeTitle = slugify(meta.title());
                Path tempStemsDir = Files.createTempDirectory("stems-" + safeTitle + "-");
                AudioUtils.extractAudioFromZip(stemsZip, tempStemsDir); // filters for .mp3/.wav

                // Upload contents of extracted stems folder to storage
                String stemsDirPath = STEMS_UPLOAD_DIR + "/" + safeTitle; // use named directory
                List<CompletableFuture<Void>> uploadTasks = new ArrayList<>();

                try (Stream<Path> files = Files.walk(tempStemsDir)) {
                    files.filter(Files::isRegularFile)
                            .filter(f -> {
                                String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                                try {
                                    long size = Files.size(f);

                                    // 🚫 Skip macOS junk and unwanted extensions
                                    if (isMacJunkName(name) || !isAllowedStemFile(name)) {
                                        log.debug("Skipping junk/non-audio stem: {}", name);
                                        return false;
                                    }

                                    // 🚫 Skip empty or tiny stems (<8 KB)
                                    if (size < 8 * 1024) {
                                        log.debug("Skipping tiny stem file: {} ({} bytes)", name, size);
                                        return false;
                                    }

                                    // ✅ Passed all checks
                                    return true;

                                } catch (IOException e) {
                                    log.warn("Skipping unreadable stem file {}", f, e);
                                    return false;
                                }
                            })
                            .forEach(audioFile -> {
                                uploadTasks.add(CompletableFuture.runAsync(() -> {
                                    try (InputStream in = Files.newInputStream(audioFile)) {
                                        String relativeName = tempStemsDir.relativize(audioFile).toString();
                                        long fileSize = Files.size(audioFile);
                                        String mime = detectMimeType(relativeName);
                                        storage.save(in, fileSize, relativeName, stemsDirPath, mime);
                                        log.info("Uploaded stem: {} ({} bytes)", relativeName, fileSize);
                                    } catch (Exception e) {
                                        log.error("Failed to upload stem file {}", audioFile, e);
                                    }
                                }, processingExecutor));
                            });
                }

                CompletableFuture.allOf(uploadTasks.toArray(new CompletableFuture[0])).join();

                stemsFolderPath = stemsDirPath; // store base path for stems folder
                log.info("✅ Uploaded stems for beat '{}' to storage folder: {}", meta.title(), stemsFolderPath);
            } catch (Exception e) {
                log.error("❌ Failed to process stems ZIP for beat '{}': {}", meta.title(), e.getMessage(), e);
            }
        }

        // ---- wait for background tasks ----
        try { durationFuture.get(); } catch (Exception ignore) {}

        String previewStored;
        try {
            previewStored = previewFuture.get();
        } catch (Exception ex) {
            try { Files.deleteIfExists(tmpAudio); } catch (Exception ignore) {}
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Failed to render watermarked preview", "hint", ex.getMessage()));
        }

        try { Files.deleteIfExists(tmpAudio); } catch (Exception ignore) {}

        // ---- build entity ----
        Beat beat = new Beat();
        beat.setTitle(meta.title().trim());
        beat.setArtist((user.getDisplayName() != null && !user.getDisplayName().isBlank())
                ? user.getDisplayName() : user.getEmail());
        beat.setBpm(meta.bpm());
        beat.setGenre((meta.genre() == null ? "" : meta.genre()).trim().replaceAll("-", " ").replaceAll("\\s+", " "));
        beat.setTags(TagUtils.normalizeTags(meta.tags() == null ? "" : meta.tags()));
        beat.setDurationInSeconds(durationInSeconds[0]);
        if (durationNeedsFix[0]) {
            log.info("Beat {} uploaded without detected duration — manual fix needed", meta.title());
        }

        beat.setAudioFilePath(audioStored);
        beat.setStemsFilePath(stemsFolderPath);
        if (previewStored != null) {
            try {
                beat.getClass().getMethod("setPreviewUrl", String.class).invoke(beat, previewStored);
            } catch (ReflectiveOperationException ignore) {
                try { beat.getClass().getMethod("setPreviewAudioPath", String.class).invoke(beat, previewStored); }
                catch (ReflectiveOperationException ignoredToo) {}
            }
        }
        beat.setCoverImagePath(coverStored);
        beat.setUploadedBy(user);
        beat.setOwner(user);
        beat.setApproved(false);
        beat.setRejected(false);
        beat.setOnSale(false);
        beat.setLikeCount(0);

        BigDecimal displayPrice = null;
        for (var l : enabled) {
            var bl = new BeatLicense();
            bl.setBeat(beat);
            bl.setType(LicenseType.valueOf(l.type().toUpperCase()));
            bl.setPrice(l.price());
            bl.setEnabled(true);
            beat.getLicenses().add(bl);
            displayPrice = (displayPrice == null || l.price().compareTo(displayPrice) < 0) ? l.price() : displayPrice;
        }
        beat.setPrice(displayPrice != null ? displayPrice : BigDecimal.ZERO);
        beat.setSlug(slugify(meta.title()) + "-" + UUID.randomUUID());

        Beat saved = beatService.saveBeat(beat);
        saved.setSlug(slugify(saved.getTitle()) + "-" + saved.getId());
        saved = beatService.saveBeat(saved);

        followerNotificationService.notifyFollowersOfNewBeat(user, saved);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "Beat uploaded successfully and is awaiting admin approval.",
                "beatId", saved.getId(),
                "slug", saved.getSlug(),
                "approved", false
        ));
    }

    /** Convert a title into a URL-safe base for slugs. */
    private String slugify(String s) {
        if (s == null || s.isBlank()) return "untitled";
        String base = s.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return base.isBlank() ? "untitled" : base;
    }

    /* ---------- helpers ---------- */
    private static boolean looksLikeZip(MultipartFile f) {
        String name = Optional.ofNullable(f.getOriginalFilename()).orElse("").toLowerCase();
        String ct   = Optional.ofNullable(f.getContentType()).orElse("");
        if (name.endsWith(".zip")) return true;
        if (ct.equalsIgnoreCase("application/zip") || ct.equalsIgnoreCase("application/x-zip-compressed")) return true;
        try (InputStream in = f.getInputStream()) {
            byte[] sig = in.readNBytes(4);
            return sig.length >= 2 && sig[0] == 'P' && sig[1] == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isSupportedAudio(MultipartFile f) {
        String name = Optional.ofNullable(f.getOriginalFilename()).orElse("").toLowerCase();
        String ct   = Optional.ofNullable(f.getContentType()).orElse("").toLowerCase();
        boolean byExt = name.endsWith(".mp3") || name.endsWith(".wav");
        boolean byCt  = ct.contains("audio/mpeg") || ct.contains("audio/wav") || ct.contains("audio/wave") || ct.contains("x-wav");
        return byExt || byCt;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBeat(@PathVariable Long id, Authentication authentication) {
        Beat beat = beatService.getBeatById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found"));

        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        User currentUser = userDetails.getUser();

        boolean isOwner = beat.getOwner().getId().equals(currentUser.getId());
        boolean isAdmin = currentUser.getRole().equals("ADMIN");

        if (!isOwner && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not allowed to delete this beat.");
        }

        try {
            beatService.deleteBeatById(id);
            return ResponseEntity.noContent().build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/download/{beatId}")
    public ResponseEntity<Resource> downloadBeat(@PathVariable Long beatId, HttpServletRequest request, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required to download full beat");
        }

        Beat beat = beatService.getBeatById(beatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found"));

        String stored = beat.getAudioFilePath();
        if (stored == null || stored.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio file not found");
        }

        // If HTTP URL, redirect client to it
        if (isHttpUrl(stored)) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, stored)
                    .build();
        }

        // Local key: stream from disk under uploadRoot
        Path audioPath = Paths.get(uploadRoot).resolve(stored).normalize();
        if (!Files.exists(audioPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio file not found");
        }

        try {
            Resource resource = new UrlResource(audioPath.toUri());
            String contentType = request.getServletContext().getMimeType(audioPath.toString());
            if (contentType == null) contentType = "application/octet-stream";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + audioPath.getFileName().toString() + "\"")
                    .body(resource);

        } catch (MalformedURLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error serving file", e);
        }
    }


    @PostMapping("/{id}/play")
    public ResponseEntity<?> incrementPlayCount(@PathVariable Long id) {
        Beat beat = beatRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found"));

        beat.setPlayCount(beat.getPlayCount() + 1);
        beatRepository.save(beat);

        return ResponseEntity.ok().build();
    }


    private static Object[] safeStats(Object[] result) {
        if (result == null || result.length < 2) return new Object[]{0L, BigDecimal.ZERO};

        Object count = result[0];
        Object sum = result[1];

        Long safeCount = (count instanceof Number n) ? n.longValue() : 0L;
        BigDecimal safeSum = (sum instanceof BigDecimal b) ? b : BigDecimal.ZERO;

        return new Object[]{safeCount, safeSum};
    }

    private BeatDto mapToDto(Beat beat) {
        // 1️⃣ Determine current user
        User tmp;
        try {
            tmp = currentUserService.getCurrentUserOrThrow();
        } catch (Exception ignored) {
            tmp = null;
        }
        final User current = tmp;

        // 2️⃣ Determine if current user liked this beat
        boolean liked = false;
        if (current != null && beat.getUpvotedBy() != null) {
            liked = beat.getUpvotedBy()
                    .stream()
                    .anyMatch(u -> u.getId().equals(current.getId()));
        }

        // 3️⃣ Owner ID
        Long ownerId = (beat.getOwner() != null) ? beat.getOwner().getId() : null;

        // 4️⃣ Sales and earnings
        Object[] rawStats = purchaseRepository.getBeatSalesAndEarnings(beat.getId());
        Object[] stats = safeStats(rawStats);
        int sales = ((Number) stats[0]).intValue();
        BigDecimal earnings = (BigDecimal) stats[1];

        // 5️⃣ Build DTO
        return new BeatDto(
                beat.getId(),
                beat.getSlug(),
                beat.getTitle(),
                beat.getOwner() != null ? beat.getOwner().getDisplayName() : null, // ownerName
                ownerId, // ownerId
                null, // audioUrl (optional)
                beat.getPlayCount(),
                beat.getDurationInSeconds(),
                beat.getGenre(),
                beat.getTags(),
                beat.getPrice(),
                beat.getCoverImagePath(),
                beat.getLikeCount(),
                Objects.requireNonNullElse(beat.getCommentCount(), 0),
                promotionService.isCurrentlyFeatured(Promotion.TargetType.BEAT, beat.getId()),
                beat.getFeaturedAt(),
                toPublicUrl(beat.getCoverImagePath()),
                null, // previewUrl
                beat.getBpm(),
                beat.getCreatedAt(),
                liked,
                sales,
                earnings
        );
    }

    private static String toPublicUrl(String path) {
        if (path == null || path.isBlank()) return null;
        String p = path.toLowerCase();
        if (p.startsWith("http://") || p.startsWith("https://")) return path; // remote URL
        return "/uploads/" + path; // local static mapping
    }

    @GetMapping("/test-dto/{id}")
    public ResponseEntity<BeatDto> testMapToDto(@PathVariable Long id) {
        Beat beat = beatRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found"));

        return ResponseEntity.ok(mapToDto(beat));
    }
    @PostMapping("/{id}/like")
    public ResponseEntity<BeatDto> likeBeat(@PathVariable Long id) {
        return ResponseEntity.ok(beatService.likeBeat(id));
    }

    @DeleteMapping("/{id}/like")
    public ResponseEntity<BeatDto> unlikeBeat(@PathVariable Long id) {
        return ResponseEntity.ok(beatService.unlikeBeat(id));
    }

// src/main/java/.../BeatController.java

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

        Page<Beat> resultPage;
        int totalCount;

        if (q == null || q.trim().isBlank()) {
            resultPage = repo.listRecentPaginated(pageable);
            totalCount = (int) repo.countAllApproved();
        } else {
            String qNormalized = q.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
            resultPage = repo.searchFlexiblePaginated(qNormalized, pageable);
            totalCount = (int) resultPage.getTotalElements();
        }

        List<Beat> rows = resultPage.getContent();
        Long userId = (principal != null) ? principal.getUser().getId() : null;
        List<Long> ids = rows.stream().map(Beat::getId).toList();

        // Liked IDs
        Set<Long> likedIds = (userId != null && !ids.isEmpty())
                ? new HashSet<>(repo.findLikedBeatIds(userId, ids))
                : Set.of();

        Instant now = Instant.now();

        List<SearchCardDto> out = rows.stream().map(b -> {
            String rawCover = (b.getAlbumCoverUrl() != null && !b.getAlbumCoverUrl().isBlank())
                    ? b.getAlbumCoverUrl()
                    : b.getCoverImagePath();
            String coverUrl = toPublicUrl(rawCover);
            String artistName = (b.getArtist() != null && !b.getArtist().isBlank())
                    ? b.getArtist()
                    : displayNameOf(b.getOwner());

            Long ownerId = (b.getOwner() != null) ? b.getOwner().getId() : 0;

            // badges per beat (like packs)
            boolean featured = beatService.getFeaturedBeatsByOwner(ownerId, 200)
                    .stream().anyMatch(fb -> fb.getId().equals(b.getId()));
            boolean popular  = beatService.getPopularBeatsByOwner(ownerId, 200)
                    .stream().anyMatch(pb -> pb.getId().equals(b.getId()));
            boolean trending = beatService.getTrendingBeatsByOwner(ownerId, 200)
                    .stream().anyMatch(tb -> tb.getId().equals(b.getId()));
            boolean isNew    = beatService.getNewBeatsByOwner(ownerId, 200)
                    .stream().anyMatch(nb -> nb.getId().equals(b.getId()));

            return SearchCardDto.builder()
                    .id(b.getId())
                    .slug(b.getSlug())
                    .title(b.getTitle())
                    .coverUrl(coverUrl)
                    .price(b.getPrice())
                    .createdAt(b.getCreatedAt())
                    ._kind("BEAT")
                    .artistName(artistName)
                    .artistId(ownerId)
                    .tags(b.getTags())
                    .likeCount(b.getLikeCount())
                    .liked(likedIds.contains(b.getId()))
                    .bpm(b.getBpm())
                    .durationSec(b.getDurationInSeconds())
                    .genre(b.getGenre())
                    .featured(featured)
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

    @GetMapping("/filter")
    public ResponseEntity<List<BeatDto>> filterBeats(
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) Integer bpmMin,
            @RequestParam(required = false) Integer bpmMax
    ) {
        List<Beat> filteredBeats = beatRepository.findByFilters(genre, bpmMin, bpmMax);
        List<BeatDto> dtos = filteredBeats.stream().map(this::mapToDto).toList();
        return ResponseEntity.ok(dtos);
    }


    @GetMapping("/trending")
    public ResponseEntity<PaginatedResponse<BeatDto>> trending(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        // 1) Fetch paginated global trending beats
        Page<Beat> beatPage = beatService.getGlobalTrendingBeatsPage(page, limit);
        int totalCount = (int) beatPage.getTotalElements();

        List<Beat> beats = beatPage.getContent();
        List<Long> beatIds = beats.stream().map(Beat::getId).toList();

        // 2) Current user (optional)
        User current = principal != null ? principal.getUser() : null;

        // 3) Liked beats
        Set<Long> likedIds = Collections.emptySet();
        if (current != null && !beatIds.isEmpty()) {
            likedIds = new HashSet<>(beatRepository.findLikedBeatIds(current.getId(), beatIds));
        }

        // 4) Active promotions (featured) only
        Map<Long, Promotion> promoMap = promotionService
                .getActivePromotions(Promotion.TargetType.BEAT, Instant.now(), beatIds.size())
                .stream()
                .collect(Collectors.toMap(Promotion::getTargetId, p -> p));

        final Set<Long> finalLikedSet = likedIds;

        // 5) Map to BeatDto WITHOUT sales/earnings
        List<BeatDto> out = beats.stream()
                .map(b -> {
                    Promotion promo = promoMap.get(b.getId());
                    boolean isFeatured = promo != null;
                    Instant featuredAt = isFeatured ? promo.getStartDate() : null;

                    Long ownerId = (b.getOwner() != null) ? b.getOwner().getId() : null;

                    return new BeatDto(
                            b.getId(),
                            b.getSlug(),
                            b.getTitle(),
                            b.getOwner() != null ? b.getOwner().getDisplayName() : null,
                            ownerId,
                            null, // audioUrl
                            b.getPlayCount(),
                            b.getDurationInSeconds(),
                            b.getGenre(),
                            b.getTags(),
                            b.getPrice(),
                            b.getCoverImagePath(),
                            b.getLikeCount(),
                            Objects.requireNonNullElse(b.getCommentCount(), 0),
                            isFeatured,
                            featuredAt,
                            toPublicUrl(b.getCoverImagePath()),
                            null, // previewUrl
                            b.getBpm(),
                            b.getCreatedAt(),
                            finalLikedSet.contains(b.getId()),
                            0,    // sales removed
                            null  // earnings removed
                    );
                })
                .toList();

        return ResponseEntity.ok(new PaginatedResponse<>(out, totalCount, page, limit));
    }

    @PostMapping("/{id}/feature")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BeatDto> feature(@PathVariable Long id) {
        return ResponseEntity.ok(BeatMapper.mapToDto(beatService.featureBeat(id)));
    }



    @DeleteMapping("/{id}/feature")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BeatDto> unfeature(@PathVariable Long id) {
        return ResponseEntity.ok(BeatMapper.mapToDto(beatService.unfeatureBeat(id)));
    }

    @GetMapping("/new")
    public ResponseEntity<PaginatedResponse<BeatDto>> getNew(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication
    ) {
        Page<Beat> beatPage = beatService.getNewBeats(page, limit);
        int totalCount = beatService.getTotalNewBeats();

        // Get current user if available
        User current = null;
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails cud) {
            current = cud.getUser();
        }

        // Get liked IDs for current user
        Set<Long> likedIds = Collections.emptySet();
        if (current != null && !beatPage.isEmpty()) {
            List<Long> ids = beatPage.getContent().stream().map(Beat::getId).toList();
            likedIds = new HashSet<>(beatRepository.findLikedBeatIds(current.getId(), ids));
        }

        final Set<Long> likedSet = likedIds;

        // Map to DTOs with ownerId
        List<BeatDto> out = beatPage.getContent().stream()
                .map(b -> {
                    Long ownerId = (b.getOwner() != null) ? b.getOwner().getId() : null;
                    return new BeatDto(
                            b.getId(),
                            b.getSlug(),
                            b.getTitle(),
                            b.getOwner() != null ? b.getOwner().getDisplayName() : null, // ownerName
                            ownerId, // <-- added
                            null, // previewUrl
                            b.getPlayCount(),
                            b.getDurationInSeconds(),
                            b.getGenre(),
                            b.getTags(),
                            b.getPrice(),
                            b.getCoverImagePath(),
                            b.getLikeCount(),
                            Objects.requireNonNullElse(b.getCommentCount(), 0),
                            false,    // isFeatured
                            null,     // featuredAt
                            toPublicUrl(b.getCoverImagePath()),
                            null,     // audioUrl
                            b.getBpm(),
                            b.getCreatedAt(),
                            likedSet.contains(b.getId()),
                            0,       // sales
                            null     // earnings
                    );
                })
                .toList();

        return ResponseEntity.ok(new PaginatedResponse<>(out, totalCount, page, limit));
    }

    @GetMapping("/popular")
    public ResponseEntity<PaginatedResponse<BeatDto>> getPopular(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication
    ) {
        Page<Beat> beatPage = beatService.getGlobalPopularBeatsPage(page, limit);

        User current = null;
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails cud) {
            current = cud.getUser();
        }

        Set<Long> likedIds = Collections.emptySet();
        if (current != null && !beatPage.isEmpty()) {
            List<Long> ids = beatPage.stream().map(Beat::getId).toList();
            likedIds = new HashSet<>(beatRepository.findLikedBeatIds(current.getId(), ids));
        }

        Map<Long, Promotion> promoMap = promotionService
                .getActivePromotions(Promotion.TargetType.BEAT, Instant.now(), beatPage.getContent().size())
                .stream()
                .collect(Collectors.toMap(Promotion::getTargetId, p -> p));

        final Set<Long> likedSet = likedIds;
        List<BeatDto> out = beatPage.getContent().stream()
                .map(b -> {
                    Promotion promo = promoMap.get(b.getId());
                    boolean isFeatured = promo != null;
                    Instant featuredAt = promo != null ? promo.getStartDate() : null;

                    Long ownerId = (b.getOwner() != null) ? b.getOwner().getId() : null;


                    return new BeatDto(
                            b.getId(),
                            b.getSlug(),
                            b.getTitle(),
                            b.getOwner() != null ? b.getOwner().getDisplayName() : null,
                            ownerId,
                            null,
                            b.getPlayCount(),
                            b.getDurationInSeconds(),
                            b.getGenre(),
                            b.getTags(),
                            b.getPrice(),
                            b.getCoverImagePath(),
                            b.getLikeCount(),
                            Objects.requireNonNullElse(b.getCommentCount(), 0),
                            isFeatured,
                            featuredAt,
                            toPublicUrl(b.getCoverImagePath()),
                            null,
                            b.getBpm(),
                            b.getCreatedAt(),
                            likedSet.contains(b.getId()),
                            0,
                            null
                    );
                })
                .toList();

        return ResponseEntity.ok(new PaginatedResponse<>(out, (int) beatPage.getTotalElements(), page, limit));
    }

    @GetMapping("/{id}/licenses")
    public ResponseEntity<List<BeatLicenseDto>> getBeatLicenses(@PathVariable Long id) {
        var licenses = beatLicenseService.getEnabledLicenses(id);
        return ResponseEntity.ok(licenses);
    }

    @GetMapping("/approved")
    public ResponseEntity<PaginatedResponse<BeatDto>> getApprovedBeats(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        // 1) Calculate offset
        int offset = page * limit;

        // 2) Get all approved beats
        List<Beat> allApproved = beatService.getApprovedBeats();
        int totalCount = allApproved.size(); // ← total for pagination
        List<Beat> paginated = allApproved.stream()
                .skip(offset)
                .limit(limit)
                .toList();

        List<Long> beatIds = paginated.stream().map(Beat::getId).toList();

        // 3) Get current user
        User current = principal != null ? principal.getUser() : null;

        // 4) Get liked beat IDs
        Set<Long> likedIds = Collections.emptySet();
        if (current != null && !beatIds.isEmpty()) {
            likedIds = new HashSet<>(beatRepository.findLikedBeatIds(current.getId(), beatIds));
        }

        // 5) Get active promotions
        Map<Long, Promotion> promoMap = promotionService
                .getActivePromotions(Promotion.TargetType.BEAT, Instant.now(), beatIds.size())
                .stream()
                .collect(Collectors.toMap(Promotion::getTargetId, p -> p));

        // 6) Get sales and earnings
        Map<Long, Object[]> salesAndEarnings = paginated.stream().collect(Collectors.toMap(
                Beat::getId,
                beat -> {
                    Object[] result = purchaseRepository.getBeatSalesAndEarnings(beat.getId());
                    return result != null ? result : new Object[]{0L, BigDecimal.ZERO};
                }
        ));

        // 7) Map to BeatDto
        Set<Long> finalLikedIds = likedIds;
        List<BeatDto> dtos = paginated.stream()
                .map(b -> {
                    Promotion promo = promoMap.get(b.getId());
                    boolean isFeatured = promo != null;
                    Instant featuredAt = promo != null ? promo.getStartDate() : null;

                    Object[] stats = salesAndEarnings.getOrDefault(b.getId(), new Object[]{0L, BigDecimal.ZERO});

                    int sales = 0;
                    BigDecimal earnings = BigDecimal.ZERO;

                    if (stats.length > 0 && stats[0] instanceof Number n1) {
                        sales = n1.intValue();
                    }

                    if (stats.length > 1) {
                        if (stats[1] instanceof BigDecimal bd) {
                            earnings = bd;
                        } else if (stats[1] instanceof Number n2) {
                            earnings = BigDecimal.valueOf(n2.doubleValue());
                        }
                    }

                    String previewUrl = "/api/beats/" + b.getId() + "/preview-url";
                    boolean liked = finalLikedIds.contains(b.getId());

                    Long ownerId = (b.getOwner() != null) ? b.getOwner().getId() : null;


                    return new BeatDto(
                            b.getId(),
                            b.getSlug(),
                            b.getTitle(),
                            b.getOwner() != null ? b.getOwner().getDisplayName() : null,
                            ownerId,
                            previewUrl,
                            b.getPlayCount(),
                            b.getDurationInSeconds(),
                            b.getGenre(),
                            b.getTags(),
                            b.getPrice(),
                            b.getCoverImagePath(),
                            b.getLikeCount(),
                            Objects.requireNonNullElse(b.getCommentCount(), 0),
                            isFeatured,
                            featuredAt,
                            toPublicUrl(b.getCoverImagePath()),
                            null, // audioUrl
                            b.getBpm(),
                            b.getCreatedAt(),
                            liked,
                            sales,
                            earnings
                    );
                })
                .toList();

        return ResponseEntity.ok(new PaginatedResponse<>(dtos, totalCount, page, limit));
    }

    @GetMapping("/featured")
    public ResponseEntity<PaginatedResponse<BeatDto>> featured(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        Instant now = Instant.now();

        // 1) Get all active promotions for BEATs
        List<Promotion> allPromos = promotionService.getActivePromotions(
                Promotion.TargetType.BEAT, now, Integer.MAX_VALUE
        );
        int totalCount = allPromos.size();

        // 2) Apply page-based pagination manually
        int offset = page * limit;
        List<Promotion> promos = allPromos.stream()
                .skip(offset)
                .limit(limit)
                .toList();

        List<Long> beatIds = promos.stream().map(Promotion::getTargetId).toList();

        // 3) Fetch beats by those IDs
        List<Beat> beats = beatRepository.findAllById(beatIds);

        // 4) Map promotions by beat ID
        Map<Long, Promotion> promoMap = promos.stream()
                .collect(Collectors.toMap(
                        Promotion::getTargetId,
                        Function.identity(),
                        (existing, replacement) -> {
                            if (existing == null) return replacement;
                            if (replacement == null) return existing;

                            Instant existingStart = existing.getStartDate();
                            Instant replacementStart = replacement.getStartDate();

                            // Handle null startDates safely
                            if (existingStart == null && replacementStart == null) return existing;
                            if (existingStart == null) return replacement;
                            if (replacementStart == null) return existing;

                            // Keep the one with later startDate
                            return existingStart.isAfter(replacementStart) ? existing : replacement;
                        }
                ));

        // 5) Sort beats by promo start date descending
        beats.sort(Comparator.comparing(
                b -> promoMap.getOrDefault(b.getId(), new Promotion()).getStartDate(),
                Comparator.nullsLast(Comparator.reverseOrder()))
        );

        // 6) Determine liked beats
        User current = principal != null ? principal.getUser() : null;
        Set<Long> likedIds = Collections.emptySet();
        if (current != null && !beatIds.isEmpty()) {
            likedIds = new HashSet<>(beatRepository.findLikedBeatIds(current.getId(), beatIds));
        }

        // 7) Get sales and earnings
        Map<Long, Object[]> salesAndEarnings = beats.stream()
                .collect(Collectors.toMap(
                        Beat::getId,
                        beat -> {
                            Object[] raw = purchaseRepository.getBeatSalesAndEarnings(beat.getId());

                            long sales = 0L;
                            BigDecimal earnings = BigDecimal.ZERO;

                            if (raw != null && raw.length >= 2) {
                                if (raw[0] instanceof Number n1) sales = n1.longValue();
                                if (raw[1] instanceof BigDecimal bd) earnings = bd;
                                else if (raw[1] instanceof Number n2) earnings = BigDecimal.valueOf(n2.doubleValue());
                            }

                            return new Object[]{sales, earnings};
                        }
                ));

        // 8) Map to BeatDto
        Set<Long> finalLikedIds = likedIds;
        List<BeatDto> out = beats.stream()
                .map(b -> {
                    Promotion promo = promoMap.get(b.getId());
                    boolean isFeatured = promo != null && promo.isActive();
                    Instant featuredAt = promo != null ? promo.getStartDate() : null;

                    Object[] stats = salesAndEarnings.getOrDefault(b.getId(), new Object[]{0L, BigDecimal.ZERO});
                    int sales = ((Number) stats[0]).intValue();
                    BigDecimal earnings = (BigDecimal) stats[1];

                    String previewUrl = "/api/beats/" + b.getId() + "/preview-url";
                    boolean liked = finalLikedIds.contains(b.getId());

                    Long ownerId = (b.getOwner() != null) ? b.getOwner().getId() : null;


                    return new BeatDto(
                            b.getId(),
                            b.getSlug(),
                            b.getTitle(),
                            b.getOwner() != null ? b.getOwner().getDisplayName() : null,
                            ownerId,
                            previewUrl,
                            b.getPlayCount(),
                            b.getDurationInSeconds(),
                            b.getGenre(),
                            b.getTags(),
                            b.getPrice(),
                            b.getCoverImagePath(),
                            b.getLikeCount(),
                            Objects.requireNonNullElse(b.getCommentCount(), 0),
                            isFeatured,
                            featuredAt,
                            toPublicUrl(b.getCoverImagePath()),
                            null, // audioUrl
                            b.getBpm(),
                            b.getCreatedAt(),
                            liked,
                            sales,
                            earnings
                    );
                })
                .toList();

        return ResponseEntity.ok(new PaginatedResponse<>(out, totalCount, page, limit));
    }

    // ---- helpers ----
    private static boolean isHttpUrl(String s) {
        String x = s == null ? "" : s.toLowerCase();
        return x.startsWith("http://") || x.startsWith("https://");
    }

//    @GetMapping("/styles/{slug}")
//    public ResponseEntity<List<BeatDto>> getByStyleGenre(
//            @PathVariable String slug,
//            @RequestParam(required = false) Integer bpmMin,
//            @RequestParam(required = false) Integer bpmMax,
//            @RequestParam(defaultValue = "60") int limit,
//            @RequestParam(defaultValue = "0") int page,
//            @AuthenticationPrincipal CustomUserDetails principal
//    ) {
//        var rows = beatService.getBeatsForStyle(slug, bpmMin, bpmMax, page, limit);
//
//        // Determine current user
//        User current = (principal != null) ? principal.getUser() : null;
//
//        // Which beats are liked by this user?
//        Set<Long> likedIds = Collections.emptySet();
//        if (current != null && !rows.isEmpty()) {
//            List<Long> ids = rows.stream().map(Beat::getId).toList();
//            likedIds = new HashSet<>(beatRepository.findLikedBeatIds(current.getId(), ids));
//        }
//
//        // Get active promotions
//        Map<Long, Promotion> promoMap = promotionService
//                .getActivePromotions(Promotion.TargetType.BEAT, Instant.now(), rows.size())
//                .stream()
//                .collect(Collectors.toMap(Promotion::getTargetId, p -> p));
//
//        // Get sales and earnings
//        Map<Long, Object[]> salesMap = rows.stream().collect(Collectors.toMap(
//                Beat::getId,
//                beat -> {
//                    Object[] result = purchaseRepository.getBeatSalesAndEarnings(beat.getId());
//                    return result != null ? result : new Object[]{0L, BigDecimal.ZERO};
//                }
//        ));
//
//        Set<Long> likedSet = likedIds;
//
//        var dtos = rows.stream()
//                .map(b -> {
//                    Promotion promo = promoMap.get(b.getId());
//                    boolean isFeatured = promo != null;
//                    Instant featuredAt = promo != null ? promo.getStartDate() : null;
//
//                    Object[] stats = salesMap.getOrDefault(b.getId(), new Object[]{0L, BigDecimal.ZERO});
//                    int sales = 0;
//                    BigDecimal earnings = BigDecimal.ZERO;
//
//                    if (stats.length >= 1 && stats[0] instanceof Number n) {
//                        sales = n.intValue();
//                    }
//
//                    if (stats.length >= 2 && stats[1] instanceof BigDecimal bd) {
//                        earnings = bd;
//                    }
//
//                    return new BeatDto(
//                            b.getId(),
//                            b.getSlug(),
//                            b.getTitle(),
//                            b.getOwner() != null ? b.getOwner().getDisplayName() : null,
//                            null,                                // audioUrl
//                            b.getPlayCount(),
//                            b.getDurationInSeconds(),
//                            b.getGenre(),
//                            b.getTags(),
//                            b.getPrice(),
//                            b.getCoverImagePath(),
//                            b.getLikeCount(),
//                            Objects.requireNonNullElse(b.getCommentCount(), 0),
//                            isFeatured,
//                            featuredAt,
//                            toPublicUrl(b.getCoverImagePath()),  // coverUrl (public)
//                            null,                                // previewUrl
//                            b.getBpm(),
//                            b.getCreatedAt(),
//                            likedSet.contains(b.getId()),
//                            sales,
//                            earnings
//                    );
//                })
//                .toList();
//
//        return ResponseEntity.ok(dtos);
//    }



    @GetMapping("/styles/{slug}")
    public ResponseEntity<PaginatedResponse<BeatDto>> getByStyleGenre(
            @PathVariable String slug,
            @RequestParam(required = false) Integer bpmMin,
            @RequestParam(required = false) Integer bpmMax,
            @RequestParam(defaultValue = "60") int limit,
            @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        var rows = beatService.getBeatsForStyle(slug, bpmMin, bpmMax, page, limit);
        log.info("Fetched {} beats for style '{}'", rows.size(), slug);

        // Total count for pagination
        int totalCount = beatService.getTotalBeatsForStyle(slug, bpmMin, bpmMax);

        // Determine current user
        User current = (principal != null) ? principal.getUser() : null;
        Long currentUserId = current != null ? current.getId() : null;
        log.info("Current user ID: {}", currentUserId);

        // Resolve liked IDs in a way that keeps variable final
        Set<Long> likedIdsRaw;
        if (currentUserId != null && !rows.isEmpty()) {
            List<Long> beatIds = rows.stream().map(Beat::getId).toList();
            log.info("Checking liked beats for user {} on beat IDs: {}", currentUserId, beatIds);
            likedIdsRaw = new HashSet<>(beatRepository.findLikedBeatIds(currentUserId, beatIds));
            log.info("User {} liked beat IDs: {}", currentUserId, likedIdsRaw);
        } else {
            likedIdsRaw = Collections.emptySet();
        }
        final Set<Long> likedIds = likedIdsRaw;

        // Get active promotions
        Map<Long, Promotion> promoMap = promotionService
                .getActivePromotions(Promotion.TargetType.BEAT, Instant.now(), rows.size())
                .stream()
                .collect(Collectors.toMap(
                        Promotion::getTargetId,
                        Function.identity(),
                        (existing, replacement) -> {
                            Instant existingStart = existing.getStartDate();
                            Instant replacementStart = replacement.getStartDate();

                            if (existingStart == null && replacementStart == null) return existing;
                            if (existingStart == null) return replacement;
                            if (replacementStart == null) return existing;

                            // keep the one with later startDate
                            return existingStart.isAfter(replacementStart) ? existing : replacement;
                        }
                ));

        // Get sales and earnings
        Map<Long, Object[]> salesMap = rows.stream().collect(Collectors.toMap(
                Beat::getId,
                beat -> {
                    Object[] result = purchaseRepository.getBeatSalesAndEarnings(beat.getId());
                    return result != null ? result : new Object[]{0L, BigDecimal.ZERO};
                }
        ));

        var dtos = rows.stream()
                .map(b -> {
                    Promotion promo = promoMap.get(b.getId());
                    boolean isFeatured = promo != null;
                    Instant featuredAt = isFeatured ? promo.getStartDate() : null;

                    Object[] stats = salesMap.getOrDefault(b.getId(), new Object[]{0L, BigDecimal.ZERO});
                    int sales = 0;
                    BigDecimal earnings = BigDecimal.ZERO;

                    if (stats.length >= 1 && stats[0] instanceof Number n) {
                        sales = n.intValue();
                    }
                    if (stats.length >= 2 && stats[1] instanceof BigDecimal bd) {
                        earnings = bd;
                    }

                    Long ownerId = (b.getOwner() != null) ? b.getOwner().getId() : null;


                    return new BeatDto(
                            b.getId(),
                            b.getSlug(),
                            b.getTitle(),
                            b.getOwner() != null ? b.getOwner().getDisplayName() : null,
                            ownerId,
                            null,                                // audioUrl
                            b.getPlayCount(),
                            b.getDurationInSeconds(),
                            b.getGenre(),
                            b.getTags(),
                            b.getPrice(),
                            b.getCoverImagePath(),
                            b.getLikeCount(),
                            Objects.requireNonNullElse(b.getCommentCount(), 0),
                            isFeatured,
                            featuredAt,
                            toPublicUrl(b.getCoverImagePath()),  // coverUrl
                            null,                                // previewUrl
                            b.getBpm(),
                            b.getCreatedAt(),
                            likedIds.contains(b.getId()),
                            sales,
                            earnings
                    );
                })
                .toList();

        return ResponseEntity.ok(new PaginatedResponse<>(dtos, totalCount, page, limit));
    }

    // handle can be "123" or "artist-title"
    @GetMapping("/by-slug/{slug}")
    public ResponseEntity<BeatDto> getBeatBySlug(@PathVariable String slug) {
        return beatRepository.findBySlug(slug)
                .filter(beat -> beat.isApproved() && !beat.isRejected())
                .map(this::mapToDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // GET /api/beats/{id}/comments
    @GetMapping("/{id}/comments")
    public List<com.drilldex.drillbackend.beat.dto.BeatCommentDto> getBeatComments(@PathVariable Long id) {
        Beat beat = beatRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found"));

        return beatRepository.findComments(beat.getId())
                .stream()
                .map(com.drilldex.drillbackend.beat.dto.BeatCommentDto::from)
                .toList();
    }

    // POST /api/beats/{id}/comments
    @PostMapping("/{id}/comments")
    @PreAuthorize("isAuthenticated()")
    public com.drilldex.drillbackend.beat.dto.BeatCommentDto addBeatComment(
            @PathVariable Long id,
            @RequestBody com.drilldex.drillbackend.beat.dto.CommentRequest body,
            @AuthenticationPrincipal com.drilldex.drillbackend.auth.CustomUserDetails principal
    ) {
        if (body == null || body.text() == null || body.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text is required");
        }

        Beat beat = beatRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found"));

        var c = new BeatComment();
        c.setBeat(beat);
        c.setUser(principal.getUser());
        c.setText(body.text().trim());

        // Cascade persist via Beat
        beat.getComments().add(c);
        beat.setCommentCount(beat.getCommentCount() + 1);
        beatRepository.save(beat);

        return com.drilldex.drillbackend.beat.dto.BeatCommentDto.from(c);
    }

    // DELETE /api/beats/{beatId}/comments/{commentId}
    @DeleteMapping("/{beatId}/comments/{commentId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteBeatComment(
            @PathVariable Long beatId,
            @PathVariable Long commentId,
            @AuthenticationPrincipal com.drilldex.drillbackend.auth.CustomUserDetails principal
    ) {
        Beat beat = beatRepository.findById(beatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found"));

        var comment = beatRepository.findComment(beatId, commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));

        var user = principal.getUser();
        boolean isOwner = comment.getUser().getId().equals(user.getId());
        boolean isAdmin = user.getRole() == Role.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to delete this comment");
        }

        // orphanRemoval=true takes care of deleting the row
        beat.getComments().removeIf(c -> Objects.equals(c.getId(), commentId));
        beat.setCommentCount(Math.max(0, beat.getCommentCount() - 1));
        beatRepository.save(beat);

        return ResponseEntity.noContent().build();
    }

    private User currentOrNull() {
        try { return currentUserService.getCurrentUserOrThrow(); }
        catch (Exception ignored) { return null; } // guest
    }

    private BeatDto mapForCurrent(Beat b) {
        return BeatMapper.mapToDto(b, currentOrNull());
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
    public java.util.Map<String, Object> isBeatLiked(
            @PathVariable Long id,
            org.springframework.security.core.Authentication auth
    ) {
        if (!beatRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found");
        }
        Long uid = currentUserId(auth);
        if (uid == null) return java.util.Map.of("liked", false);

        boolean liked = beatRepository.findLikedBeatIds(uid, java.util.List.of(id)).contains(id);
        return java.util.Map.of("liked", liked);
    }

    private static boolean isMacJunkName(String name) {
        if (name == null) return false;
        String n = name.replace('\\', '/').toLowerCase(Locale.ROOT);
        String base = n.substring(n.lastIndexOf('/') + 1);
        return n.startsWith("__macosx/") || base.startsWith("._") || base.equals(".ds_store");
    }

    private static boolean isAllowedStemFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".wav"); // 🔹 add more later (.flac, .m4a, .aiff, etc.)
    }

    private static String detectMimeType(String name) {
        if (name == null) return "application/octet-stream";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3"))  return "audio/mpeg";
        if (lower.endsWith(".wav"))  return "audio/wav";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".aiff")) return "audio/aiff";
        if (lower.endsWith(".m4a"))  return "audio/mp4";
        return "application/octet-stream";
    }
}
