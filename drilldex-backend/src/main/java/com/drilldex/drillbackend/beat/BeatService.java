package com.drilldex.drillbackend.beat;

import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.beat.BeatRepository;
import com.drilldex.drillbackend.shared.SlugUtil;
import com.drilldex.drillbackend.storage.StorageService;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import com.drilldex.drillbackend.util.AudioUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.http.fileupload.FileUtils;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class BeatService {

    // Windows
    private static final int NEW_WINDOW_DAYS           = 60;
    private static final int POPULAR_WINDOW_DAYS       = 60;   // evaluate in last 30d
    private static final int POPULAR_MAX_AGE_DAYS      = 90;   // never call “popular” if older than 90d
    private static final int TRENDING_POOL_DAYS        = 21;
    private static final double TRENDING_HALFLIFE_DAYS = 2.5;  // faster decay

    // Popularity min floors (tune as you wish)
    private static final long POPULAR_MIN_PLAYS  = 50;
    private static final int  POPULAR_MIN_LIKES  = 5;
    private static final long POPULAR_MIN_SCORE  = 80;   // plays + 3*likes

    // Trending min floors
    private static final long TRENDING_MIN_PLAYS = 10;
    private static final int  TRENDING_MIN_LIKES = 2;

    private final BeatRepository beatRepository;
    private final StorageService storage;
    private final BeatRepository repo;
    private final BeatRepository beatRepo;
    private final UserRepository userRepository;
    private final com.drilldex.drillbackend.user.CurrentUserService currentUserService;


    /* ================= POPULAR ================== */

    public List<Beat> getPopularBeats(int limit) {
        int capped = Math.max(1, limit);
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofDays(POPULAR_WINDOW_DAYS));
        Pageable page = PageRequest.of(0, Math.max(capped * 3, 100));

        var candidates = repo.findPopularSince(cutoff, page); // should already be approved+not rejected

        var filtered = candidates.stream()
                // hard age cap
                .filter(b -> b.getCreatedAt() != null &&
                        !b.getCreatedAt().isBefore(now.minus(Duration.ofDays(POPULAR_MAX_AGE_DAYS))))
                // thresholds
                .filter(this::passesPopularThresholds)
                // exclude items considered "trending" right now
                .filter(b -> !classifyTrending(b, now))
                .sorted((a, b) -> {
                    long sa = (a.getPlayCount() == null ? 0L : a.getPlayCount()) + 3L * a.getLikeCount();
                    long sb = (b.getPlayCount() == null ? 0L : b.getPlayCount()) + 3L * b.getLikeCount();
                    int cmp = Long.compare(sb, sa); // desc by score
                    return (cmp != 0) ? cmp : b.getCreatedAt().compareTo(a.getCreatedAt()); // tie -> newer first
                })
                .limit(capped)
                .toList();

        return filtered;
    }

    private boolean passesPopularThresholds(Beat b) {
        long plays = b.getPlayCount() == null ? 0L : b.getPlayCount();
        int  likes = b.getLikeCount();
        long score = plays + 3L * likes;
        return plays >= POPULAR_MIN_PLAYS && likes >= POPULAR_MIN_LIKES && score >= POPULAR_MIN_SCORE;
    }

    public java.util.List<Beat> getPopularBeats(int limit, int windowDays) {
        int capped = Math.max(1, limit);
        var cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(Math.max(1, windowDays)));
        var page   = org.springframework.data.domain.PageRequest.of(0, capped);

        // plays + 3*likes over window, then newest
        var items = repo.findPopularSince(cutoff, page);
        if (!items.isEmpty() || windowDays >= 365) return items;

        // Fallback: widen window progressively (simple, safe)
        return getPopularBeats(limit, windowDays * 2);
    }

    /* ================= TRENDING ================= */

    /**
     * "Trending" = recent momentum with recency decay.
     * We take a pool of recent approved beats (e.g., last 30 days), then rank by:
     *   score = (plays + 3*likes) * decay(ageDays), where decay = 0.5^(ageDays / halfLife)
     * Half-life ~3 days gives yesterday a big boost vs last week.
     */
    public Page<Beat> getGlobalTrendingBeatsPage(int page, int limit) {
        int pg = Math.max(0, page);
        int capped = Math.max(1, limit);
        Pageable pageable = PageRequest.of(pg, capped);

        Instant now = Instant.now();
        Instant poolCutoff = now.minus(Duration.ofDays(TRENDING_POOL_DAYS));

        // 1) Fetch recent approved beats (global)
        List<Beat> recent = repo.findByApprovedTrueAndRejectedFalseAndCreatedAtAfter(poolCutoff, pageable);
        if (recent.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        // 2) Filter for minimum plays/likes (avoid tiny spikes)
        List<Beat> filtered = recent.stream()
                .filter(b -> (b.getPlayCount() != null && b.getPlayCount() >= TRENDING_MIN_PLAYS) ||
                        (b.getLikeCount() >= TRENDING_MIN_LIKES))
                .sorted((a, b) -> {
                    double sa = trendingScore(a, now);
                    double sb = trendingScore(b, now);
                    int cmp = Double.compare(sb, sa); // descending score
                    return (cmp != 0) ? cmp : b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .toList();

        // 3) Paginate filtered results manually (if filtering removed some)
        int start = pg * capped;
        int end = Math.min(start + capped, filtered.size());
        if (start >= end) return new PageImpl<>(List.of(), pageable, filtered.size());

        List<Beat> pageContent = filtered.subList(start, end);
        return new PageImpl<>(pageContent, pageable, filtered.size());
    }

    private static double trendingScore(Beat b, Instant now) {
        long plays = b.getPlayCount() == null ? 0L : b.getPlayCount();
        int likes  = b.getLikeCount();
        double base = plays + 3.0 * likes;

        double ageDays = Math.max(0.01,
                Duration.between(b.getCreatedAt(), now).toHours() / 24.0);
        double decay = Math.pow(0.5, ageDays / TRENDING_HALFLIFE_DAYS);

        return base * decay;
    }

    // quick classifier used to keep lists exclusive
    private boolean classifyTrending(Beat b, Instant now) {
        if (b.getCreatedAt() == null ||
                b.getCreatedAt().isBefore(now.minus(Duration.ofDays(TRENDING_POOL_DAYS)))) {
            return false;
        }
        long plays = b.getPlayCount() == null ? 0L : b.getPlayCount();
        int  likes = b.getLikeCount();
        if (plays < TRENDING_MIN_PLAYS && likes < TRENDING_MIN_LIKES) return false;
        return trendingScore(b, now) > 0;
    }

    public Beat saveBeat(Beat beat) {
        return beatRepository.save(beat);
    }

    public Optional<Beat> getBeatById(Long id) {
        return beatRepository.findById(id);
    }

    public void deleteBeatById(Long id) throws IOException {
        Beat beat = beatRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Beat not found"));

        // Use the abstraction: it knows how to delete local keys or URLs.
        if (beat.getAudioFilePath() != null && !beat.getAudioFilePath().isBlank()) {
            storage.delete(beat.getAudioFilePath());
        }
        if (beat.getCoverImagePath() != null && !beat.getCoverImagePath().isBlank()) {
            storage.delete(beat.getCoverImagePath());
        }

        beatRepository.delete(beat);
    }

    public List<Beat> getAllBeats() {
        return beatRepository.findAll();
    }


    @Transactional
    public Beat featureBeat(Long beatId) {
        Beat b = beatRepository.findById(beatId)
                .orElseThrow(() -> new IllegalArgumentException("Beat not found: " + beatId));
        b.setFeatured(true);
        b.setFeaturedAt(Instant.now());
        return beatRepository.save(b);
    }

    @Transactional
    public Beat unfeatureBeat(Long beatId) {
        Beat b = beatRepository.findById(beatId)
                .orElseThrow(() -> new IllegalArgumentException("Beat not found: " + beatId));
        b.setFeatured(false);
        b.setFeaturedAt(null);
        return beatRepository.save(b);
    }


    public List<Beat> getApprovedBeats() {
        return beatRepository.findByApprovedTrueAndRejectedFalseOrderByIdDesc();
    }

    public List<Beat> getFeaturedBeats(int limit) {
        return repo.findActiveFeatured(Instant.now(), PageRequest.of(0, Math.max(1, limit)));
    }

    /** Start or extend a paid feature window for a beat. */
    @Transactional
    public Beat startPaidFeature(Long beatId, String tier, int days) {
        Beat b = repo.findById(beatId).orElseThrow(() -> new IllegalArgumentException("Beat not found: " + beatId));
        if (!b.isApproved() || b.isRejected()) {
            throw new IllegalStateException("Beat must be approved and not rejected to be featured");
        }

        Instant now = Instant.now();
        Instant from = (b.getFeaturedUntil() != null && b.getFeaturedUntil().isAfter(now))
                ? b.getFeaturedFrom() == null ? now : b.getFeaturedFrom()   // keep existing from if still active
                : now;

        Instant until = (b.getFeaturedUntil() != null && b.getFeaturedUntil().isAfter(now))
                ? b.getFeaturedUntil().plus(Duration.ofDays(Math.max(1, days))) // extend current window
                : now.plus(Duration.ofDays(Math.max(1, days)));                  // new window

        b.setFeatured(true);                 // keep this true for legacy clients
        b.setFeaturedAt(from);               // optional: mirror
        b.setFeaturedFrom(from);
        b.setFeaturedUntil(until);
        b.setFeaturedTier((tier == null ? "standard" : tier.trim().toLowerCase()));

        return repo.save(b);
    }

    // ---- Style registry (Phase 3) ----
    private record StyleDef(String genre, java.util.List<String> aliases) {}

    private static final java.util.Map<String, StyleDef> STYLE_REGISTRY = new java.util.HashMap<>() {{
        put("uk-drill",        new StyleDef("uk drill",        java.util.List.of("ukdrill","uk drill","uk")));
        put("ny-drill",        new StyleDef("ny drill",        java.util.List.of("nydrill","ny drill","new york drill","ny")));
        put("chicago-drill",   new StyleDef("chicago drill",   java.util.List.of("chicagodrill","chicago","chi")));
        put("dutch-drill",     new StyleDef("dutch drill",     java.util.List.of("dutchdrill","dutch","nl")));
        put("french-drill",    new StyleDef("french drill",    java.util.List.of("frenchdrill","fr","france")));
        put("afro-drill",      new StyleDef("afro drill",      java.util.List.of("afrodrill","afro")));
        put("canadian-drill",  new StyleDef("canadian drill",  java.util.List.of("canadiandrill","canada","ca")));
        put("australian-drill",new StyleDef("australian drill",java.util.List.of("aussi drill","australia","au","australiandrill")));
        put("irish-drill",     new StyleDef("irish drill",     java.util.List.of("irishdrill","ireland","ie")));
        put("german-drill",    new StyleDef("german drill",    java.util.List.of("germandrill","germany","de")));
        put("spanish-drill",   new StyleDef("spanish drill",   java.util.List.of("spanishdrill","spain","es")));
        put("italian-drill",   new StyleDef("italian drill",   java.util.List.of("italiandrill","italy","it")));
        put("brazilian-drill", new StyleDef("brazilian drill", java.util.List.of("braziliandrill","brazil","br")));
    }};

    private static String normalizeSlug(String s) {
        if (s == null) return "";
        return s.replace('-', ' ').replaceAll("\\s+", " ").trim().toLowerCase();
    }

    public int getTotalBeatsForStyle(String slug, Integer bpmMin, Integer bpmMax) {
        String key = (slug == null ? "" : slug.trim().toLowerCase());
        String genre = SlugUtil.toSlug(key); // Normalize the slug if needed

        // 1) Exact genre matches
        List<Beat> exact = repo.findByFilters(genre, bpmMin, bpmMax);

        // 2) Tag alias fallback
        List<Beat> tagOnly = repo.filterWithTags(null, bpmMin, bpmMax, genre, null, null);

        // 3) Merge + deduplicate by ID
        Set<Long> seenIds = new HashSet<>();
        int count = 0;

        for (Beat b : exact) {
            if (seenIds.add(b.getId())) count++;
        }
        for (Beat b : tagOnly) {
            if (seenIds.add(b.getId())) count++;
        }

        return count;
    }

    /** Phase 3: genre-first + tag-alias top-up, ranked by trending score. */
    public List<Beat> getBeatsForStyle(String slug, Integer bpmMin, Integer bpmMax, int page, int limit) {
        int capped = Math.max(1, Math.min(200, limit));
        int offset = page * capped;

        String key = (slug == null ? "" : slug.trim().toLowerCase());
        StyleDef def = STYLE_REGISTRY.getOrDefault(key, new StyleDef(normalizeSlug(slug), List.of()));

        // 1) Exact-genre matches
        List<Beat> exact = repo.findByFilters(def.genre(), bpmMin, bpmMax).stream()
                .filter(b -> b.isApproved() && !b.isRejected())
                .toList();

// 2) Tag-based fallback (genre null so we only use tag aliases)
        List<String> a = def.aliases();
        String a1 = a.size() > 0 ? a.get(0) : null;
        String a2 = a.size() > 1 ? a.get(1) : null;
        String a3 = a.size() > 2 ? a.get(2) : null;
        List<Beat> tagOnly = (a1 == null && a2 == null && a3 == null)
                ? List.of()
                : repo.filterWithTags(null, bpmMin, bpmMax, a1, a2, a3).stream()
                .filter(b -> b.isApproved() && !b.isRejected())
                .toList();

        // 3) Merge results and remove duplicates (exact first)
        LinkedHashMap<Long, Beat> merged = new LinkedHashMap<>();
        for (Beat b : exact) merged.put(b.getId(), b);
        for (Beat b : tagOnly) merged.putIfAbsent(b.getId(), b);

        List<Beat> candidates = new ArrayList<>(merged.values());

        // 4) Sort by trending score, then by recency
        Instant now = Instant.now();
        candidates.sort((b1, b2) -> {
            double s1 = trendingScore(b1, now);
            double s2 = trendingScore(b2, now);
            int cmp = Double.compare(s2, s1); // Descending
            return (cmp != 0) ? cmp : b2.getCreatedAt().compareTo(b1.getCreatedAt());
        });

        // 5) Paginate the result
        int start = offset;
        int end = Math.min(start + capped, candidates.size());
        if (start >= end) return List.of();

        return candidates.subList(start, end);
    }

    @Transactional
    public Beat startFeatureByOwner(Long id, User owner, String tier, int days) {
        Beat b = beatRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found"));

        if (b.getOwner() == null || !b.getOwner().getId().equals(owner.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your beat");
        }

        // normalize inputs
        int d = Math.max(1, Math.min(30, days));
        String t = (tier == null ? "standard" : tier.trim().toLowerCase());
        if (!t.equals("standard") && !t.equals("premium") && !t.equals("spotlight")) t = "standard";

        Instant now = Instant.now();
        Instant from = (b.getFeaturedUntil() != null && b.getFeaturedUntil().isAfter(now))
                ? b.getFeaturedFrom() : now;
        Instant until = (b.getFeaturedUntil() != null && b.getFeaturedUntil().isAfter(now))
                ? b.getFeaturedUntil().plus(d, ChronoUnit.DAYS)
                : now.plus(d, ChronoUnit.DAYS);

        b.setFeatured(true);
        b.setFeaturedFrom(from);
        b.setFeaturedUntil(until);
        b.setFeaturedTier(t);

        return beatRepository.save(b);
    }

    public List<Beat> getApprovedBeatsByOwner(Long ownerId, int limit, int page) {
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);

        Pageable pageable = PageRequest.of(pageIndex, cappedLimit, Sort.by(Sort.Direction.DESC, "createdAt"));

        // Fetch from repo only approved and not rejected beats
        return repo.findByOwnerIdAndApprovedTrueAndRejectedFalseOrderByCreatedAtDesc(ownerId, pageable);
    }


    public List<Beat> getFeaturedBeatsByOwner(Long ownerId, int limit) {
        return repo.findActiveFeaturedByOwner(
                ownerId, Instant.now(), PageRequest.of(0, Math.max(1, limit)));
    }

    public List<Beat> getNewBeatsByOwner(Long ownerId, int limit) {
        int capped = Math.max(1, limit);
        Instant cutoff = Instant.now().minus(Duration.ofDays(NEW_WINDOW_DAYS));
        Pageable page = PageRequest.of(0, capped);

        var windowed = repo.findByOwnerIdAndApprovedTrueAndRejectedFalseAndCreatedAtAfterOrderByCreatedAtDesc(
                ownerId, cutoff, page);
        if (windowed.size() >= capped) return windowed;

        // top up newest (no dups)
        var fallback = repo.findByOwnerIdAndApprovedTrueAndRejectedFalseOrderByCreatedAtDesc(ownerId, page);
        var merged = new java.util.LinkedHashMap<Long, Beat>();
        windowed.forEach(b -> merged.put(b.getId(), b));
        for (Beat b : fallback) {
            if (merged.size() >= capped) break;
            merged.putIfAbsent(b.getId(), b);
        }
        return new java.util.ArrayList<>(merged.values());
    }

    public List<Beat> getPopularBeatsByOwner(Long ownerId, int limit) {
        return getPopularBeatsByOwner(ownerId, limit, POPULAR_WINDOW_DAYS);
    }

    private List<Beat> getPopularBeatsByOwner(Long ownerId, int limit, int windowDays) {
        int capped = Math.max(1, limit);
        Instant cutoff = Instant.now().minus(Duration.ofDays(Math.max(1, windowDays)));
        Pageable page = PageRequest.of(0, capped);

        var items = repo.findOwnerPopularSince(ownerId, cutoff, page);
        if (!items.isEmpty() || windowDays >= 365) return items;

        return getPopularBeatsByOwner(ownerId, limit, windowDays * 2);
    }

    public Page<Beat> getGlobalPopularBeatsPage(int page, int limit) {
        int pg = Math.max(0, page);
        int capped = Math.max(1, limit);
        Pageable pageable = PageRequest.of(pg, capped);

        Instant cutoff = Instant.now().minus(Duration.ofDays(POPULAR_WINDOW_DAYS));
        List<Beat> beats = beatRepository.findGlobalPopularSince(cutoff, pageable);

        long total = beatRepository.countAllApproved(); // for pagination metadata
        return new PageImpl<>(beats, pageable, total);
    }

    public List<Beat> getTrendingBeatsByOwner(Long ownerId, int limit) {
        int capped = Math.max(1, limit);
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofDays(TRENDING_POOL_DAYS));

        int poolSize = Math.max(capped * 5, 200);
        Pageable page = PageRequest.of(0, poolSize);

        var pool = repo.findOwnerTrendingPool(ownerId, cutoff, page);
        if (pool.isEmpty()) return java.util.List.of();

        var ranked = new java.util.ArrayList<>(pool);
        ranked.sort((a, b) -> {
            double sa = trendingScore(a, now);  // SAME formula as global
            double sb = trendingScore(b, now);
            int cmp = Double.compare(sb, sa);
            if (cmp != 0) return cmp;
            return b.getCreatedAt().compareTo(a.getCreatedAt()); // newer first
        });

        return ranked.subList(0, Math.min(capped, ranked.size()));
    }

    private String makeUniqueSlug(String base) {
        String root = SlugUtil.toSlug(base);
        String candidate = root;
        int i = 2;
        while (beatRepo.existsBySlug(candidate)) {
            candidate = root + "-" + i++;
            if (i > 999) { // absolute safety valve
                candidate = root + "-" + System.currentTimeMillis();
                break;
            }
        }
        return candidate;
    }

    @Transactional
    public Beat create(Beat beat) {
        // only set once if not provided
        if (beat.getSlug() == null || beat.getSlug().isBlank()) {
            String base = (beat.getArtist() != null && !beat.getArtist().isBlank() ? beat.getArtist() + " " : "")
                    + (beat.getTitle() != null ? beat.getTitle() : "");
            beat.setSlug(makeUniqueSlug(base));
        } else {
            // if provided by client, still ensure uniqueness
            beat.setSlug(makeUniqueSlug(beat.getSlug()));
        }
        return beatRepo.save(beat);
    }

    @Transactional(readOnly = true)
    public Beat getByHandle(String handle) {
        if (handle.matches("\\d+")) {
            return beatRepo.findById(Long.valueOf(handle))
                    .orElseThrow(() -> new IllegalArgumentException("Beat not found"));
        }
        return beatRepo.findBySlug(handle)
                .orElseThrow(() -> new IllegalArgumentException("Beat not found"));
    }

    @Transactional
    public Beat update(Long id, Beat patch) {
        Beat b = beatRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Beat not found"));

        // Update fields (example—adjust to your real patching logic)
        if (patch.getTitle() != null) b.setTitle(patch.getTitle());
        if (patch.getArtist() != null) b.setArtist(patch.getArtist());
        // DO NOT auto-change slug on normal updates (keeps links stable)

        return beatRepo.save(b);
    }

    @Transactional
    public BeatDto likeBeat(Long beatId) {
        User current = currentUserService.getCurrentUserOrThrow();
        Beat beat = beatRepository.findById(beatId)
                .orElseThrow(() -> new RuntimeException("Beat not found"));

        boolean already = beat.getUpvotedBy().stream()
                .anyMatch(u -> u.getId().equals(current.getId()));
        if (!already) {
            beat.getUpvotedBy().add(current);   // mutate owner side
            beat.setLikeCount(beat.getUpvotedBy().size());
            beatRepository.saveAndFlush(beat);  // ensure join row is persisted
        }

        return BeatMapper.mapToDto(beat, current); // liked=true now
    }

    @Transactional
    public BeatDto unlikeBeat(Long beatId) {
        User current = currentUserService.getCurrentUserOrThrow();
        Beat beat = beatRepository.findById(beatId)
                .orElseThrow(() -> new RuntimeException("Beat not found"));

        boolean removed = beat.getUpvotedBy()
                .removeIf(u -> u.getId().equals(current.getId()));
        if (removed) {
            beat.setLikeCount(beat.getUpvotedBy().size());
            beatRepository.saveAndFlush(beat);
        }

        return BeatMapper.mapToDto(beat, current); // liked=false now
    }
    private static String toPublicUrl(String path) {
        return (path != null && !path.isBlank()) ? "/uploads/" + path : null;
    }

    public Page<Beat> getNewBeats(int page, int limit) {
        int pg = Math.max(0, page);
        int capped = Math.max(1, limit);
        Pageable pageable = PageRequest.of(pg, capped);

        Instant cutoff = Instant.now().minus(Duration.ofDays(NEW_WINDOW_DAYS));
        return beatRepository.findNewBeatsSince(cutoff, pageable);
    }

    /**
     * Returns the total number of new beats globally
     */
    public int getTotalNewBeats() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(NEW_WINDOW_DAYS));
        return beatRepository.countNewBeatsSince(cutoff);
    }
    private String slugify(String s) {
        if (s == null || s.isBlank()) return "untitled";
        String base = s.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return base.isBlank() ? "untitled" : base;
    }

    public void uploadBeatStems(User user, Long beatId, MultipartFile stemsZip) {
        Beat beat = beatRepository.findById(beatId)
                .orElseThrow(() -> new IllegalArgumentException("Beat not found"));

        if (!beat.getOwner().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Unauthorized to upload stems for this beat");
        }

        try {
            String safeTitle = slugify(beat.getTitle());
            Path tempDir = Files.createTempDirectory("stems-" + safeTitle + "-");
            String stemsDirPath = "stems/" + safeTitle;

            // ✅ 1. Extract audio files (.mp3 / .wav)
            AudioUtils.extractAudioFromZip(stemsZip, tempDir);

            // ✅ 2. Upload new audio files (local)
            Files.walk(tempDir)
                    .filter(Files::isRegularFile)
                    .filter(f -> {
                        String name = f.getFileName().toString().toLowerCase();
                        return name.endsWith(".mp3") || name.endsWith(".wav");
                    })
                    .forEach(file -> {
                        try (InputStream in = Files.newInputStream(file)) {
                            long size = Files.size(file);
                            String filename = file.getFileName().toString();
                            String mime = filename.toLowerCase().endsWith(".wav")
                                    ? "audio/wav" : "audio/mpeg";
                            storage.save(in, size, filename, stemsDirPath, mime);
                            log.info("Uploaded stem: {}/{}", stemsDirPath, filename);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to upload stem: " + file.getFileName(), e);
                        }
                    });

            // ✅ 3. Update DB path and save
            beat.setStemsFilePath(stemsDirPath);
            beatRepository.save(beat);

            // ✅ 4. Clean up temp folder
            FileUtils.deleteDirectory(tempDir.toFile());
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload stems for beat: " + e.getMessage(), e);
        }
    }

}
