// src/main/java/com/drilldex/drillbackend/beat/PreviewController.java
package com.drilldex.drillbackend.beat;

import com.drilldex.drillbackend.preview.PreviewGenerator;
import com.drilldex.drillbackend.storage.StorageService;
import com.drilldex.drillbackend.util.AudioUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/beats")
public class PreviewController {

    private final BeatRepository beats;
    private final PreviewGenerator previewGenerator;
    private final StorageService storage;

    @Value("${app.storage.local.web-base:/uploads}")
    private String webBase;

    @Value("${app.storage.provider:local}")
    private String storageProvider;

    @Value("${app.upload.root:uploads}")
    private String uploadRoot;

// In PreviewController.java

    @GetMapping("/{id}/preview-url")
    public ResponseEntity<?> getPreviewUrl(@PathVariable Long id) {
        Beat beat = beats.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found"));

        // 1) Prefer stored preview field if your Beat has one
        String previewPath = getPreviewPathIfPresent(beat);

        if (previewPath != null && !previewPath.isBlank()) {
            // If we're running local storage but the stored preview is a remote URL,
            // ignore it and derive a local preview path instead.
            if (isHttpUrl(previewPath) && "local".equalsIgnoreCase(storageProvider)) {
                previewPath = null;
            }
        }

        // If local file is missing, regenerate a preview on demand
        if ((previewPath == null || previewPath.isBlank()) && "local".equalsIgnoreCase(storageProvider)) {
            previewPath = generatePreviewForBeat(beat);
        } else if ("local".equalsIgnoreCase(storageProvider) && previewPath != null && !previewPath.isBlank()) {
            if (!localFileExists(previewPath)) {
                previewPath = generatePreviewForBeat(beat);
            }
        }

        String previewKey;
        if (previewPath != null && !previewPath.isBlank()) {
            previewKey = normalizePath(previewPath);
        } else {
            // 2) Derive from master but ALWAYS .mp3
            String stored = beat.getAudioFilePath();
            if (stored == null || stored.isBlank())
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No audio available");
            previewKey = derivePreviewKeyMp3(stored);
        }

        if (previewKey == null || previewKey.isBlank())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not available");
        String url = toPublicUrl(previewKey);
        return ResponseEntity.ok(Map.of("url", url));
    }

    /* -------- helpers -------- */

    /** If Beat has previewUrl/previewAudioPath, use it */
    private static String getPreviewPathIfPresent(Beat beat) {
        try {
            var m = beat.getClass().getMethod("getPreviewUrl");
            Object v = m.invoke(beat);
            if (v instanceof String s && !s.isBlank()) return s;
        } catch (ReflectiveOperationException ignore) {}
        try {
            var m = beat.getClass().getMethod("getPreviewAudioPath");
            Object v = m.invoke(beat);
            if (v instanceof String s && !s.isBlank()) return s;
        } catch (ReflectiveOperationException ignore) {}
        return null;
    }

    private static String normalizePath(String pathOrUrl) {
        String s = (pathOrUrl == null ? "" : pathOrUrl.trim()).replace('\\', '/');
        if (s.isBlank()) return s;
        if (s.startsWith("http://") || s.startsWith("https://")) {
            try {
                java.net.URI u = java.net.URI.create(s);
                if (u.getPath() != null) s = u.getPath();
            } catch (Exception ignored) {}
        }
        int uploadsIdx = s.indexOf("/uploads/");
        if (uploadsIdx >= 0) s = s.substring(uploadsIdx + "/uploads/".length());
        return s.replaceFirst("^/+", "");
    }

    private static boolean isHttpUrl(String s) {
        String x = s == null ? "" : s.toLowerCase();
        return x.startsWith("http://") || x.startsWith("https://");
    }

    private boolean localFileExists(String pathOrUrl) {
        try {
            Path p = AudioUtils.resolveUploadPath(uploadRoot, pathOrUrl);
            return p != null && Files.exists(p);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String generatePreviewForBeat(Beat beat) {
        try {
            Path master = AudioUtils.resolveUploadPath(uploadRoot, beat.getAudioFilePath());
            if (master == null || !Files.exists(master)) {
                return null;
            }

            Path previewTmp = Files.createTempFile("beat-preview-", ".mp3");
            try {
                previewGenerator.generatePreview(master, previewTmp);
                long size = Files.size(previewTmp);
                String stored;
                try (InputStream in = Files.newInputStream(previewTmp)) {
                    stored = storage.save(in, size, master.getFileName().toString(), "previews", "audio/mpeg");
                }
                if (stored != null && !stored.isBlank()) {
                    beat.setPreviewAudioPath(stored);
                    beats.save(beat);
                }
                return stored;
            } finally {
                try { Files.deleteIfExists(previewTmp); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to generate preview", e);
        }
    }

    private String toPublicUrl(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return null;
        String s = keyOrUrl.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) return s;
        if (s.startsWith("/uploads/")) return s;
        String base = (webBase == null ? "/uploads" : webBase).replaceAll("/+$", "");
        return base + "/" + s.replaceFirst("^/+", "");
    }

    /** Master -> "previews/<base>-preview.mp3" (force MP3) */
    private static String derivePreviewKeyMp3(String pathOrUrl) {
        String key = normalizePath(pathOrUrl);

        // If already in previews/, keep it
        if (key.toLowerCase().contains("/previews/")) return key.replaceFirst("^/+", "");

        // If stored under audio/, previews use the same randomized filename
        String normalized = key.replaceFirst("^/+", "");
        if (normalized.startsWith("audio/")) {
            return "previews/" + normalized.substring("audio/".length());
        }
        if (normalized.contains("/audio/")) {
            return normalized.replaceFirst("/audio/", "/previews/");
        }

        String fname = normalized;
        int slash = fname.lastIndexOf('/');
        if (slash >= 0) fname = fname.substring(slash + 1);
        if (fname.isBlank()) return null;

        String base = fname.replaceAll("\\.[^.]+$", ""); // drop .wav/.mp3/etc
        return "previews/" + base + "-preview.mp3"; // force .mp3
    }
}
