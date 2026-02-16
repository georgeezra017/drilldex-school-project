// src/main/java/com/drilldex/drillbackend/admin/AdminMediaController.java
package com.drilldex.drillbackend.admin;

import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.beat.BeatRepository;
import com.drilldex.drillbackend.kit.Kit;
import com.drilldex.drillbackend.kit.KitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminMediaController {

    private final BeatRepository beats;
    private final KitRepository kits;

    @Value("${app.upload.root:uploads}")
    private String uploadRoot;

    @GetMapping("/beats/{id}/master-url")
    public ResponseEntity<?> getBeatMasterUrl(@PathVariable Long id) {
        Beat beat = beats.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found"));

        String stored = beat.getAudioFilePath();
        if (stored == null || stored.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No master available");
        }

        return ResponseEntity.ok(Map.of("url", toPublicUrl(stored)));
    }

    @GetMapping("/kits/{id}/master-url")
    public ResponseEntity<?> getKitMasterUrl(
            @PathVariable Long id,
            @RequestParam(name = "file", required = false) String fileParam
    ) {
        Kit kit = kits.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));

        String chosen = (fileParam != null && !fileParam.isBlank()) ? fileParam.trim() : null;

        if (chosen == null) {
            if (kit.getPreviewAudioPath() != null && !kit.getPreviewAudioPath().isBlank()) {
                chosen = kit.getPreviewAudioPath();
            } else if (kit.getFilePaths() != null && !kit.getFilePaths().isEmpty()) {
                chosen = kit.getFilePaths().get(0);
            }
        }

        if (chosen == null || chosen.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No audio available for this kit");
        }

        return ResponseEntity.ok(Map.of("url", toPublicUrl(chosen)));
    }

    private String toPublicUrl(String path) {
        if (path == null || path.isBlank()) return null;
        String p = path.trim().replace('\\', '/');
        if (p.startsWith("http://") || p.startsWith("https://")) return p;
        if (p.startsWith("/uploads/")) return p;

        // Strip absolute local root if present
        String root = (uploadRoot == null ? "" : uploadRoot).replace('\\', '/');
        if (!root.isBlank() && p.contains(root)) {
            int idx = p.indexOf(root);
            String tail = p.substring(idx + root.length());
            tail = tail.replaceFirst("^/+", "");
            return "/uploads/" + tail;
        }

        // Default to /uploads/<path>
        return "/uploads/" + p.replaceFirst("^/+", "");
    }
}
