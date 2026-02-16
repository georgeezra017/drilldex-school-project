// src/main/java/com/drilldex/drillbackend/storage/LocalStorageService.java
package com.drilldex.drillbackend.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private final Path rootDir;       // e.g. /var/app/uploads
    private final String webBase;     // e.g. /uploads

    public LocalStorageService(
            @Value("${app.upload.root:uploads}") String uploadRoot,
            @Value("${app.storage.local.web-base:/uploads}") String webBase
    ) throws IOException {
        this.rootDir = Path.of(uploadRoot).toAbsolutePath().normalize();
        this.webBase = webBase.replaceAll("/+$","");
        Files.createDirectories(this.rootDir);
    }

    @Override
    public String save(MultipartFile file, String folder) throws IOException {
        return save(file.getInputStream(), file.getSize(), file.getOriginalFilename(), folder, file.getContentType());
    }

    @Override
    public String save(InputStream in, long size, String originalFilename, String folder, String contentType) throws IOException {
        String ext = ext(originalFilename);
        String name = UUID.randomUUID() + (ext.isBlank() ? "" : "." + ext);
        String safeFolder = (folder == null || folder.isBlank()) ? "misc" : folder;

        Path target = rootDir.resolve(safeFolder).resolve(name).normalize();
        Files.createDirectories(target.getParent());
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);

        // Return *storage key* like "audio/uuid.mp3" — your mappers can turn that into "/uploads/audio/..."
        return safeFolder + "/" + name;
    }

    @Override
    public void delete(String keyOrUrl) throws IOException {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return;

        // Accept either "/uploads/audio/..." or "audio/..."
        String key = keyOrUrl.replaceFirst("^/?uploads/?", "");
        Path p = rootDir.resolve(key).normalize();
        Files.deleteIfExists(p);
    }

    private static String ext(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }
}