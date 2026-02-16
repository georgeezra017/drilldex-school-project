// src/main/java/com/drilldex/drillbackend/storage/StorageService.java
package com.drilldex.drillbackend.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

public interface StorageService {
    /** Save bytes under a logical folder (e.g. "audio", "covers", "kits") and return a *storage key* or a public URL. */
    String save(MultipartFile file, String folder) throws IOException;

    /** Optional helper if you already wrote to a temp file/stream (e.g., to probe duration). */
    String save(InputStream in, long size, String originalFilename, String folder, String contentType) throws IOException;

    /** Delete by key or URL (impl handles both). */
    default void delete(String keyOrUrl) throws IOException {}
}