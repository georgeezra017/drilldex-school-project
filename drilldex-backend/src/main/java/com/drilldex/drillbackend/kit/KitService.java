package com.drilldex.drillbackend.kit;

import com.drilldex.drillbackend.kit.dto.FeaturedKitDto;
import com.drilldex.drillbackend.kit.dto.KitSummaryDto;
import com.drilldex.drillbackend.kit.dto.KitUploadMeta;
import com.drilldex.drillbackend.preview.PreviewGenerator;
import com.drilldex.drillbackend.promotions.Promotion;
import com.drilldex.drillbackend.promotions.PromotionRepository;
import com.drilldex.drillbackend.promotions.PromotionService;
import com.drilldex.drillbackend.purchase.PurchaseRepository;
import com.drilldex.drillbackend.storage.StorageService;
import com.drilldex.drillbackend.shared.SlugUtil;
import com.drilldex.drillbackend.user.CurrentUserService;
import com.drilldex.drillbackend.user.Role;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.util.AudioUtils;
import com.drilldex.drillbackend.util.TagUtils;
import com.drilldex.drillbackend.util.ZipUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import com.drilldex.drillbackend.preview.PreviewGenerator;

import java.io.*;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KitService {

    private final KitRepository kitRepository;
    private final StorageService storage;
    private final KitFileRepository kitFileRepository;
    private final PromotionRepository promotionRepository;
    private final CurrentUserService currentUserService;
    private final PurchaseRepository purchaseRepository;

    /** Namespaces used under local storage. */
    private static final String KITS_COVERS   = "kits/covers";
    private static final String KITS_AUDIO    = "kits/audio";
    private static final String KITS_PREVIEWS = "kits/previews";
    private static final String KITS_FILES    = "kits/files";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PreviewGenerator previewGenerator;

    private final KitRepository repo;

    // keep in sync with Pack/Beat
    private static final int NEW_WINDOW_DAYS            = 60;
    private static final int NEW_KIT_WINDOW_DAYS = 60;

    private static final int POPULAR_WINDOW_DAYS        = 60;   // evaluate over last 30d
    private static final int POPULAR_MAX_AGE_DAYS       = 90;   // never call “popular” if older than 90d

    private static final int TRENDING_POOL_DAYS         = 21;   // pool of recent items
    private static final double TRENDING_HALFLIFE_DAYS  = 2.5;  // faster decay

    // Popularity min floors (tune to taste)
    private static final long POPULAR_MIN_PLAYS         = 50;
    private static final int  POPULAR_MIN_LIKES         = 5;    // if you track likes/upvotes on kits
    private static final long POPULAR_MIN_SCORE         = 80;   // plays + 3*likes (+ 2*downloads if you want)

    // Trending floors (light-touch)
    private static final long TRENDING_MIN_PLAYS        = 10;
    private static final int  TRENDING_MIN_LIKES        = 2;

    private static final String PUBLISHED               = "published";

    private final ExecutorService processingExecutor = Executors.newFixedThreadPool(8);
    private final ExecutorService previewExecutor = Executors.newFixedThreadPool(4);

    @Transactional
    public Kit createKit(
            User owner,
            KitUploadMeta meta,
            MultipartFile cover,
            List<MultipartFile> files,   // ignored (zip-only)
            MultipartFile zip
    ) throws IOException {

        if (meta == null || meta.name() == null || meta.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kit name is required");
        }
        BigDecimal price = meta.price() == null ? BigDecimal.ZERO : meta.price();
        if (price.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Price must be >= 0");
        }

        if (zip == null || zip.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a .zip (kits are zip-only)");
        }
        if (!looksLikeZip(zip)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be a .zip");
        }

        Kit k = new Kit();
        k.setTitle(meta.name().trim());
        k.setType(meta.type() == null ? "Drum Kit" : meta.type().trim());
        k.setDescription(meta.description() == null ? "" : meta.description().trim());
        k.setTags(TagUtils.normalizeTags(meta.tags() == null ? "" : meta.tags()));
        k.setPrice(price);
        k.setBpmMin(meta.bpmMin());
        k.setBpmMax(meta.bpmMax());
        k.setKeySignature(meta.key());
        k.setSlug(ensureUniqueKitSlug(SlugUtil.toSlug(meta.name())));
        k.setOwner(owner);
        k.setStatus("pending");
        k.setPublishedAt(null);

        // ---- cover ----
        if (cover != null && !cover.isEmpty()) {
            String savedCover = storage.save(cover, KITS_COVERS);
            k.setCoverImagePath(savedCover);
        }

        // ---- unzip + parallel processing ----
        // ---- unzip + parallel processing ----
        List<String> saved = Collections.synchronizedList(new ArrayList<>());
        AtomicLong totalDurationSec = new AtomicLong(0);
        List<Callable<Void>> tasks = new ArrayList<>();

        for (var entry : ZipUtils.iterZip(zip)) {
            String rawName = entry.name();
            if (rawName == null || rawName.isBlank() || isMacJunk(rawName)) continue;

            String baseName = safeName(baseName(rawName));
            if (baseName.isBlank()) continue;

            String contentType = entry.contentType();
            if (contentType == null || contentType.isBlank()) {
                contentType = guessContentType(baseName);
            }

            File tmp = File.createTempFile("kit-", "-" + baseName);
            try (InputStream in = entry.stream(); OutputStream out = new FileOutputStream(tmp)) {
                in.transferTo(out);
            }

            boolean isAudioFile = isAudio(baseName, contentType);

            // 🔥 make local final copies for lambda capture
            final File tmpFile = tmp;
            final String finalBaseName = baseName;
            final String finalContentType = contentType;

            if (!isAudioFile) {
                tasks.add(() -> {
                    try (InputStream in = new FileInputStream(tmpFile)) {
                        String key = storage.save(in, tmpFile.length(), finalBaseName, KITS_FILES, finalContentType);
                        saved.add(key);
                    } finally {
                        tmpFile.delete();
                    }
                    return null;
                });
            } else {
                tasks.add(() -> {
                    try (InputStream in = new FileInputStream(tmpFile)) {
                        String key = storage.save(in, tmpFile.length(), finalBaseName, KITS_AUDIO, finalContentType);
                        saved.add(key);
                    }
                    try {
                        int sec = AudioUtils.getDurationInSecondsFromFile(tmpFile);
                        if (sec > 0) totalDurationSec.addAndGet(sec);
                    } catch (Exception ignored) {}

                    // Generate preview async
                    File previewTmp = File.createTempFile("kit-prev-", ".mp3");
                    previewExecutor.submit(() -> {
                        try {
                            previewGenerator.generatePreview(tmpFile.toPath(), previewTmp.toPath());
                            String baseNoExt = finalBaseName.contains(".")
                                    ? finalBaseName.substring(0, finalBaseName.lastIndexOf('.'))
                                    : finalBaseName;
                            try (InputStream inPrev = new FileInputStream(previewTmp)) {
                                storage.save(inPrev, previewTmp.length(),
                                        baseNoExt + ".mp3", KITS_PREVIEWS, "audio/mpeg");
                            }
                        } catch (Exception e) {
                            System.err.println("Preview generation failed for " + finalBaseName + ": " + e.getMessage());
                        } finally {
                            previewTmp.delete();
                            tmpFile.delete();
                        }
                    });
                    return null;
                });
            }
        }

        // Run all file processing tasks in parallel
        try {
            List<Future<Void>> futures = processingExecutor.invokeAll(tasks);
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    System.err.println("Error in kit task: " + e.getCause().getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Kit upload interrupted", e);
        }

        if (saved.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ZIP contains no usable files");
        }

        k.setFilePaths(saved);
        k.setDurationInSeconds((int) Math.min(Integer.MAX_VALUE, totalDurationSec.get()));

        deriveCounts(k);
        return kitRepository.save(k);
    }

    /* ===================== helpers ===================== */

    private static boolean looksLikeZip(MultipartFile zip) {
        String name = zip.getOriginalFilename();
        String ct   = zip.getContentType();
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String c = ct   == null ? "" : ct.toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || c.contains("zip");
    }

    private static boolean isMacJunk(String path) {
        String n = path == null ? "" : path.replace('\\','/').toLowerCase(Locale.ROOT);
        String base = baseName(n);
        return n.startsWith("__macosx/") || base.startsWith("._") || base.equals(".ds_store");
    }

    private static String baseName(String path) {
        if (path == null) return "";
        String n = path.replace('\\','/');
        int i = n.lastIndexOf('/');
        return (i >= 0) ? n.substring(i + 1) : n;
    }

    // keep *real audio* only; do NOT count MIDI as audio
    private static boolean isAudio(String name, String ctype) {
        String n  = name  == null ? "" : name.toLowerCase(Locale.ROOT);
        String ct = ctype == null ? "" : ctype.toLowerCase(Locale.ROOT);

        if (n.endsWith(".wav") || n.endsWith(".aif") || n.endsWith(".aiff")
                || n.endsWith(".mp3") || n.endsWith(".flac")
                || n.endsWith(".ogg") || n.endsWith(".m4a")
                || n.endsWith(".aac")) {
            return true;
        }
        // Generic audio/* but exclude MIDI explicitly
        return ct.startsWith("audio/") && !ct.contains("midi");
    }

    private static String guessContentType(String filename) {
        String n = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (n.endsWith(".wav"))  return "audio/wav";
        if (n.endsWith(".aif") || n.endsWith(".aiff")) return "audio/aiff";
        if (n.endsWith(".mp3"))  return "audio/mpeg";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".ogg"))  return "audio/ogg";
        if (n.endsWith(".m4a"))  return "audio/mp4";
        if (n.endsWith(".aac"))  return "audio/aac";
        if (n.endsWith(".mid") || n.endsWith(".midi")) return "audio/midi"; // will be routed to kits/files
        // common docs/presets/project files (saved under kits/files)
        if (n.endsWith(".txt"))  return "text/plain";
        if (n.endsWith(".pdf"))  return "application/pdf";
        if (n.endsWith(".zip"))  return "application/zip";
        return "application/octet-stream";
    }

    private static String safeName(String name) {
        if (name == null) return "file";
        // keep letters, numbers, dot, dash, underscore; replace others
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }


    @Transactional
    public void deleteKit(Long kitId, User caller) throws IOException {
        Kit k = kitRepository.findById(kitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));
        if (!k.getOwner().getId().equals(caller.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your kit");
        }
        if (k.getCoverImagePath() != null && !k.getCoverImagePath().isBlank()) storage.delete(k.getCoverImagePath());
        if (k.getPreviewAudioPath() != null && !k.getPreviewAudioPath().isBlank()) storage.delete(k.getPreviewAudioPath());
        if (k.getFilePaths() != null) {
            for (String p : k.getFilePaths()) {
                if (p != null && !p.isBlank()) storage.delete(p);
            }
        }
        kitRepository.delete(k);
    }

    private static void deriveCounts(Kit k) {
        int samples = 0, loops = 0, presets = 0;
        var type = (k.getType() == null ? "" : k.getType()).toLowerCase();

        for (String p : k.getFilePaths()) {
            String n = p == null ? "" : p.toLowerCase();
            if (n.endsWith(".wav") || n.endsWith(".aif") || n.endsWith(".aiff")
                    || n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".ogg")) {
                if (type.contains("loop")) loops++; else samples++;
            } else if (n.endsWith(".fxp") || n.endsWith(".fst") || n.endsWith(".syx")
                    || n.endsWith(".vstpreset") || n.endsWith(".aupreset") || n.endsWith(".xpand2")
                    || n.endsWith(".mid") || n.endsWith(".midi")) {
                presets++;
            }
        }
        k.setSamplesCount(samples);
        k.setLoopsCount(loops);
        k.setPresetsCount(presets);
    }

    public static String toPublicUrl(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return null;
        String s = keyOrUrl.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) return s;
        return s; // e.g. "app-prod/kits/audio/uuid.wav"
    }

    public List<String> listKitFiles(Kit kit) {
        if (kit == null) return List.of();

        // 1) Direct collections
        for (String mName : List.of("getFilePaths", "getFiles")) {
            try {
                Method m = kit.getClass().getMethod(mName);
                Object val = m.invoke(kit);
                if (val instanceof Collection<?> coll && !coll.isEmpty()) {
                    return coll.stream().filter(Objects::nonNull)
                            .map(String::valueOf).collect(Collectors.toList());
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                // ignore and keep trying others
            }
        }

        // 2) JSON columns
        for (String mName : List.of("getFilePathsJson", "getFilesJson")) {
            try {
                Method m = kit.getClass().getMethod(mName);
                Object val = m.invoke(kit);
                if (val instanceof String s && !s.isBlank()) {
                    List<String> list = objectMapper.readValue(
                            s, new TypeReference<List<String>>() {});
                    if (list != null && !list.isEmpty()) return list;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                // ignore and fallback
            }
        }

        // Nothing discoverable
        return List.of();
    }

    public List<KitSummaryDto> getFeaturedKitsFromPromotions(int offset, int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        int safeOffset = Math.max(0, offset);
        Instant now = Instant.now();

        // 1) Fetch active KIT promotions, already ordered by tier desc, start_date desc
        List<Promotion> allPromos = promotionRepository.findActiveByTypeOrdered(
                Promotion.TargetType.KIT.name(),
                now,
                PageRequest.of(0, Integer.MAX_VALUE) // fetch all and apply pagination manually
        );

        // 2) Apply manual pagination
        List<Promotion> paginatedPromos = allPromos.stream()
                .skip(safeOffset)
                .limit(safeLimit)
                .toList();

        if (paginatedPromos.isEmpty()) return List.of();

        // 3) Extract kit IDs
        List<Long> kitIds = paginatedPromos.stream()
                .map(Promotion::getTargetId)
                .toList();

        // 4) Load kits and keep only published ones
        List<Kit> kits = kitRepository.findAllById(kitIds).stream()
                .filter(k -> "published".equalsIgnoreCase(k.getStatus()))
                .toList();

        // 5) Map promo by kit ID (first promo wins if duplicates)
        Map<Long, Promotion> promoByKitId = paginatedPromos.stream()
                .collect(Collectors.toMap(Promotion::getTargetId, p -> p, (a, b) -> a));

        // 6) Maintain promo order
        Map<Long, Kit> kitById = kits.stream().collect(Collectors.toMap(Kit::getId, k -> k));
        List<Kit> ordered = kitIds.stream()
                .map(kitById::get)
                .filter(Objects::nonNull)
                .toList();

        var currentUser = currentUserService.getCurrentUserOrNull();

        // 7) Map to DTOs with earnings and sales
        return ordered.stream()
                .map(kit -> {
                    Object[] stats = purchaseRepository.getKitSalesAndEarnings(kit.getId());

                    int sales = 0;
                    BigDecimal earnings = BigDecimal.ZERO;

                    if (stats != null && stats.length == 2) {
                        if (stats[0] instanceof Number s) sales = s.intValue();
                        if (stats[1] instanceof BigDecimal bd) earnings = bd;
                        else if (stats[1] instanceof Number n) earnings = BigDecimal.valueOf(n.doubleValue());
                    }

                    return KitSummaryDto.from(
                            kit,
                            promoByKitId.get(kit.getId()),
                            earnings,
                            sales,
                            kit.getDurationInSeconds(),
                            currentUser
                    );
                })
                .toList();
    }

    /* ===== NEW ===== */
    public List<Kit> getNewKitsByOwner(Long ownerId, Pageable pageable) {
        int pageSize = pageable.getPageSize();
        int internalLimit = pageSize * 3;
        Instant cutoff = Instant.now().minus(Duration.ofDays(NEW_WINDOW_DAYS));

        // 1) Get recent kits by owner (published only)
        Pageable expandedPage = PageRequest.of(0, internalLimit);
        var recent = repo.findByOwnerIdAndStatusOrderByCreatedAtDesc(ownerId, PUBLISHED, expandedPage)
                .stream()
                .filter(k -> k.getCreatedAt() != null && !k.getCreatedAt().isBefore(cutoff))
                .toList();

        // 2) If not enough, fallback to top up with older published (no duplicates)
        if (recent.size() < (pageable.getPageNumber() + 1) * pageSize) {
            var fallback = repo.findByOwnerIdAndStatusOrderByCreatedAtDesc(ownerId, PUBLISHED, expandedPage);

            // Use LinkedHashMap to preserve order and uniqueness
            Map<Long, Kit> merged = new LinkedHashMap<>();
            for (Kit k : recent) merged.put(k.getId(), k);
            for (Kit k : fallback) {
                if (!merged.containsKey(k.getId())) merged.put(k.getId(), k);
            }

            // Apply pagination manually
            return merged.values().stream()
                    .skip((long) pageable.getPageNumber() * pageable.getPageSize())
                    .limit(pageable.getPageSize())
                    .toList();
        }

        // Enough results: just apply pagination to the recent results
        return recent.stream()
                .skip((long) pageable.getPageNumber() * pageable.getPageSize())
                .limit(pageable.getPageSize())
                .toList();
    }

    /* ===== POPULAR ===== */

    public List<Kit> getPopularKitsByOwner(Long ownerId, Pageable pageable) {
        return getPopularKitsByOwner(ownerId, pageable, POPULAR_WINDOW_DAYS);
    }

    private List<Kit> getPopularKitsByOwner(Long ownerId, Pageable pageable, int windowDays) {
        Instant now = Instant.now();
        Instant evalCutoff   = now.minus(Duration.ofDays(Math.max(1, windowDays)));
        Instant maxAgeCutoff = now.minus(Duration.ofDays(POPULAR_MAX_AGE_DAYS));

        // Use default page size if pageable is unpaged
        int pageSize = (pageable == null || pageable.isUnpaged()) ? 20 : pageable.getPageSize();
        int pageNumber = (pageable == null || pageable.isUnpaged()) ? 0 : pageable.getPageNumber();

        // Pull a bigger slice internally so we can apply filtering
        int internalLimit = pageSize * 3;
        Pageable expandedPage = PageRequest.of(0, internalLimit);

        var base = repo.findOwnerPopularSince(ownerId, evalCutoff, expandedPage);

        var filtered = base.stream()
                .filter(k -> k.getCreatedAt() != null && !k.getCreatedAt().isBefore(maxAgeCutoff))
                .filter(k -> {
                    long plays = k.getPlayCount();
                    int likes = k.getLikeCount();
                    long score = plays + 3L * likes;

                    boolean meetsFloors = (plays >= POPULAR_MIN_PLAYS) || (likes >= POPULAR_MIN_LIKES);
                    return meetsFloors && score >= POPULAR_MIN_SCORE;
                })
                .sorted((a, b) -> {
                    long scoreA = a.getPlayCount() + 3L * a.getLikeCount();
                    long scoreB = b.getPlayCount() + 3L * b.getLikeCount();
                    return Long.compare(scoreB, scoreA); // descending
                })
                .skip((long) pageNumber * pageSize)
                .limit(pageSize)
                .toList();

        if (!filtered.isEmpty() || windowDays >= 365) return filtered;

        // fallback widening, with hard cap
        return getPopularKitsByOwner(ownerId, pageable, Math.min(windowDays * 2, 365));
    }

    /* ===== TRENDING ===== */
    public List<Kit> getTrendingKitsByOwner(Long ownerId, Pageable pageable) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofDays(TRENDING_POOL_DAYS));

        // Internally fetch a large pool to rank and filter, regardless of requested page
        int internalLimit = pageable.getPageSize() * 5;
        Pageable internalPage = PageRequest.of(0, internalLimit);

        var pool = repo.findOwnerTrendingPool(ownerId, cutoff, internalPage);
        if (pool.isEmpty()) return List.of();

        // Filter + sort based on trending logic
        List<Kit> sorted = pool.stream()
                .filter(k -> k.getPlayCount() >= TRENDING_MIN_PLAYS || k.getLikeCount() >= TRENDING_MIN_LIKES)
                .sorted((a, b) -> {
                    double sa = trendingScore(a, now);
                    double sb = trendingScore(b, now);
                    int cmp = Double.compare(sb, sa); // descending
                    if (cmp != 0) return cmp;

                    // Tie-breaker: newest first
                    Instant ca = a.getCreatedAt();
                    Instant cb = b.getCreatedAt();
                    if (cb == null && ca == null) return 0;
                    if (cb == null) return -1;
                    if (ca == null) return 1;
                    return cb.compareTo(ca);
                })
                .toList();

        // Apply final pagination
        return sorted.stream()
                .skip((long) pageable.getPageNumber() * pageable.getPageSize())
                .limit(pageable.getPageSize())
                .toList();
    }

    private static double trendingScore(Kit k, Instant now) {
        // momentum: plays + 3*likes  (+ optionally: + 2*downloads)
        double base = k.getPlayCount() + 3.0 * k.getLikeCount();
        // base += 2.0 * k.getDownloads(); // uncomment if you want downloads in the score

        Instant created = (k.getCreatedAt() != null) ? k.getCreatedAt() : now;
        double ageDays = Math.max(0.01, Duration.between(created, now).toHours() / 24.0);
        double decay   = Math.pow(0.5, ageDays / TRENDING_HALFLIFE_DAYS);
        return base * decay;
    }

    @Transactional
    public void incrementPlayCount(Long kitId) {
        repo.incrementPlayCount(kitId);
    }

    private String ensureUniqueKitSlug(String base) {
        if (base == null || base.isBlank()) base = "kit";
        String s = base;
        int n = 2;
        while (kitRepository.existsBySlug(s)) {
            s = base + "-" + n++;
        }
        return s;
    }

    public List<Kit> getFeaturedKitsByOwner(Long ownerId, int limit) {
        Instant now = Instant.now();
        int capped = Math.max(1, Math.min(100, limit));

        List<Long> kitIds = promotionRepository.findActiveFeaturedKitIdsByOwner(
                ownerId,
                now,
                PageRequest.of(0, capped)
        );

        return kitRepository.findAllById(kitIds).stream()
                .filter(k -> "published".equalsIgnoreCase(k.getStatus()))
                .limit(capped)
                .toList();
    }

    public int countNewKitsByOwner(Long ownerId) {
        Instant cutoff = Instant.now().minus(NEW_KIT_WINDOW_DAYS, ChronoUnit.DAYS);
        return kitRepository.countNewKitsByOwner(ownerId, cutoff);
    }

    public int countPopularKitsByOwner(Long ownerId) {
        Instant now = Instant.now();
        Instant evalCutoff   = now.minus(Duration.ofDays(POPULAR_WINDOW_DAYS));
        Instant maxAgeCutoff = now.minus(Duration.ofDays(POPULAR_MAX_AGE_DAYS));

        // Fetch a wide range to filter from
        List<Kit> base = repo.findOwnerPopularSince(ownerId, evalCutoff, PageRequest.of(0, 500)); // limit 500

        // Apply same filtering as in getPopularKitsByOwner
        return (int) base.stream()
                .filter(k -> k.getCreatedAt() != null && !k.getCreatedAt().isBefore(maxAgeCutoff))
                .filter(k -> {
                    long plays = k.getPlayCount();
                    int likes = k.getLikeCount();
                    long score = plays + 3L * likes;

                    boolean meetsFloors = (plays >= POPULAR_MIN_PLAYS) || (likes >= POPULAR_MIN_LIKES);
                    return meetsFloors && score >= POPULAR_MIN_SCORE;
                })
                .count();
    }

    public int countTrendingKitsByOwner(Long ownerId) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofDays(TRENDING_POOL_DAYS));

        List<Kit> base = repo.findOwnerTrendingPool(ownerId, cutoff, PageRequest.of(0, 500));

        return (int) base.stream()
                .filter(k -> k.getPlayCount() >= TRENDING_MIN_PLAYS || k.getLikeCount() >= TRENDING_MIN_LIKES)
                .count();
    }

    public List<String> findFilePathsForKit(Long kitId) {
        return kitFileRepository.findPathsByKitId(kitId);
    }

    /** Container for service-level new kits + total count. */
    public record NewKitsPage(List<KitSummaryDto> kits, int totalCount) {}

    public NewKitsPage getGlobalNewKits(int page, int limit, User currentUser) {
        int offset = page * limit;
        Instant cutoff = Instant.now().minus(Duration.ofDays(NEW_KIT_WINDOW_DAYS));

        // 1️⃣ Fetch all published kits and filter by cutoff (new kits)
        List<Kit> allPublished = repo.findByStatusIgnoreCaseOrderByCreatedAtDesc("published");

        List<Kit> recentKits = allPublished.stream()
                .filter(k -> k.getCreatedAt() != null && !k.getCreatedAt().isBefore(cutoff))
                .skip(offset)
                .limit(limit)
                .toList();

        int totalCount = (int) allPublished.stream()
                .filter(k -> k.getCreatedAt() != null && !k.getCreatedAt().isBefore(cutoff))
                .count();

        // 2️⃣ Map to KitSummaryDto using factory method (includes ownerId and uploader)
        List<KitSummaryDto> dtos = recentKits.stream()
                .map(kit -> KitSummaryDto.from(
                        kit,
                        null,   // earnings null for public
                        0,      // sales 0 for public
                        kit.getDurationInSeconds(),
                        currentUser // can be null if unauthenticated
                ))
                .toList();

        return new NewKitsPage(dtos, totalCount);
    }

    public KitService.NewKitsPage getGlobalTrendingKits(int page, int limit, User currentUser) {
        int offset = page * limit;
        Instant cutoff = Instant.now().minus(Duration.ofDays(TRENDING_POOL_DAYS));

        // 1️⃣ Fetch published kits within the cutoff window
        List<Kit> recentKits = repo.findByStatusIgnoreCaseOrderByCreatedAtDesc("published").stream()
                .filter(k -> k.getCreatedAt() != null && !k.getCreatedAt().isBefore(cutoff))
                .filter(k -> k.getPlayCount() >= TRENDING_MIN_PLAYS || k.getLikeCount() >= TRENDING_MIN_LIKES)
                .toList();

        int totalCount = recentKits.size();

        // 2️⃣ Sort by trendingScore
        List<Kit> trendingKits = recentKits.stream()
                .sorted((a, b) -> Double.compare(trendingScore(b, Instant.now()), trendingScore(a, Instant.now())))
                .skip(offset)
                .limit(limit)
                .toList();

        // 3️⃣ Map to DTOs using factory method
        List<KitSummaryDto> dtos = trendingKits.stream()
                .map(kit -> KitSummaryDto.from(
                        kit,
                        null,                   // earnings null for public
                        0,                      // sales 0 for public
                        kit.getDurationInSeconds(),
                        currentUser             // can be null for unauthenticated
                ))
                .toList();

        return new KitService.NewKitsPage(dtos, totalCount);
    }

    public NewKitsPage getGlobalPopularKits(int page, int limit, User currentUser) {
        int offset = page * limit;
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofDays(POPULAR_WINDOW_DAYS));
        Instant maxAgeCutoff = now.minus(Duration.ofDays(POPULAR_MAX_AGE_DAYS));

        // 1️⃣ Fetch kits with published status and created within max age
        List<Kit> allKits = repo.findByStatusIgnoreCaseOrderByCreatedAtDesc("published").stream()
                .filter(k -> k.getCreatedAt() != null && !k.getCreatedAt().isBefore(maxAgeCutoff))
                .filter(k -> k.getPlayCount() + 3L * k.getLikeCount() >= POPULAR_MIN_SCORE)
                .toList();

        // 2️⃣ Sort by popularity (descending)
        List<Kit> popularKits = allKits.stream()
                .sorted((a, b) -> Long.compare(
                        b.getPlayCount() + 3L * b.getLikeCount(),
                        a.getPlayCount() + 3L * a.getLikeCount()
                ))
                .skip(offset)
                .limit(limit)
                .toList();

        int totalCount = allKits.size();

        // 3️⃣ Map to DTOs using factory method
        List<KitSummaryDto> dtos = popularKits.stream()
                .map(kit -> KitSummaryDto.from(
                        kit,
                        null,                    // earnings null for public
                        0,                       // sales 0 for public
                        kit.getDurationInSeconds(),
                        currentUser              // can be null for unauthenticated
                ))
                .toList();

        return new NewKitsPage(dtos, totalCount);
    }

}
