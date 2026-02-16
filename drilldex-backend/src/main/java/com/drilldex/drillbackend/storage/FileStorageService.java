// src/main/java/com/drilldex/drillbackend/storage/FileStorageService.java
package com.drilldex.drillbackend.storage;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

@Service
public class FileStorageService {

    // configurable base dir from application.properties
    private final Path baseDir = Path.of("uploads/kits");

    public FileStorageService() throws IOException {
        Files.createDirectories(baseDir);
    }

    public String save(MultipartFile file, String prefix) throws IOException {
        if (file == null || file.isEmpty()) return null;
        String ext = getExt(file.getOriginalFilename());
        String name = (prefix != null && !prefix.isBlank() ? prefix + "-" : "")
                + UUID.randomUUID() + (ext.isBlank() ? "" : "." + ext);
        Path dest = baseDir.resolve(name).normalize();
        Files.createDirectories(dest.getParent());
        Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        // return web path that your frontend knows how to resolve
        return "/uploads/kits/" + name;
    }

    public String saveRaw(MultipartFile file) throws IOException {
        return save(file, null);
    }

    private static String getExt(String filename) {
        if (filename == null) return "";
        String f = filename.trim();
        int i = f.lastIndexOf('.');
        return (i >= 0) ? f.substring(i + 1).toLowerCase() : "";
    }
}