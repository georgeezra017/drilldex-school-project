package com.drilldex.drillbackend.pack;

import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.beat.BeatRepository;
import com.drilldex.drillbackend.beat.LicenseType;
import com.drilldex.drillbackend.chat.ChatStorageService;
import com.drilldex.drillbackend.inbox.InboxMessageService;
import com.drilldex.drillbackend.pack.dto.PackDto;
import com.drilldex.drillbackend.pack.dto.PackUpdateRequest;
import com.drilldex.drillbackend.pack.dto.PackUploadMeta;
import com.drilldex.drillbackend.pack.mapper.PackMapper;
import com.drilldex.drillbackend.promotions.Promotion;
import com.drilldex.drillbackend.promotions.PromotionRepository;
import com.drilldex.drillbackend.shared.SlugUtil;
import com.drilldex.drillbackend.util.AudioUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.http.fileupload.FileUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;
import com.drilldex.drillbackend.preview.PreviewGenerator;
import com.drilldex.drillbackend.storage.StorageService;
import com.drilldex.drillbackend.user.Role;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.util.TagUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.util.stream.Stream;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class PackService {
    private final PackRepository packRepo;
    private final BeatRepository beatRepo;
    private final StorageService storage;
    private final PreviewGenerator previewGenerator;
    private final PackRepository repo;
    private final PackRepository packRepository;
    private final InboxMessageService inboxMessageService;
    private final ChatStorageService chatStorageService;
    private final PromotionRepository promotionRepository;

    // Windows
    private static final int NEW_WINDOW_DAYS           = 60;
    private static final int POPULAR_WINDOW_DAYS       = 60;   // evaluate in last 30d
    private static final int POPULAR_MAX_AGE_DAYS      = 90;   // never call “popular” if older than 90d
    private static final int TRENDING_POOL_DAYS        = 21;
    private static final double TRENDING_HALFLIFE_DAYS = 2.5;  // faster decay

    // Popularity floors
    private static final long POPULAR_MIN_PLAYS  = 50;
    private static final int  POPULAR_MIN_LIKES  = 5;
    private static final long POPULAR_MIN_SCORE  = 80;   // plays + 3*likes

    // Trending floors
    private static final long TRENDING_MIN_PLAYS = 10;
    private static final int  TRENDING_MIN_LIKES = 2;


    // Pack-specific namespaces in storage
    private static final String PACKS_COVERS   = "packs/covers";
    private static final String PACKS_AUDIO    = "packs/audio";
    private static final String PACKS_PREVIEWS = "packs/previews";
    private static final String PACKS_STEMS = "packs/stems";

    private final ExecutorService processingExecutor = Executors.newFixedThreadPool(8);
    private final ExecutorService previewExecutor = Executors.newFixedThreadPool(4);

    @Transactional
    public Pack uploadPackMixed(
            User artist,
            PackUploadMeta meta,
            MultipartFile cover,
            List<MultipartFile> files,
            MultipartFile zip,
            MultipartFile stemsZip
    ) throws IOException {
        if (meta == null || meta.name() == null || meta.name().isBlank())
            throw new IllegalArgumentException("Pack name is required");

        // Build Pack
        Pack p = new Pack();
        p.setTitle(meta.name().trim());
        p.setDescription(meta.description() == null ? "" : meta.description().trim());
        p.setOwner(artist);
        p.setApproved(false);
        p.setRejected(false);
        p.setTags(TagUtils.normalizeTags(meta.tags()));
        p.setSlug(ensureUniqueSlug(SlugUtil.toSlug(meta.name())));

        // Licenses
        List<PackLicense> licenseEntities = new ArrayList<>();
        if (meta.licenses() != null) {
            for (PackUploadMeta.LicenseLine l : meta.licenses()) {
                if (l == null || !Boolean.TRUE.equals(l.enabled())) continue;
                var price = l.price();
                if (price == null || price.signum() < 0) continue;

                PackLicense pl = new PackLicense();
                pl.setPack(p);
                pl.setType(LicenseType.valueOf(l.type().toUpperCase()));
                pl.setEnabled(true);
                pl.setPrice(price);
                licenseEntities.add(pl);
            }
        }
        p.setLicenses(licenseEntities);
        BigDecimal displayPrice = licenseEntities.stream()
                .filter(PackLicense::isEnabled)
                .map(PackLicense::getPrice)
                .min(BigDecimal::compareTo)
                .orElseGet(() -> meta.price() != null ? meta.price() : BigDecimal.ZERO);
        p.setPrice(displayPrice.setScale(2, RoundingMode.HALF_UP));

        // Cover (optional)
        if (cover != null && !cover.isEmpty()) {
            log.info("Saving pack cover...");
            String coverSaved = storage.save(cover, PACKS_COVERS);
            p.setCoverImagePath(coverSaved);
            log.info("Cover saved: " + coverSaved);
        }

        // Start with any included beats
        List<Beat> included = new ArrayList<>();
        if (meta.beatIds() != null && !meta.beatIds().isEmpty()) {
            included.addAll(beatRepo.findAllById(meta.beatIds()));
        }

        Set<String> seenBases = new HashSet<>();
        included.forEach(b -> seenBases.add(safeBase(b.getTitle()).toLowerCase(Locale.ROOT)));

        // Process individual files in parallel
        if (files != null && !files.isEmpty()) {
            List<Callable<Beat>> tasks = new ArrayList<>();
            for (MultipartFile f : files) {
                if (f == null || f.isEmpty()) continue;
                tasks.add(() -> processFileToBeat(f, seenBases, artist));
            }

            List<Beat> newBeats = new ArrayList<>();
            try {
                List<Future<Beat>> futures = previewExecutor.invokeAll(tasks);
                for (Future<Beat> f : futures) {
                    try {
                        Beat b = f.get();
                        if (b != null) newBeats.add(b);
                    } catch (ExecutionException e) {
                        throw new RuntimeException("Failed to process file: " + e.getCause().getMessage(), e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupted status
                throw new RuntimeException("Beat processing was interrupted", e);
            }

            beatRepo.saveAll(newBeats);
            included.addAll(newBeats);
        }

        // ZIP file (folders supported)
        if (zip != null && !zip.isEmpty()) {
            log.info("Starting ZIP processing");
            included.addAll(processZipToBeats(zip, artist, seenBases));
        }

        if (stemsZip != null && !stemsZip.isEmpty()) {
            try {
                String slug = p.getSlug() != null ? p.getSlug() : SlugUtil.toSlug(meta.name());
                Path tempStemsDir = Files.createTempDirectory("pack-stems-" + slug + "-");
                AudioUtils.extractAudioFromZip(stemsZip, tempStemsDir); // extracts audio files

                String stemsDirPath = PACKS_STEMS + "/" + slug; // e.g., packs/stems/my-pack-123
                List<CompletableFuture<Void>> uploadTasks = new ArrayList<>();

                try (Stream<Path> walk = Files.walk(tempStemsDir)) {   // <-- renamed to avoid shadowing 'files' param
                    walk.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName() != null)
                            .filter(path -> {
                                String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
                                return n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".m4a");
                            })
                            .forEach(stemFile -> uploadTasks.add(
                                    CompletableFuture.runAsync(() -> {
                                        try (InputStream in = Files.newInputStream(stemFile)) {
                                            String relativeName = tempStemsDir.relativize(stemFile).toString();
                                            long fileSize = Files.size(stemFile);
                                            String nameLower = relativeName.toLowerCase(Locale.ROOT);

                                            String mime =
                                                    nameLower.endsWith(".wav") ? "audio/wav" :
                                                            nameLower.endsWith(".m4a") ? "audio/mp4" :   // common for m4a
                                                                    "audio/mpeg"; // mp3 default

                                            storage.save(in, fileSize, relativeName, stemsDirPath, mime);
                                        } catch (Exception e) {
                                            log.error("Failed to upload stem file {}", stemFile, e);
                                        }
                                    }, processingExecutor)
                            ));
                }

                CompletableFuture.allOf(uploadTasks.toArray(new CompletableFuture[0])).join();
                p.setStemsFilePath(stemsDirPath);
                log.info("✅ Uploaded stems for pack '{}' to storage folder: {}", meta.name(), stemsDirPath);

                try {
                    Files.walk(tempStemsDir)
                            .sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try { Files.deleteIfExists(path); } catch (Exception ignore) {}
                            });
                } catch (Exception ignore) {}
            } catch (Exception e) {
                log.error("❌ Failed to process stems ZIP for pack '{}': {}", meta.name(), e.getMessage(), e);
            }
        }

        if (included.isEmpty()) {
            throw new IllegalArgumentException("No valid audio files were ingested.");
        }

        p.setBeats(included);
        return packRepo.save(p);
    }

    private Beat processFileToBeat(MultipartFile f, Set<String> seenBases, User artist) throws Exception {
        String orig = Optional.ofNullable(f.getOriginalFilename()).orElse("file");
        if (isMacJunk(orig) || !isAudioLike(orig)) return null;

        String base = toBaseFile(orig);
        String baseKey = safeBase(base).toLowerCase(Locale.ROOT);
        synchronized (seenBases) {
            if (!seenBases.add(baseKey)) return null;
        }

        // ✅ Load into memory first (faster for <20MB files)
        byte[] fileBytes = f.getBytes();

        // ✅ Write once to temp for probing + preview
        Path tmp = Files.createTempFile("pack-file-", "-" + safeName(base));
        Files.write(tmp, fileBytes);

        int duration = probeDurationSeconds(tmp);

        String storedMaster;
        try (InputStream in = new ByteArrayInputStream(fileBytes)) {
            storedMaster = storage.save(in, fileBytes.length, base, PACKS_AUDIO, contentTypeFromName(base));
        }

        Beat b = createBeatFromUpload(storedMaster, base, artist, duration);

        String baseSlugBeat = SlugUtil.toSlug(b.getTitle());
        String uniqueSlug = ensureUniqueBeatSlug(baseSlugBeat);
        b.setSlug(uniqueSlug);
        b.setPartOfPack(true);
        if (!uniqueSlug.equals(baseSlugBeat)) {
            b.setTitle(b.getTitle() + " (" + uniqueSlug.substring(baseSlugBeat.length()) + ")");
        }

        // ✅ Async preview generation using in-place temp file
        previewExecutor.submit(() -> {
            try { ensurePreviewForBeat(tmp, base, b); } catch (Exception ignored) {}
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        });

        return b;
    }

    /** 🔹 Ensure slug uniqueness by appending -2, -3, ... */
    private String ensureUniqueSlug(String base) {
        if (base == null || base.isBlank()) base = "pack";
        String s = base;
        int n = 2;
        while (packRepo.existsBySlug(s)) {
            s = base + "-" + n++;
        }
        return s;
    }

    @Transactional
    public Pack updatePack(Long id, PackUpdateRequest req, User artist) throws IOException {
        Pack p = packRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Pack not found"));

        if (!p.getOwner().getId().equals(artist.getId()))
            throw new SecurityException("Not your pack");
        if (p.isApproved())
            throw new IllegalStateException("Approved packs cannot be edited");

        if (req.getTitle() != null)        p.setTitle(req.getTitle().trim());
        if (req.getDescription() != null)  p.setDescription(req.getDescription().trim());
        if (req.getPrice() != null)        p.setPrice(req.getPrice());
        if (req.getTags() != null)         p.setTags(TagUtils.normalizeTags(req.getTags()));

        if (req.getNewCover() != null && !req.getNewCover().isEmpty()) {
            if (p.getCoverImagePath() != null && !p.getCoverImagePath().isBlank()) {
                try { storage.delete(p.getCoverImagePath()); } catch (Exception ignore) {}
            }
            String newCover = storage.save(req.getNewCover(), PACKS_COVERS);
            p.setCoverImagePath(newCover);
        }

        if (req.getBeatIds() != null) {
            List<Beat> beats = beatRepo.findAllById(req.getBeatIds());
            p.setBeats(beats);
        }

        return packRepo.save(p);
    }

    public Page<Pack> getApprovedNotRejectedPacks(Pageable pageable) {
        return packRepo.findByApprovedTrueAndRejectedFalseOrderByCreatedAtDesc(pageable);
    }

    public int countApprovedNotRejectedPacks() {
        return (int) packRepo.countByApprovedTrueAndRejectedFalse();
    }

    public Page<Pack> getNewPacksPaginated(Pageable pageable) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(NEW_WINDOW_DAYS));
        return packRepo.findByApprovedTrueAndRejectedFalseAndCreatedAtAfterOrderByCreatedAtDesc(cutoff, pageable);
    }

    public Page<Pack> getPopularPacksPaginated(Pageable pageable) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(POPULAR_WINDOW_DAYS));
        return packRepo.findPopularSince(cutoff, pageable);
    }

    // ---------- helpers ----------

    private String ensureUniqueBeatSlug(String base) {
        if (base == null || base.isBlank()) base = "beat";
        String s = base;
        int n = 2;
        while (beatRepo.existsBySlug(s)) {
            s = base + "-" + n++;
        }
        return s;
    }

    private Beat createBeatFromUpload(String savedLocation, String originalName, User owner, int durationSeconds) {
        Beat b = new Beat();
        b.setTitle(filenameSansExt(originalName));
        b.setArtist(owner.getDisplayName() != null ? owner.getDisplayName() : owner.getEmail());
        b.setAudioFilePath(savedLocation); // master
        b.setDurationInSeconds(durationSeconds);
        b.setBpm(0);
        b.setGenre(b.getGenre() == null ? "" : b.getGenre());
        b.setPrice(BigDecimal.ZERO);
        b.setPlayCount(0L);
        b.setLikeCount(0);
        b.setOwner(owner);
        b.setUploadedBy(owner);
        b.setApproved(false);
        b.setRejected(false);
        b.setOnSale(false);

        String baseSlug = SlugUtil.toSlug(b.getTitle());
        b.setSlug(ensureUniqueBeatSlug(baseSlug));
        return b;
    }

    private int probeDurationSeconds(Path tmp) {
        try {
            return com.drilldex.drillbackend.util.AudioUtils.getDurationInSeconds(tmp.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Always render an MP3 preview and store it under packs/previews. */
    private void ensurePreviewForBeat(Path tmpMaster, String originalFilename, Beat beat) throws IOException {
        Path previewTmp = Files.createTempFile("beat-preview-", ".mp3");
        try {
            previewGenerator.generatePreview(tmpMaster, previewTmp);

            String base = (originalFilename == null ? "track" : filenameSansExt(originalFilename));
            String previewName = base + "-preview.mp3";

            try (InputStream in = Files.newInputStream(previewTmp)) {
                long size = Files.size(previewTmp);
                String stored = storage.save(in, size, previewName, PACKS_PREVIEWS, "audio/mpeg");

                // Prefer dedicated preview field if your Beat has it
                try {
                    beat.getClass().getMethod("setPreviewAudioPath", String.class).invoke(beat, stored);
                } catch (ReflectiveOperationException ignore) {
                    try { beat.getClass().getMethod("setPreviewUrl", String.class).invoke(beat, stored); }
                    catch (ReflectiveOperationException ignoredToo) { /* no preview field */ }
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to render preview", e);
        } finally {
            try { Files.deleteIfExists(previewTmp); } catch (Exception ignore) {}
        }
    }

    /**
     * Extract audio files from a ZIP, flatten paths so nothing ends up under "__MACOSX/" or nested folders,
     * save masters under packs/audio, and generate MP3 previews under packs/previews.
     */
    private List<Beat> processZipToBeats(MultipartFile zip, User owner, Set<String> seenBases) throws IOException {
        List<Callable<Beat>> tasks = new ArrayList<>();
        Path zipTemp = Files.createTempFile("upload-", ".zip");
        log.info("📦 Saving uploaded ZIP to temp file...");
        zip.transferTo(zipTemp);
        log.info("✅ ZIP saved to {}", zipTemp);

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipTemp))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String entryName = entry.getName();
                if (isMacJunk(entryName) || !isAudioLike(entryName)) continue;

                String base = toBaseFile(entryName); // flatten
                String baseKey = safeBase(base).toLowerCase(Locale.ROOT);

                synchronized (seenBases) {
                    if (!seenBases.add(baseKey)) continue; // skip duplicates
                }

                log.info("🧾 Reading entry '{}'", entryName); // 💬 Entry read log


                // ✅ Read entry into memory immediately to prevent blocking later
                byte[] fileBytes = zis.readAllBytes();

                // 🧵 Create async task to handle disk + processing
                tasks.add(() -> {
                    Path tmp = Files.createTempFile("pack-zip-", "-" + safeName(base));
                    Files.write(tmp, fileBytes);
                    log.info("⏳ [{}] Written to temp file", base);

                    int duration = probeDurationSeconds(tmp);
                    log.info("🎧 [{}] Probed duration: {}s", base, duration);


                    String storedMaster;
                    try (InputStream in = Files.newInputStream(tmp)) {
                        long size = Files.size(tmp);
                        storedMaster = storage.save(in, size, base, PACKS_AUDIO, contentTypeFromName(base));
                    }
                    log.info("🪣 [{}] Stored master audio", base);


                    Beat b = createBeatFromUpload(storedMaster, base, owner, duration);

                    String baseSlugBeat = SlugUtil.toSlug(b.getTitle());
                    String uniqueSlug = ensureUniqueBeatSlug(baseSlugBeat);
                    b.setSlug(uniqueSlug);
                    b.setPartOfPack(true);
                    if (!uniqueSlug.equals(baseSlugBeat)) {
                        b.setTitle(b.getTitle() + " (" + uniqueSlug.substring(baseSlugBeat.length()) + ")");
                    }


                    // Fire-and-forget preview
                    previewExecutor.submit(() -> {
                        try { ensurePreviewForBeat(tmp, base, b); } catch (Exception ignored) {}
                        try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
                    });

                    return b;
                });
            }
        } finally {
            try { Files.deleteIfExists(zipTemp); } catch (Exception ignored) {}
        }

        log.info("🚀 Dispatching {} processing tasks...", tasks.size());


        try {
            List<Future<Beat>> futures = processingExecutor.invokeAll(tasks);
            List<Beat> beats = new ArrayList<>();
            for (Future<Beat> f : futures) {
                Beat b = f.get();
                if (b != null) beats.add(b);
            }
            log.info("💾 Saving {} processed beats to DB...", beats.size());
            beatRepo.saveAll(beats);
            log.info("🎉 All beats saved.");
            return beats;
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Failed to process zip entries", e);
        }
    }

    private static boolean isAudioLike(String pathish) {
        if (pathish == null) return false;
        String base = toBaseFile(pathish).toLowerCase(Locale.ROOT);
        return base.endsWith(".mp3") || base.endsWith(".wav")
                || base.endsWith(".aiff") || base.endsWith(".flac")
                || base.endsWith(".m4a");
    }

    /** Skip macOS junk and hidden files. */
    private static boolean isMacJunk(String pathish) {
        String p = pathish.replace('\\', '/').toLowerCase(Locale.ROOT);
        String base = p.substring(p.lastIndexOf('/') + 1);
        return p.startsWith("__macosx/") || base.startsWith("._") || base.equals(".ds_store");
    }

    /** Return last path segment only. */
    private static String toBaseFile(String pathish) {
        if (pathish == null) return "file";
        String p = pathish.replace('\\', '/');
        String base = p.substring(p.lastIndexOf('/') + 1);
        return base.isBlank() ? "file" : base;
    }

    private static String filenameSansExt(String name) {
        int dot = (name == null ? -1 : name.lastIndexOf('.'));
        return (dot >= 0 ? name.substring(0, dot) : (name == null ? "Untitled" : name));
    }

    private static String safeBase(String name) {
        return filenameSansExt(name == null ? "" : name).replaceAll("\\s+", " ").trim();
    }

    private static String safeName(String name) {
        return name == null ? "file" : name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String contentTypeFromName(String name) {
        String n = (name == null ? "" : name.toLowerCase(Locale.ROOT));
        if (n.endsWith(".mp3"))  return "audio/mpeg";
        if (n.endsWith(".wav"))  return "audio/wav";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".aiff")) return "audio/aiff";
        if (n.endsWith(".m4a"))  return "audio/mp4";
        return "application/octet-stream";
    }

    public List<Pack> getFeaturedPacks(int limit) {
        return repo.findActiveFeatured(
                Instant.now(),
                PageRequest.of(0, Math.max(1, limit))
        );
    }

    public Pack getPackById(Long id) {
        return packRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));
    }

    /** Start or extend a paid feature window for a pack. */
    @Transactional
    public Pack startPaidFeature(Long id, User caller, String tier, int days) {
        Pack p = packRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));

        // owner-or-admin gate
        boolean isAdmin = caller.getRole() != null && caller.getRole().name().equals("ADMIN");
        if (!isAdmin && (p.getOwner() == null || !p.getOwner().getId().equals(caller.getId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your pack");
        }

        // require approved & not rejected (relax if you want to allow pending)
        if (!p.isApproved() || p.isRejected()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Pack must be approved (and not rejected) to feature");
        }

        // normalize tier & clamp days
        String normTier = switch (String.valueOf(tier).toLowerCase(Locale.ROOT)) {
            case "spotlight" -> "spotlight";
            case "premium"   -> "premium";
            default          -> "standard";
        };
        int safeDays = Math.max(1, Math.min(90, days));

        // ✅ Save promotion instead of editing Pack directly
        Promotion promo = new Promotion();
        promo.setTargetType(Promotion.TargetType.PACK);
        promo.setTargetId(p.getId());
        promo.setTier(normTier);
        promo.setDurationDays(safeDays);
        promo.setStartDate(Instant.now());
        promo.setOwner(caller);
        promotionRepository.save(promo);

        return p; // no need to update the pack itself
    }

    public List<Pack> getFeaturedPacksByOwner(Long ownerId, int limit) {
        return repo.findActiveFeaturedByOwner(
                ownerId, Instant.now(), PageRequest.of(0, Math.max(1, limit)));
    }

    /** "New" = newest approved within NEW_WINDOW_DAYS, topped up with newest approved if not enough. */
    public List<Pack> getNewPacksByOwner(Long ownerId, int limit, int page) {
        int capped = Math.max(1, Math.min(200, limit));
        Instant cutoff = Instant.now().minus(Duration.ofDays(NEW_WINDOW_DAYS));

        int fetchSize = Math.max(capped * (page + 1), 50);
        Pageable fetchPage = PageRequest.of(0, fetchSize);

        // Get windowed results (e.g., within 30 days)
        var windowed = repo.findByOwnerIdAndApprovedTrueAndRejectedFalseAndCreatedAtAfterOrderByCreatedAtDesc(
                ownerId, cutoff, fetchPage);

        // Fallback to older approved ones if needed
        var fallback = repo.findByOwnerIdAndApprovedTrueAndRejectedFalseOrderByCreatedAtDesc(ownerId, fetchPage);

        // Merge and dedupe by ID
        var merged = new LinkedHashMap<Long, Pack>();
        windowed.forEach(p -> merged.put(p.getId(), p));
        for (Pack p : fallback) {
            if (merged.size() >= (page + 1) * capped) break;
            merged.putIfAbsent(p.getId(), p);
        }

        var fullList = new ArrayList<>(merged.values());
        int start = page * capped;
        int end = Math.min(start + capped, fullList.size());

        if (start >= end) return List.of();
        return fullList.subList(start, end);
    }

    /** "Popular" = plays + 3*likes over a rolling window; widen the window if empty. */
    public List<Pack> getPopularPacksByOwner(Long ownerId, int limit, int page) {
        return getPopularPacksByOwner(ownerId, limit, page, POPULAR_WINDOW_DAYS);
    }

    private List<Pack> getPopularPacksByOwner(Long ownerId, int limit, int page, int windowDays) {
        int capped = Math.max(1, limit);
        Instant now = Instant.now();

        Instant evalCutoff   = now.minus(Duration.ofDays(Math.max(1, windowDays)));   // scoring window
        Instant maxAgeCutoff = now.minus(Duration.ofDays(POPULAR_MAX_AGE_DAYS));      // hard age cutoff

        // Pull a large enough pool to filter and paginate after filtering
        int poolSize = Math.max(capped * (page + 1) * 2, 100);
        Pageable pageable = PageRequest.of(0, poolSize);

        var base = repo.findOwnerPopularSince(ownerId, evalCutoff, pageable);

        var filtered = base.stream()
                .filter(p -> p.getCreatedAt() != null && !p.getCreatedAt().isBefore(maxAgeCutoff))
                .filter(p -> {
                    long plays = p.getPlayCount();
                    int likes = p.getLikeCount();
                    long score = plays + 3L * likes;
                    boolean meetsFloors = (plays >= POPULAR_MIN_PLAYS) || (likes >= POPULAR_MIN_LIKES);
                    return meetsFloors && score >= POPULAR_MIN_SCORE;
                })
                .sorted((a, b) -> {
                    long scoreA = a.getPlayCount() + 3L * a.getLikeCount();
                    long scoreB = b.getPlayCount() + 3L * b.getLikeCount();
                    int cmp = Long.compare(scoreB, scoreA); // Descending
                    return (cmp != 0) ? cmp : b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .toList();

        int start = page * limit;
        int end = Math.min(start + limit, filtered.size());

        if (start >= end) return List.of();
        return filtered.subList(start, end);
    }

    /**
     * "Trending" = (plays + 3*likes) * decay(ageDays),
     * decay = 0.5^(ageDays / TRENDING_HALFLIFE_DAYS) with half-life ~3 days.
     */
    public List<Pack> getTrendingPacksByOwner(Long ownerId, int limit, int page) {
        int capped = Math.max(1, limit);
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofDays(TRENDING_POOL_DAYS));

        // Pull a large enough pool to filter, sort, then paginate
        int poolSize = Math.max(capped * (page + 1) * 2, 100);
        Pageable pageable = PageRequest.of(0, poolSize);

        var pool = repo.findOwnerTrendingPool(ownerId, cutoff, pageable);
        if (pool.isEmpty()) return List.of();

        var ranked = pool.stream()
                .filter(p -> {
                    long plays = p.getPlayCount();
                    int likes = p.getLikeCount();
                    return plays >= TRENDING_MIN_PLAYS || likes >= TRENDING_MIN_LIKES;
                })
                .sorted((a, b) -> {
                    double sa = trendingScore(a, now);
                    double sb = trendingScore(b, now);
                    int cmp = Double.compare(sb, sa); // Descending by trending score
                    return (cmp != 0) ? cmp : b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .toList();

        int start = page * limit;
        int end = Math.min(start + limit, ranked.size());

        if (start >= end) return List.of();
        return ranked.subList(start, end);
    }

    private static double trendingScore(Pack p, Instant now) {
        long plays = p.getPlayCount();       // primitive long => never null
        int likes  = p.getLikeCount();       // primitive int  => never null
        double base = plays + 3.0 * likes;

        Instant created = (p.getCreatedAt() != null) ? p.getCreatedAt() : now; // guard just in case
        double ageDays = Math.max(0.01, Duration.between(created, now).toHours() / 24.0);

        double decay = Math.pow(0.5, ageDays / TRENDING_HALFLIFE_DAYS);
        return base * decay;
    }

    @Transactional
    public void incrementPlayCount(Long packId) {
        repo.incrementPlayCount(packId);
    }


    public long countDistinctBeatsInPacksByOwner(Long ownerId) {
        return packRepository.countDistinctBeatsAcrossPacksByOwnerId(ownerId);
    }

    public long sumPlayCountByOwnerId(Long ownerId) {
        return packRepository.sumPlayCountByOwnerId(ownerId);
    }

    public Set<Long> getDistinctBeatIdsInPacksByOwner(Long ownerId) {
        return packRepository.findByOwnerId(ownerId).stream()
                .filter(p -> p.isApproved() && !p.isRejected())
                .flatMap(p -> p.getBeats().stream())
                .map(beat -> beat.getId())
                .collect(Collectors.toSet());
    }

    public List<PackDto> getApprovedPacksByOwner(Long ownerId) {
        return packRepository.findByOwnerIdAndApprovedTrue(ownerId)
                .stream().map(PackMapper::toDto).toList();
    }

    @Transactional
    public Pack rejectAndNotify(Long id, String reason) {
        Pack p = packRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));

        boolean saved = false;
        try {
            var setStatus = p.getClass().getMethod("setStatus", String.class);
            setStatus.invoke(p, "rejected");
            saved = true;
        } catch (Exception ignored) {}

        if (!saved) {
            try {
                var setRejected = p.getClass().getMethod("setRejected", boolean.class);
                setRejected.invoke(p, true);
            } catch (Exception ignored) {}
        }

        if (reason != null && !reason.isBlank()) {
            try {
                var setNote = p.getClass().getMethod("setModerationNote", String.class);
                setNote.invoke(p, reason.trim());
            } catch (Exception ignored) {}
        }

        try { p.getClass().getMethod("setFeatured", boolean.class).invoke(p, false); } catch (Exception ignored) {}
        try { p.getClass().getMethod("setFeaturedAt", java.time.Instant.class).invoke(p, new Object[]{null}); } catch (Exception ignored) {}
        try { p.getClass().getMethod("setFeaturedFrom", java.time.Instant.class).invoke(p, new Object[]{null}); } catch (Exception ignored) {}
        try { p.getClass().getMethod("setFeaturedUntil", java.time.Instant.class).invoke(p, new Object[]{null}); } catch (Exception ignored) {}
        try { p.getClass().getMethod("setFeaturedTier", String.class).invoke(p, new Object[]{null}); } catch (Exception ignored) {}

        Pack savedPack = packRepository.save(p);

        try {
            User owner = savedPack.getOwner();
            if (owner != null && owner.getId() != null) {
                String body = (reason == null || reason.isBlank())
                        ? "Your pack has been rejected by the moderation team."
                        : "Your pack has been rejected by the moderation team because: " + reason.trim();

                chatStorageService.sendAdminMessageToUser(owner.getId(), body);
            }
        } catch (Exception ignored) {}

        return savedPack;
    }

    private static String safeTitle(Pack p) {
        try {
            var m = p.getClass().getMethod("getTitle");
            Object v = m.invoke(p);
            return v == null ? "(untitled)" : String.valueOf(v);
        } catch (Exception e) {
            return "(untitled)";
        }
    }

    public Page<Pack> getGlobalNewPacksPage(int page, int limit) {
        int pg = Math.max(0, page);
        int capped = Math.max(1, limit);
        Pageable pageable = PageRequest.of(pg, capped);

        Instant cutoff = Instant.now().minus(Duration.ofDays(NEW_WINDOW_DAYS));
        Page<Pack> packs = packRepository.findByApprovedTrueAndRejectedFalseAndCreatedAtAfterOrderByCreatedAtDesc(cutoff, pageable);

        return packs;
    }

    public int getTotalNewPacks() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(NEW_WINDOW_DAYS));
        return packRepository.countNewPacksSince(cutoff); // Add this repository method if not exists
    }

    public List<Pack> getGlobalTrendingPacks(int page, int limit) {
        int capped = Math.max(1, limit);
        int poolSize = Math.max(capped * (page + 1) * 2, 100);
        Pageable fetchPage = PageRequest.of(0, poolSize);

        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofDays(TRENDING_POOL_DAYS));

        List<Pack> pool = packRepo.findGlobalTrendingPool(cutoff, fetchPage);
        if (pool.isEmpty()) return List.of();

        List<Pack> ranked = pool.stream()
                .filter(p -> p.getPlayCount() >= TRENDING_MIN_PLAYS || p.getLikeCount() >= TRENDING_MIN_LIKES)
                .sorted((a, b) -> {
                    double sa = trendingScore(a, now);
                    double sb = trendingScore(b, now);
                    int cmp = Double.compare(sb, sa);
                    return (cmp != 0) ? cmp : b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .toList();

        int start = page * limit;
        int end = Math.min(start + limit, ranked.size());
        if (start >= end) return List.of();

        return ranked.subList(start, end);
    }

    public List<Pack> getGlobalPopularPacks(int page, int limit) {
        int capped = Math.max(1, limit);
        int poolSize = Math.max((page + 1) * capped * 2, 100);
        Pageable fetchPage = PageRequest.of(0, poolSize);

        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofDays(POPULAR_WINDOW_DAYS));

        List<Pack> pool = packRepo.findGlobalPopularSince(cutoff, fetchPage);
        if (pool.isEmpty()) return List.of();

        List<Pack> ranked = pool.stream()
                .filter(p -> p.getPlayCount() >= POPULAR_MIN_PLAYS || p.getLikeCount() >= POPULAR_MIN_LIKES)
                .filter(p -> (p.getPlayCount() + 3L * p.getLikeCount()) >= POPULAR_MIN_SCORE)
                .sorted((a, b) -> {
                    long scoreA = a.getPlayCount() + 3L * a.getLikeCount();
                    long scoreB = b.getPlayCount() + 3L * b.getLikeCount();
                    int cmp = Long.compare(scoreB, scoreA);
                    return (cmp != 0) ? cmp : b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .toList();

        int start = page * limit;
        int end = Math.min(start + limit, ranked.size());
        if (start >= end) return List.of();
        return ranked.subList(start, end);
    }

    private String slugify(String s) {
        if (s == null || s.isBlank()) return "untitled";
        String base = s.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return base.isBlank() ? "untitled" : base;
    }

    public void uploadPackStems(User user, Long packId, MultipartFile stemsZip) {
        Pack pack = packRepository.findById(packId)
                .orElseThrow(() -> new IllegalArgumentException("Pack not found"));

        if (!pack.getOwner().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Unauthorized to upload stems for this pack");
        }

        try {
            String safeTitle = slugify(pack.getTitle());
            Path tempDir = Files.createTempDirectory("stems-" + safeTitle + "-");
            String stemsDirPath = PACKS_STEMS + "/" + safeTitle;

            // ✅ Extract and upload new stems
            AudioUtils.extractAudioFromZip(stemsZip, tempDir);

            Files.walk(tempDir)
                    .filter(Files::isRegularFile)
                    .filter(f -> {
                        String name = f.getFileName().toString().toLowerCase();
                        // 🎧 Allow only audio files
                        if (!(name.endsWith(".mp3") || name.endsWith(".wav"))) return false;
                        // 🚫 Skip macOS junk files
                        if (name.startsWith("._") || name.equals(".ds_store") || name.contains("__macosx"))
                            return false;
                        try {
                            long size = Files.size(f);
                            // 🚫 Skip very small / junk files (< 8 KB)
                            if (size < 8 * 1024) {
                                log.debug("Skipping small stem ({} bytes): {}", size, name);
                                return false;
                            }
                        } catch (IOException e) {
                            return false;
                        }
                        return true;
                    })
                    .forEach(file -> {
                        try (InputStream in = Files.newInputStream(file)) {
                            long size = Files.size(file);
                            String filename = file.getFileName().toString();
                            String mime = filename.toLowerCase().endsWith(".wav")
                                    ? "audio/wav" : "audio/mpeg";

                            storage.save(in, size, filename, stemsDirPath, mime);
                            log.info("✅ Uploaded stem: {}/{}", stemsDirPath, filename);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to upload stem: " + file.getFileName(), e);
                        }
                    });

            // ✅ Save stems path in DB
            pack.setStemsFilePath(stemsDirPath);
            packRepository.save(pack);

            FileUtils.deleteDirectory(tempDir.toFile());
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload stems for pack: " + e.getMessage(), e);
        }
    }

}
