// src/main/java/com/drilldex/drillbackend/purchase/PurchaseController.java
package com.drilldex.drillbackend.purchase;

import com.drilldex.drillbackend.auth.CustomUserDetails;
import com.drilldex.drillbackend.beat.BeatRepository;
import com.drilldex.drillbackend.beat.LicenseType;
import com.drilldex.drillbackend.kit.KitRepository;
import com.drilldex.drillbackend.kit.KitService;
import com.drilldex.drillbackend.notification.NotificationService;
import com.drilldex.drillbackend.pack.Pack;
import com.drilldex.drillbackend.pack.PackRepository;
import com.drilldex.drillbackend.purchase.dto.*;

import com.drilldex.drillbackend.subscription.Subscription;
import com.drilldex.drillbackend.subscription.SubscriptionRepository;
import com.drilldex.drillbackend.user.CurrentUserService;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import com.drilldex.drillbackend.util.AudioUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/purchases")
@RequiredArgsConstructor
@Slf4j
public class PurchaseController {

    private final PurchaseService purchaseService;
    private final PurchaseRepository purchaseRepo;
    private final KitService kitService;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepo;
    private final CurrentUserService currentUserService;

    private final BeatRepository beatRepo;
    private final PackRepository packRepo;
    private final KitRepository kitRepo;


    private static final String PLACEHOLDER_IMG = "/placeholder-cover.jpg";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Value("${app.upload.root:uploads}")
    private String uploadRoot;

    @Value("${app.licenses.dir:licenses}")
    private String licensesDir;

    @GetMapping("/{id}/license")
    public ResponseEntity<FileSystemResource> downloadLicense(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        User buyer = principal.getUser();
        Purchase p = purchaseService.getPurchaseOwned(buyer, id);

        File file = resolveLicenseFile(p.getLicensePdfPath());
        if (!file.exists()) return ResponseEntity.notFound().build();

        FileSystemResource res = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"license-" + id + ".pdf\"")
                .body(res);
    }

    private File resolveLicenseFile(String path) {
        if (path == null || path.isBlank()) return new File("missing");
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        if (p.isAbsolute()) return p.toFile();

        java.nio.file.Path base = java.nio.file.Paths.get(licensesDir).toAbsolutePath().normalize();
        // If path already starts with "licenses/", avoid duplicating the folder
        if (p.getNameCount() > 1 && p.getName(0).toString().equalsIgnoreCase(base.getFileName().toString())) {
            return base.resolve(p.subpath(1, p.getNameCount())).toFile();
        }
        return base.resolve(p).toFile();
    }

    // PurchaseController.java
    public record BuyPackRequest(Long packId, LicenseType licenseType) {}


    @GetMapping("/packs/{purchaseId}/download")
    public void downloadPurchasedPackZip(@PathVariable Long purchaseId,
                                         Authentication authentication,
                                         HttpServletResponse response) {
        Purchase purchase = purchaseRepo.findById(purchaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found"));
        if (purchase.getPack() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This purchase is not a pack");

        String requester = authentication.getName();
        boolean isBuyer = purchase.getBuyer().getEmail().equalsIgnoreCase(requester);
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isBuyer && !isAdmin)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");

        try {
            AudioUtils.streamPackZipWithLicense(purchase, uploadRoot, response);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download pack", e);
        }
    }

    // PurchaseController.java


    // PurchaseController.java (kits download)
    @GetMapping("/kits/{purchaseId}/download")
    public void downloadPurchasedKitZip(@PathVariable Long purchaseId,
                                        Authentication authentication,
                                        jakarta.servlet.http.HttpServletResponse response) {
        // guards
        Purchase purchase = purchaseRepo.findById(purchaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found"));
        if (purchase.getKit() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This purchase is not a kit");

        String requester = authentication.getName();
        boolean isBuyer = purchase.getBuyer().getEmail().equalsIgnoreCase(requester);
        boolean isAdmin  = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isBuyer && !isAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");

        Long kitId = purchase.getKit().getId();

        List<String> rawPaths = kitService.findFilePathsForKit(kitId);
        List<String> keys = rawPaths == null ? List.of() : rawPaths.stream()
                .map(AudioUtils::normalizeStorageKey)
                .filter(Objects::nonNull)
                .filter(p -> !p.isBlank())
                .toList();

        if (keys.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit has no downloadable items");
        }

        try {
            AudioUtils.streamKitZipWithLicense(purchase, keys, uploadRoot, response);
            response.flushBuffer();
        } catch (ResponseStatusException e) {
            throw e; // pre-commit errors (like "no items")
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download kit", e);
        }
    }

    // request DTO
    record BuyKitRequest(Long kitId) {}

    @GetMapping("/beats/{purchaseId}/download")
    public void downloadPurchasedBeat(
            @PathVariable Long purchaseId,
            @RequestParam(defaultValue = "auto") String format, // "auto" | "mp3" | "wav"
            Authentication authentication,
            HttpServletResponse response
    ) {
        // 1) Load + guards
        Purchase purchase = purchaseRepo.findById(purchaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found"));
        if (purchase.getBeat() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This purchase is not a beat");
        }

        // 2) Auth: only buyer or admin
        String requester = authentication.getName();
        boolean isBuyer = purchase.getBuyer().getEmail().equalsIgnoreCase(requester);
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isBuyer && !isAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");

        // 3) Entitlements
        String licenseType = String.valueOf(purchase.getLicenseType()); // MP3 | WAV | ...
        boolean allowWav = !"MP3".equalsIgnoreCase(licenseType);
        boolean wantWav  = "wav".equalsIgnoreCase(format);
        boolean wantMp3  = "mp3".equalsIgnoreCase(format);

        var beat = purchase.getBeat();
        String safeTitle = AudioUtils.sanitizeName(
                (beat.getTitle() == null || beat.getTitle().isBlank()) ? "beat" : beat.getTitle()
        );

        // 4) Resolve local master path
        Path master;
        try {
            master = AudioUtils.resolveUploadPath(uploadRoot, beat.getAudioFilePath());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Master file not available");
        }
        if (master == null || !Files.exists(master)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Master file not available");
        }

        // 5) Detect extension/content-type via filename/probe
        String ext = AudioUtils.guessExtFromKeyOrType(master.getFileName().toString(), null);
        boolean storedIsWav = ".wav".equalsIgnoreCase(ext);
        boolean storedIsMp3 = ".mp3".equalsIgnoreCase(ext);

        String contentTypeDetected = null;
        try {
            contentTypeDetected = Files.probeContentType(master);
        } catch (IOException ignored) {}
        if (contentTypeDetected == null) {
            contentTypeDetected = safeContentTypeFromKey(master.getFileName().toString());
        }

        // 6) Enforce license vs request
        if (storedIsWav && !allowWav) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "WAV not allowed for this license");
        }
        if (wantWav && !storedIsWav) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Requested WAV, but master is MP3");
        }
        if (wantMp3 && !storedIsMp3) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Requested MP3, but master is WAV");
        }
        // format=auto → serve what’s stored

        // 7) Headers — use the local filename
        String leaf = AudioUtils.sanitizeWindowsName(master.getFileName().toString());
        if (!leaf.contains(".") && (storedIsMp3 || storedIsWav)) {
            leaf = leaf + (storedIsWav ? ".wav" : ".mp3");
        }

        try {
            response.setContentLengthLong(Files.size(master));
        } catch (IOException ignored) {}

        // set content-type + both filename and filename*
        response.setContentType(contentTypeDetected);
        response.setHeader(
                "Content-Disposition",
                "attachment; filename=\"" + leaf.replace("\"", "") + "\"; filename*=UTF-8''" + AudioUtils.rfc5987Filename(leaf)
        );

// EXPOSE headers for the browser (important if API and web are different origins)
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition,Content-Type,Content-Length");
        // 8) Stream from local storage
        boolean hasStems = beat.getStemsFilePath() != null && !beat.getStemsFilePath().isBlank();
        boolean requiresStems = purchase.getLicenseType() == LicenseType.PREMIUM
                || purchase.getLicenseType() == LicenseType.EXCLUSIVE;

        if (hasStems && requiresStems) {
            try {
                AudioUtils.streamBeatZipWithLicense(purchase, uploadRoot, response);
                return;
            } catch (Exception e) {
                log.error("Failed to download stems ZIP for beat {}", beat.getId(), e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download beat ZIP", e);
            }
        }

        // otherwise — fallback to single audio file
        try (InputStream in = Files.newInputStream(master)) {
            in.transferTo(response.getOutputStream());
            response.flushBuffer();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download beat", e);
        }
    }

    @GetMapping("/mine")
    public List<MyPurchaseRow> listMyPurchases(
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        User me = principal.getUser();

        return purchaseRepo.findAllByBuyerIdOrderByPurchasedAtDesc(me.getId()) // 🔹 update repo too
                .stream()
                .map(p -> {
                    String type;
                    String title;
                    String img = PLACEHOLDER_IMG;
                    String audioUrl = null;
                    String zipUrl = null;

                    if (p.getBeat() != null) {
                        type = "beat";
                        var b = p.getBeat();
                        title = safe(b.getTitle(), "Untitled Beat");
                        img = firstNonBlank(
                                tryGet(b, "getCoverImagePath"),
                                tryGet(b, "getAlbumCoverUrl"),
                                img
                        );
                        audioUrl = "/api/purchases/beats/" + p.getId() + "/download";
                    } else if (p.getPack() != null) {
                        type = "pack";
                        var pack = p.getPack();
                        title = safe(pack.getTitle(), "Pack");
                        img = firstNonBlank(
                                tryGet(pack, "getCoverImagePath"),
                                tryGet(pack, "getImageUrl"),
                                img
                        );
                        zipUrl = "/api/purchases/packs/" + p.getId() + "/download";
                    } else if (p.getKit() != null) {
                        type = "kit";
                        var kit = p.getKit();
                        title = safe(kit.getTitle(), "Kit");
                        img = firstNonBlank(
                                tryGet(kit, "getCoverImagePath"),
                                tryGet(kit, "getImageUrl"),
                                img
                        );
                        zipUrl = "/api/purchases/kits/" + p.getId() + "/download";
                    } else {
                        type = "item";
                        title = "Purchase " + p.getId();
                    }

                    String licenseUrl = "/api/purchases/" + p.getId() + "/license";
                    String purchasedAtIso = p.getPurchasedAt() != null
                            ? p.getPurchasedAt().toString() // ISO-8601 from Instant
                            : null;

                    Long sourceId = null;
                    if (p.getBeat() != null) {
                        sourceId = p.getBeat().getId();
                    } else if (p.getPack() != null) {
                        sourceId = p.getPack().getId();
                    } else if (p.getKit() != null) {
                        sourceId = p.getKit().getId();
                    }

                    return new MyPurchaseRow(
                            p.getId(),
                            type,
                            title,
                            img,
                            licenseUrl,
                            audioUrl,
                            zipUrl,
                            purchasedAtIso,
                            p.getPricePaid(),               // ← BigDecimal (nullable)
                            p.getCurrency() != null ? p.getCurrency() : "USD", // ← fallback
                            sourceId
                    );
                })
                .toList();
    }

    // ---- tiny helpers to avoid reflection boilerplate in your entities ----
    private static String safe(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
    private static String tryGet(Object bean, String method) {
        try {
            var m = bean.getClass().getMethod(method);
            Object v = m.invoke(bean);
            return (v instanceof String s && !s.isBlank()) ? s : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    // ====== helpers (place anywhere inside PurchaseController class) ======
    private static boolean isPlayable(String p) {
        if (p == null) return false;
        String lo = p.toLowerCase(Locale.ROOT);
        return lo.endsWith(".mp3") || lo.endsWith(".wav") || lo.endsWith(".aiff") || lo.endsWith(".flac");
    }
    private static String leafName(String keyOrUrl) {
        if (keyOrUrl == null) return "audio";
        int q = keyOrUrl.lastIndexOf('/');
        return q >= 0 ? keyOrUrl.substring(q + 1) : keyOrUrl;
    }
    private static String safeContentTypeFromKey(String key) {
        if (key == null) return "application/octet-stream";
        String lo = key.toLowerCase(Locale.ROOT);
        if (lo.endsWith(".mp3")) return "audio/mpeg";
        if (lo.endsWith(".wav")) return "audio/wav";
        if (lo.endsWith(".aiff")) return "audio/aiff";
        if (lo.endsWith(".flac")) return "audio/flac";
        return "application/octet-stream";
    }
    private void streamFromFileWithRange(Path file, String contentType, String rangeHeader, HttpServletResponse response) {
        try {
            long total = Files.size(file);

            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("Access-Control-Expose-Headers",
                    "Content-Type,Content-Length,Accept-Ranges,Content-Range,Content-Disposition");

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-", 2);
                long start = Long.parseLong(parts[0]);
                long end = (parts.length > 1 && !parts[1].isBlank()) ? Long.parseLong(parts[1]) : total - 1;
                if (end >= total) end = total - 1;
                if (start > end || start >= total) {
                    throw new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "Bad Range");
                }

                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                response.setContentType(contentType);
                response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + total);
                response.setContentLengthLong(end - start + 1);

                try (var raf = new java.io.RandomAccessFile(file.toFile(), "r");
                     var out = response.getOutputStream()) {
                    raf.seek(start);
                    long remaining = end - start + 1;
                    byte[] buffer = new byte[8192];
                    while (remaining > 0) {
                        int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read == -1) break;
                        out.write(buffer, 0, read);
                        remaining -= read;
                    }
                    out.flush();
                }
            } else {
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType(contentType);
                response.setContentLengthLong(total);
                response.setHeader("Content-Disposition",
                        "inline; filename*=UTF-8''" + AudioUtils.rfc5987Filename(file.getFileName().toString()));
                try (InputStream in = Files.newInputStream(file)) {
                    in.transferTo(response.getOutputStream());
                    response.flushBuffer();
                }
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Streaming failed", e);
        }
    }

    @GetMapping("/beats/{purchaseId}/stream")
    public void streamPurchasedBeat(
            @PathVariable Long purchaseId,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            Authentication authentication,
            HttpServletResponse response
    ) {
        Purchase purchase = purchaseRepo.findById(purchaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found"));
        if (purchase.getBeat() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This purchase is not a beat");

        // auth: buyer or admin
        String requester = authentication.getName();
        boolean isBuyer = purchase.getBuyer().getEmail().equalsIgnoreCase(requester);
        boolean isAdmin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isBuyer && !isAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");

        // enforce MP3-only license vs WAV masters
        String licenseType = String.valueOf(purchase.getLicenseType());
        boolean allowWav = !"MP3".equalsIgnoreCase(licenseType);

        var beat = purchase.getBeat();
        String stored = beat.getAudioFilePath();
        Path master;
        try {
            master = AudioUtils.resolveUploadPath(uploadRoot, stored);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Master not available");
        }
        if (master == null || !Files.exists(master)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Master not available");
        }

        String ext = AudioUtils.guessExtFromKeyOrType(master.getFileName().toString(), null);
        boolean isWav = ".wav".equalsIgnoreCase(ext);
        if (isWav && !allowWav) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "WAV not allowed for this license");

        String contentType = null;
        try {
            contentType = Files.probeContentType(master);
        } catch (IOException ignored) {}
        if (contentType == null) contentType = safeContentTypeFromKey(master.getFileName().toString());

        streamFromFileWithRange(master, contentType, rangeHeader, response);
    }

    record KitTrackRow(String name, String key, String streamUrl, Long sizeBytes) {}

    @GetMapping("/kits/{purchaseId}/tracks")
    public List<KitTrackRow> listKitTracks(
            @PathVariable Long purchaseId,
            Authentication authentication
    ) {
        Purchase purchase = purchaseRepo.findById(purchaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found"));
        if (purchase.getKit() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This purchase is not a kit");

        String requester = authentication.getName();
        boolean isBuyer = purchase.getBuyer().getEmail().equalsIgnoreCase(requester);
        boolean isAdmin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isBuyer && !isAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");

        List<String> rawPaths = kitService.findFilePathsForKit(purchase.getKit().getId());
        if (rawPaths == null) rawPaths = List.of();

        return rawPaths.stream()
                .map(AudioUtils::normalizeStorageKey)
                .filter(Objects::nonNull)
                .filter(p -> !p.isBlank() && isPlayable(p))
                .sorted((a, b) -> leafName(a).compareToIgnoreCase(leafName(b)))
                .map(k -> {
                    Long size = null;
                    try {
                        Path p = AudioUtils.resolveUploadPath(uploadRoot, k);
                        if (p != null && Files.exists(p)) size = Files.size(p);
                    } catch (Exception ignored) {}
                    String url = "/api/purchases/kits/" + purchaseId + "/stream?key=" + AudioUtils.urlEncodeRfc3986(k);
                    return new KitTrackRow(leafName(k), k, url, size);
                })
                .toList();
    }

    @GetMapping("/kits/{purchaseId}/stream")
    public void streamKitTrack(
            @PathVariable Long purchaseId,
            @RequestParam String key,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            Authentication authentication,
            HttpServletResponse response
    ) {
        Purchase purchase = purchaseRepo.findById(purchaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found"));
        if (purchase.getKit() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This purchase is not a kit");

        String requester = authentication.getName();
        boolean isBuyer = purchase.getBuyer().getEmail().equalsIgnoreCase(requester);
        boolean isAdmin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isBuyer && !isAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");

        String normalizedKey = AudioUtils.normalizeStorageKey(key);
        if (normalizedKey == null || normalizedKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file key");
        }

        // only allow files that belong to this kit
        List<String> allowed = kitService.findFilePathsForKit(purchase.getKit().getId());
        boolean ok = allowed != null && allowed.stream()
                .map(AudioUtils::normalizeStorageKey)
                .filter(Objects::nonNull)
                .anyMatch(k -> k.equals(normalizedKey));
        if (!ok) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "File not in this kit");

        Path file;
        try {
            file = AudioUtils.resolveUploadPath(uploadRoot, normalizedKey);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
        if (file == null || !Files.exists(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }

        String ct = null;
        try {
            ct = Files.probeContentType(file);
        } catch (IOException ignored) {}
        if (ct == null) ct = safeContentTypeFromKey(file.getFileName().toString());

        streamFromFileWithRange(file, ct, rangeHeader, response);
    }

    record PackTrackRow(String name, String key, String streamUrl, Long sizeBytes) {}

    @GetMapping("/packs/{purchaseId}/tracks")
    public List<PackTrackRow> listPackTracks(
            @PathVariable Long purchaseId,
            Authentication authentication
    ) {
        Purchase purchase = purchaseRepo.findById(purchaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found"));
        if (purchase.getPack() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This purchase is not a pack");

        String requester = authentication.getName();
        boolean isBuyer = purchase.getBuyer().getEmail().equalsIgnoreCase(requester);
        boolean isAdmin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isBuyer && !isAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");

        Pack pack = purchase.getPack();
        var beats = pack.getBeats() == null ? List.<com.drilldex.drillbackend.beat.Beat>of() : pack.getBeats();

        List<String> keys = beats.stream()
                .map(b -> AudioUtils.normalizeStorageKey(b.getAudioFilePath()))
                .filter(Objects::nonNull)
                .filter(p -> !p.isBlank() && isPlayable(p))
                .sorted((a, b) -> leafName(a).compareToIgnoreCase(leafName(b)))
                .toList();

        return keys.stream()
                .map(k -> {
                    Long size = null;
                    try {
                        Path p = AudioUtils.resolveUploadPath(uploadRoot, k);
                        if (p != null && Files.exists(p)) size = Files.size(p);
                    } catch (Exception ignored) {}
                    String url = "/api/purchases/packs/" + purchaseId + "/stream?key=" + AudioUtils.urlEncodeRfc3986(k);
                    return new PackTrackRow(leafName(k), k, url, size);
                })
                .toList();
    }

    @GetMapping("/packs/{purchaseId}/stream")
    public void streamPackTrack(
            @PathVariable Long purchaseId,
            @RequestParam String key,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            Authentication authentication,
            HttpServletResponse response
    ) {
        Purchase purchase = purchaseRepo.findById(purchaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase not found"));
        if (purchase.getPack() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This purchase is not a pack");

        String requester = authentication.getName();
        boolean isBuyer = purchase.getBuyer().getEmail().equalsIgnoreCase(requester);
        boolean isAdmin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isBuyer && !isAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");

        String normalizedKey = AudioUtils.normalizeStorageKey(key);
        if (normalizedKey == null || normalizedKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file key");
        }

        // validate 'key' belongs to the pack's beats
        boolean allowed = false;
        var beats = purchase.getPack().getBeats();
        if (beats != null) {
            allowed = beats.stream()
                    .map(b -> AudioUtils.normalizeStorageKey(b.getAudioFilePath()))
                    .filter(Objects::nonNull)
                    .anyMatch(k -> k.equals(normalizedKey));
        }
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "File not in this pack");

        Path file;
        try {
            file = AudioUtils.resolveUploadPath(uploadRoot, normalizedKey);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
        if (file == null || !Files.exists(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }

        String ct = null;
        try {
            ct = Files.probeContentType(file);
        } catch (IOException ignored) {}
        if (ct == null) ct = safeContentTypeFromKey(file.getFileName().toString());

        streamFromFileWithRange(file, ct, rangeHeader, response);
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<OrderDto> getOrder(@PathVariable String orderId) {
        User user = currentUserService.getCurrentUserOrThrow(); // get logged-in user

        List<Purchase> purchases = purchaseRepo.findAllByOrderId(orderId);

        Optional<Subscription> subscriptionOpt = subscriptionRepo.findByUserIdAndLastOrderId(user.getId(), orderId);

        // Map purchases to DTOs, passing repositories for promotions
        List<PurchaseDto> purchaseDtos = purchases.stream()
                .map(p -> PurchaseDto.from(p, beatRepo, packRepo, kitRepo))
                .toList();

        // Map subscriptions to DTOs
        List<PurchaseDto> subscriptionDtos = subscriptionOpt.stream()
                .map(PurchaseDto::fromSubscription)
                .toList();

        List<PurchaseDto> allItems = Stream.concat(purchaseDtos.stream(), subscriptionDtos.stream())
                .toList();

        if (allItems.isEmpty()) return ResponseEntity.notFound().build();

        return ResponseEntity.ok(OrderDto.fromDtos(orderId, allItems));
    }

}
