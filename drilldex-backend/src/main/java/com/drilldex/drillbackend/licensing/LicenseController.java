package com.drilldex.drillbackend.licensing;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;

@RestController
@RequestMapping("/api/licenses")
@RequiredArgsConstructor
public class LicenseController {

    private final LicenseService licenseService;

    @GetMapping("/{filename:.+}")
    public ResponseEntity<FileSystemResource> downloadLicense(@PathVariable String filename) {
        File file = licenseService.getLicenseFile(filename);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .body(new FileSystemResource(file));
    }
}
