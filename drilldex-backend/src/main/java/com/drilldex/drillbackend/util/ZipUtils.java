package com.drilldex.drillbackend.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal ZIP helper used to iterate files inside an uploaded .zip.
 * Each entry is materialized as a small record with a name, size, contentType, and a byte[].
 * For very large zips you may want a streaming version (ask if you need that).
 */
public class ZipUtils {

    public record ZipEntryData(String name, long size, String contentType, byte[] data) {
        public InputStream stream() {
            return new ByteArrayInputStream(data);
        }
    }

    /** Read all non-directory entries from a zip MultipartFile. */
    public static Iterable<ZipEntryData> iterZip(MultipartFile zipFile) throws IOException {
        List<ZipEntryData> entries = new ArrayList<>();

        try (InputStream in = zipFile.getInputStream();
             ZipInputStream zis = new ZipInputStream(in)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                // Read the file bytes from the zip
                byte[] bytes = zis.readAllBytes();
                long size = entry.getSize() >= 0 ? entry.getSize() : bytes.length;

                String name = entry.getName();
                String contentType = guessContentType(name);

                entries.add(new ZipEntryData(name, size, contentType, bytes));
                zis.closeEntry();
            }
        }
        return entries;
    }

    private static String guessContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String f = filename.toLowerCase();
        if (f.endsWith(".wav"))  return "audio/wav";
        if (f.endsWith(".mp3"))  return "audio/mpeg";
        if (f.endsWith(".flac")) return "audio/flac";
        if (f.endsWith(".aiff") || f.endsWith(".aif")) return "audio/aiff";
        if (f.endsWith(".mid") || f.endsWith(".midi")) return "audio/midi";
        if (f.endsWith(".zip"))  return "application/zip";
        return "application/octet-stream";
    }
}