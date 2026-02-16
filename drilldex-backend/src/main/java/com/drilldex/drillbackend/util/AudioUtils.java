package com.drilldex.drillbackend.util;

import com.drilldex.drillbackend.album.Album;
import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.kit.Kit;
import com.drilldex.drillbackend.pack.Pack;
import com.drilldex.drillbackend.purchase.Purchase;
import com.mpatric.mp3agic.Mp3File;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import javax.sound.sampled.*;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;

@Slf4j
public class AudioUtils {

    public static int getDurationInSeconds(String filePath) throws Exception {
        File f = new File(filePath);
        String name = f.getName().toLowerCase();

        // 1) Prefer mp3agic for MP3
        if (name.endsWith(".mp3")) {
            try {
                Mp3File mp3 = new Mp3File(f);
                return (int)Math.round(mp3.getLengthInSeconds());
            } catch (Exception e) {
                // fall through to generic handlers if mp3agic fails
            }
        }

        // 2) Generic path: JavaSound (works for WAV/PCM and some others)
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(f)) {
            AudioFormat fmt = ais.getFormat();
            long frames = ais.getFrameLength();
            float frameRate = fmt.getFrameRate();
            if (frames > 0 && frameRate > 0) {
                return (int)Math.round(frames / frameRate);
            }
        } catch (UnsupportedAudioFileException | IOException e) {
            // fall through to try properties
        }

        // 3) Some decoders expose microseconds in properties
        try {
            AudioFileFormat aff = AudioSystem.getAudioFileFormat(f);
            Map<String, Object> props = aff.properties();
            Object dur = props.get("duration"); // microseconds
            if (dur instanceof Long d) {
                return (int)Math.round(d / 1_000_000.0);
            }
        } catch (UnsupportedAudioFileException | IOException ignored) {}

        throw new IOException("Cannot detect duration for: " + name);
    }


    public static String createAlbumZip(Album album) throws IOException {
        String zipName = "downloads/album-" + album.getId() + ".zip";

        File zipFile = new File("downloads/album-" + album.getId() + ".zip");
        File parentDir = zipFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs(); // Create the directory if it doesn't exist
        }

        List<Beat> beats = album.getBeats();
        log.info("🎵 Creating ZIP for album '{}'. Beats count: {}", album.getTitle(), beats != null ? beats.size() : "null");

        if (beats == null || beats.isEmpty()) {
            log.warn("⚠️ No beats found in album ID {} during ZIP creation", album.getId());
        }

//        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipName))) {
//            for (Beat beat : album.getBeats()) {
//                Path path = Paths.get(beat.getAudioFilePath());
//                zos.putNextEntry(new ZipEntry(path.getFileName().toString()));
//                Files.copy(path, zos);
//                zos.closeEntry();
//            }
//        }
//        return zipName;
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipName))) {
            for (Beat beat : beats) {
                String audioPath = beat.getAudioFilePath();
                log.info("📁 Adding beat to ZIP: {} (path: {})", beat.getTitle(), audioPath);

                Path path = Paths.get(audioPath);
                zos.putNextEntry(new ZipEntry(path.getFileName().toString()));
                Files.copy(path, zos);
                zos.closeEntry();
            }
        }

        log.info("✅ ZIP file created at: {}", zipName);
        return zipName;
    }


    public static String createPackZip(Pack pack) throws IOException {
        String outputDir = "packZips";
        Files.createDirectories(Paths.get(outputDir));
        String zipFilePath = outputDir + "/pack-" + pack.getId() + "-" + System.currentTimeMillis() + ".zip";

        Set<String> usedNames = new HashSet<>();

        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(zipFilePath))) {
            int index = 1;
            for (var beat : pack.getBeats()) {
                File beatFile = new File(beat.getAudioFilePath());
                if (!beatFile.exists()) continue;

                // Sanitize and make entry name unique
                String baseName = beat.getTitle().replaceAll("\\s+", "_").replaceAll("[^a-zA-Z0-9_\\-]", "");
                String entryName = baseName + ".mp3";

                // Prevent duplicates by adding an index if needed
                while (usedNames.contains(entryName)) {
                    entryName = baseName + "_" + (index++) + ".mp3";
                }
                usedNames.add(entryName);

                zipOut.putNextEntry(new ZipEntry(entryName));
                Files.copy(beatFile.toPath(), zipOut);
                zipOut.closeEntry();
            }
        }

        return zipFilePath;
    }

    public static Path saveMultipartTo(MultipartFile file, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        String safe = UUID.randomUUID() + "-" + sanitizeName(file.getOriginalFilename());
        Path dst = targetDir.resolve(safe);
        try (var in = file.getInputStream()) {
            Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        return dst;
    }

    // Extract only .mp3/.wav entries from an uploaded zip into targetDir; return the saved file paths
    public static List<Path> extractAudioFromZip(MultipartFile zip, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        List<Path> saved = new ArrayList<>();

        Path tmp = Files.createTempFile("pack-upload-", ".zip");
        try (var in = zip.getInputStream()) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tmp))) {
            for (ZipEntry e; (e = zis.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                String lower = e.getName().toLowerCase();
                if (!(lower.endsWith(".mp3") || lower.endsWith(".wav"))) continue;

                String safeName = UUID.randomUUID() + "-" + sanitizeName(Paths.get(e.getName()).getFileName().toString());
                Path dst = targetDir.resolve(safeName);
                Files.copy(zis, dst);
                saved.add(dst);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return saved;
    }

    public static String filenameSansExt(String s) {
        if (s == null) return "Untitled";
        String base = Paths.get(s).getFileName().toString();
        int i = base.lastIndexOf('.');
        return (i > 0) ? base.substring(0, i) : base;
    }

    public static String sanitizeName(String s) {
        if (s == null) return "file";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static String createKitZip(Kit kit) throws IOException {
        String outputDir = "kitZips";
        Files.createDirectories(Paths.get(outputDir));
        String zipFilePath = outputDir + "/kit-" + kit.getId() + "-" + System.currentTimeMillis() + ".zip";

        Set<String> usedNames = new HashSet<>();

        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(zipFilePath))) {
            int index = 1;

            // 1) Collect candidate items from the Kit (beats/samples/items), no assumptions
            List<Object> items = collectKitItems(kit);

            for (Object item : items) {
                // 2) Resolve a physical audio path from the item
                String pathStr = firstNonNull(
                        tryStringGetter(item, "getAudioFilePath"),
                        tryStringGetter(item, "getFilePath"),
                        tryStringGetter(item, "getPath")
                );

                if (pathStr == null || pathStr.isBlank()) continue; // skip if we can't resolve a path

                File src = new File(pathStr);
                if (!src.exists() || !src.isFile()) continue;

                // 3) Pick a clean base name (prefer item.getTitle(), otherwise filename sans ext)
                String title = tryStringGetter(item, "getTitle");
                String baseName = (title != null && !title.isBlank())
                        ? title
                        : filenameSansExt(src.getName());

                baseName = baseName.replaceAll("\\s+", "_").replaceAll("[^a-zA-Z0-9_\\-]", "");

                // 4) Extension: keep .wav if file ends with .wav, else default to .mp3
                String lower = src.getName().toLowerCase(Locale.ROOT);
                String ext = lower.endsWith(".wav") ? ".wav" : (lower.endsWith(".mp3") ? ".mp3" : ".mp3");

                String entryName = baseName + ext;
                while (usedNames.contains(entryName)) {
                    entryName = baseName + "_" + (index++) + ext;
                }
                usedNames.add(entryName);

                zipOut.putNextEntry(new ZipEntry(entryName));
                Files.copy(src.toPath(), zipOut);
                zipOut.closeEntry();
            }
        }

        return zipFilePath;
    }

    /* ============================== */
    /* Helpers for createKitZip       */
    /* ============================== */

    private static List<Object> collectKitItems(Kit kit) {
        // Try common collections on Kit: getBeats(), getSamples(), getItems()
        List<Object> items = new ArrayList<>();

        List<?> beats = tryListGetter(kit, "getBeats");
        if (beats != null) items.addAll(beats);

        List<?> samples = tryListGetter(kit, "getSamples");
        if (samples != null) items.addAll(samples);

        List<?> generic = tryListGetter(kit, "getItems");
        if (generic != null) items.addAll(generic);

        // If nothing was found, return empty list (zip would be empty)
        return items;
    }

    @SuppressWarnings("unchecked")
    private static List<?> tryListGetter(Object target, String methodName) {
        try {
            var m = target.getClass().getMethod(methodName);
            Object val = m.invoke(target);
            if (val instanceof List<?> list) return list;
        } catch (Exception ignored) {}
        return null;
    }

    private static String tryStringGetter(Object target, String methodName) {
        try {
            var m = target.getClass().getMethod(methodName);
            Object val = m.invoke(target);
            return (val instanceof String s) ? s : null;
        } catch (Exception ignored) {}
        return null;
    }

    private static String firstNonNull(String... vals) {
        for (String v : vals) if (v != null) return v;
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public static void putLocalLicenseIntoZip(String licensePdfPath, long purchaseId, ZipOutputStream zos) throws IOException {
        if (!notBlank(licensePdfPath)) return;
        File f = new File(licensePdfPath);
        if (!f.exists() || !f.isFile()) return;

        String entryName = "LICENSE-" + purchaseId + ".pdf";
        zos.putNextEntry(new ZipEntry(entryName));
        Files.copy(f.toPath(), zos);
        zos.closeEntry();
    }

    public static void streamPackZipWithLicense(
            Purchase purchase,
            String uploadRoot,
            HttpServletResponse response
    ) throws IOException {
        var pack = purchase.getPack();
        if (pack == null) throw new IOException("Purchase has no pack");

        String safePackName = sanitizeName(
                (pack.getTitle() == null || pack.getTitle().isBlank()) ? "pack" : pack.getTitle()
        );

        response.setHeader(
                "Content-Disposition",
                "attachment; filename=\"" + safePackName + "-purchase-" + purchase.getId() + ".zip\""
        );
        response.setContentType("application/zip");

        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            // 1) LICENSE (local disk)
            putLocalLicenseIntoZip(purchase.getLicensePdfPath(), purchase.getId(), zos);

            // 2) AUDIO (local) — each Beat has one master file
            if (pack.getBeats() != null) {
                for (var beat : pack.getBeats()) {
                    Path audioPath = resolveUploadPath(uploadRoot, beat.getAudioFilePath());
                    if (audioPath == null || !Files.exists(audioPath) || !Files.isRegularFile(audioPath)) continue;

                    String base = sanitizeName(
                            (beat.getTitle() == null || beat.getTitle().isBlank())
                                    ? ("beat-" + beat.getId())
                                    : beat.getTitle()
                    );
                    String ext = guessExtFromKeyOrType(audioPath.getFileName().toString(), null);
                    if (ext.isBlank()) {
                        ext = fileExt(audioPath.getFileName().toString());
                    }
                    String entryName = ext.isBlank() ? audioPath.getFileName().toString() : (base + ext);

                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(audioPath, zos);
                    zos.closeEntry();
                }
            }

            boolean requiresStems = purchase.getLicenseType() == com.drilldex.drillbackend.beat.LicenseType.PREMIUM
                    || purchase.getLicenseType() == com.drilldex.drillbackend.beat.LicenseType.EXCLUSIVE;

            String stemsPrefix = pack.getStemsFilePath();
            if (requiresStems && stemsPrefix != null && !stemsPrefix.isBlank()) {
                Path stemsDir = resolveUploadPath(uploadRoot, stemsPrefix);
                if (stemsDir != null && Files.exists(stemsDir)) {
                    addLocalFolderToZip(stemsDir, "stems", zos);
                }
            }

            zos.finish();
            response.flushBuffer();
        }
    }

    public static void streamKitZipWithLicense(
            Purchase purchase,
            List<String> kitItemPaths,
            String uploadRoot,
            HttpServletResponse response
    ) throws IOException {
        var kit = purchase.getKit();
        if (kit == null) throw new IOException("Purchase has no kit");

        String safeName = sanitizeName(
                (kit.getTitle() == null || kit.getTitle().isBlank()) ? "kit" : kit.getTitle()
        );

        List<Path> files = (kitItemPaths == null) ? List.of() :
                kitItemPaths.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .map(p -> {
                            try {
                                return resolveUploadPath(uploadRoot, p);
                            } catch (IOException e) {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .filter(Files::exists)
                        .filter(Files::isRegularFile)
                        .toList();

        if (files.isEmpty()) {
            throw new IOException("Kit has no downloadable items");
        }

        response.setHeader(
                "Content-Disposition",
                "attachment; filename=\"" + safeName + "-purchase-" + purchase.getId() + ".zip\""
        );
        response.setContentType("application/zip");

        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            putLocalLicenseIntoZip(purchase.getLicensePdfPath(), purchase.getId(), zos);

            for (Path file : files) {
                String leaf = file.getFileName().toString();
                String entryName = safeName + "/" + leaf;
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, zos);
                zos.closeEntry();
            }

            zos.finish();
        }
    }

    public static void streamBeatZipWithLicense(
            Purchase purchase,
            String uploadRoot,
            HttpServletResponse response
    ) throws IOException {
        var beat = purchase.getBeat();
        if (beat == null) throw new IOException("Purchase has no beat");

        String safeBeatName = sanitizeName(
                (beat.getTitle() == null || beat.getTitle().isBlank()) ? "beat" : beat.getTitle()
        );

        response.setHeader(
                "Content-Disposition",
                "attachment; filename=\"" + safeBeatName + "-purchase-" + purchase.getId() + ".zip\""
        );
        response.setContentType("application/zip");

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            putLocalLicenseIntoZip(purchase.getLicensePdfPath(), purchase.getId(), zos);

            Path master = resolveUploadPath(uploadRoot, beat.getAudioFilePath());
            if (master != null && Files.exists(master) && Files.isRegularFile(master)) {
                String ext = guessExtFromKeyOrType(master.getFileName().toString(), null);
                if (ext.isBlank()) {
                    ext = fileExt(master.getFileName().toString());
                }
                String entryName = ext.isBlank() ? master.getFileName().toString() : (safeBeatName + ext);
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(master, zos);
                zos.closeEntry();
            } else {
                log.warn("No main audio file found for beat {}", beat.getId());
            }

            boolean requiresStems = purchase.getLicenseType() == com.drilldex.drillbackend.beat.LicenseType.PREMIUM
                    || purchase.getLicenseType() == com.drilldex.drillbackend.beat.LicenseType.EXCLUSIVE;

            String stemsPrefix = beat.getStemsFilePath();
            if (requiresStems && stemsPrefix != null && !stemsPrefix.isBlank()) {
                Path stemsDir = resolveUploadPath(uploadRoot, stemsPrefix);
                if (stemsDir != null && Files.exists(stemsDir)) {
                    addLocalFolderToZip(stemsDir, "stems", zos);
                }
            }

            zos.finish();
            zos.flush();
            baos.flush();

            response.setContentLength(baos.size());
            try (OutputStream out = response.getOutputStream()) {
                baos.writeTo(out);
                out.flush();
            }
        } catch (Exception e) {
            log.error("Failed to create ZIP for beat {}", beat.getId(), e);
            throw new IOException("Failed to stream beat ZIP", e);
        }
    }

    public static String normalizeStorageKey(String pathOrUrl) {
        if (pathOrUrl == null) return null;
        String s = pathOrUrl.trim().replace('\\', '/');
        if (s.isBlank()) return null;

        if (s.startsWith("http://") || s.startsWith("https://")) {
            try {
                URI u = URI.create(s);
                if (u.getPath() != null) {
                    s = u.getPath();
                }
            } catch (Exception ignored) {}
        }

        int uploadsIdx = s.indexOf("/uploads/");
        if (uploadsIdx >= 0) {
            s = s.substring(uploadsIdx + "/uploads/".length());
        }

        if (s.startsWith("uploads/")) {
            s = s.substring("uploads/".length());
        }

        s = s.replaceFirst("^/+", "");
        return s.isBlank() ? null : s;
    }

    public static Path resolveUploadPath(String uploadRoot, String pathOrUrl) throws IOException {
        if (pathOrUrl == null) return null;
        String trimmed = pathOrUrl.trim();
        if (trimmed.isBlank()) return null;

        try {
            Path direct = Paths.get(trimmed);
            if (direct.isAbsolute() && Files.exists(direct)) {
                return direct.normalize();
            }
        } catch (Exception ignored) {}

        String key = normalizeStorageKey(trimmed);
        if (key == null || key.isBlank()) return null;
        Path root = Paths.get(uploadRoot).toAbsolutePath().normalize();
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Invalid storage path");
        }
        return resolved;
    }

    private static String fileExt(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return (dot >= 0) ? filename.substring(dot) : "";
    }

    private static int addLocalFolderToZip(Path folder, String folderName, ZipOutputStream zos) throws IOException {
        if (folder == null || !Files.exists(folder)) return 0;
        int added = 0;
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String leaf = p.getFileName().toString();
                if (isMacJunkName(leaf) || !isAllowedStemFile(leaf)) continue;

                long size = Files.size(p);
                if (size < 8 * 1024) continue;

                String rel = folder.relativize(p).toString().replace('\\', '/');
                String entryName = folderName + "/" + rel;
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(p, zos);
                zos.closeEntry();
                added++;
            }
        }
        return added;
    }




    public static String guessExtFromKeyOrType(String key, String contentType) {
        String lowerKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (lowerKey.endsWith(".wav")) return ".wav";
        if (lowerKey.endsWith(".mp3")) return ".mp3";
        if (lowerKey.endsWith(".zip")) return ".zip";
        if (contentType != null) {
            String ct = contentType.toLowerCase(Locale.ROOT);
            if (ct.contains("wav"))  return ".wav";
            if (ct.contains("mpeg")) return ".mp3";
            if (ct.contains("zip"))  return ".zip";
        }
        return ""; // unknown; not ideal, but won’t break
    }
    public static String sanitizeWindowsName(String s) {
        if (s == null || s.isBlank()) return "download";
        // Windows prohibits these: < > : " / \ | ? *
        String cleaned = s.replaceAll("[<>:\"/\\\\|?*]", "_");
        // remove trailing dot/space (also illegal on Windows)
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.substring(0, cleaned.length()-1);
        }
        return cleaned.isBlank() ? "download" : cleaned;
    }

    public static String rfc5987Filename(String filename) {
        return java.net.URLEncoder.encode(
                filename, java.nio.charset.StandardCharsets.UTF_8
        ).replace("+", "%20");
    }

    public static String urlEncodeRfc3986(String s) {
        if (s == null) return "";
        try {
            String encoded = URLEncoder.encode(s, "UTF-8");
            // URLEncoder encodes space as '+', RFC 3986 wants '%20'
            return encoded
                    .replace("+", "%20")
                    .replace("%21", "!")
                    .replace("%27", "'")
                    .replace("%28", "(")
                    .replace("%29", ")")
                    .replace("%7E", "~");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("UTF-8 not supported", e);
        }
    }

    public static int getDurationInSecondsFromFile(File file) throws Exception {
        String name = file.getName().toLowerCase();

        // Prefer mp3agic for MP3
        if (name.endsWith(".mp3")) {
            try {
                Mp3File mp3 = new Mp3File(file);
                return (int) Math.round(mp3.getLengthInSeconds());
            } catch (Exception ignored) {}
        }

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(file)) {
            AudioFormat fmt = ais.getFormat();
            long frames = ais.getFrameLength();
            float frameRate = fmt.getFrameRate();
            if (frames > 0 && frameRate > 0) {
                return (int) Math.round(frames / frameRate);
            }
        } catch (UnsupportedAudioFileException | IOException ignored) {}

        try {
            AudioFileFormat aff = AudioSystem.getAudioFileFormat(file);
            Map<String, Object> props = aff.properties();
            Object dur = props.get("duration"); // microseconds
            if (dur instanceof Long d) {
                return (int) Math.round(d / 1_000_000.0);
            }
        } catch (UnsupportedAudioFileException | IOException ignored) {}

        throw new IOException("Cannot detect duration for: " + name);
    }

    private static boolean isMacJunkName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals(".ds_store")
                || lower.startsWith("._")
                || lower.contains("__macosx");
    }

    private static boolean isAllowedStemFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".wav")
                || lower.endsWith(".mp3")
                || lower.endsWith(".aiff")
                || lower.endsWith(".flac");
    }
}
