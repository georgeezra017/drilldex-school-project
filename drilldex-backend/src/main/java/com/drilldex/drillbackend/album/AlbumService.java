package com.drilldex.drillbackend.album;

import com.drilldex.drillbackend.album.dto.AlbumUpdateRequest;
import com.drilldex.drillbackend.album.dto.AlbumUploadRequest;
import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.beat.BeatRepository;
import com.drilldex.drillbackend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlbumService {

    private final AlbumRepository albumRepository;
    private final BeatRepository beatRepository;

//    public void uploadAlbum(AlbumUploadRequest request, User owner) throws IOException {
//        Album album = new Album();
//        album.setTitle(request.getTitle());
//        album.setDescription(request.getDescription());
//        album.setOwner(owner);
//
//        // === Create uploads/albums directory if it doesn't exist ===
//        Path uploadDir = Paths.get("uploads", "albums").toAbsolutePath();
//        if (!Files.exists(uploadDir)) {
//            Files.createDirectories(uploadDir);
//        }
//
//        // === Save cover image ===
//        if (request.getCover() != null && !request.getCover().isEmpty()) {
//            String fileName = UUID.randomUUID() + "-" + request.getCover().getOriginalFilename();
//            Path coverPath = uploadDir.resolve(fileName);
//            request.getCover().transferTo(coverPath.toFile());
//
//            album.setCoverImagePath("uploads/albums/" + fileName); // Relative path
//        }
//
//        // === Fetch and update beats ===
//        List<Beat> beats = beatRepository.findAllById(request.getBeatIdsToInclude());
//        for (Beat beat : beats) {
//            beat.setAlbum(album);
//            beat.setSample(request.getBeatIdsAsSamples().contains(beat.getId()));
//        }
//        album.setBeats(beats);
//
//        // === Save album ===
//        albumRepository.save(album);
//        beatRepository.saveAll(beats);
//
//        log.info("✅ Album '{}' uploaded successfully with {} beats", album.getTitle(), beats.size());
//
//    }

    public void uploadAlbum(AlbumUploadRequest request, User owner) throws IOException {
        Album album = new Album();
        album.setTitle(request.getTitle());
        album.setDescription(request.getDescription());
        album.setOwner(owner);

        // === Create uploads/albums directory if it doesn't exist ===
        Path uploadDir = Paths.get("uploads", "albums").toAbsolutePath();
        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
            log.info("📁 Created album upload directory at {}", uploadDir);
        }

        // === Save cover image if present ===
        if (request.getCover() != null && !request.getCover().isEmpty()) {
            String fileName = UUID.randomUUID() + "-" + request.getCover().getOriginalFilename();
            Path coverPath = uploadDir.resolve(fileName);
            request.getCover().transferTo(coverPath.toFile());

            album.setCoverImagePath("uploads/albums/" + fileName); // relative path
            log.info("🖼️ Album cover image saved at {}", album.getCoverImagePath());
        }

        // === Save album first to assign ID ===
        Album savedAlbum = albumRepository.save(album);
        log.info("💾 Album '{}' saved with ID {}", savedAlbum.getTitle(), savedAlbum.getId());

        // === Fetch selected beats ===
        List<Long> beatIds = request.getBeatIdsToInclude();
        List<Long> sampleBeatIds = request.getBeatIdsAsSamples();

        List<Beat> beats = beatRepository.findAllById(beatIds);
        log.info("🎵 Found {} beats to include in album", beats.size());

        for (Beat beat : beats) {
            beat.setAlbum(savedAlbum); // sets album_id in DB
            beat.setSample(sampleBeatIds.contains(beat.getId()));
            log.info("🔗 Linked beat '{}' (ID: {}) to album (Sample: {})", beat.getTitle(), beat.getId(), beat.isSample());
        }

        // === Persist beat changes and re-save album with beats set ===
        beatRepository.saveAll(beats);
        savedAlbum.setBeats(beats);
        albumRepository.save(savedAlbum);

        log.info("💾 All beats saved and linked to album ID {}", savedAlbum.getId());

        // === Re-fetch album to verify beats ===
        Album verifiedAlbum = albumRepository.findById(savedAlbum.getId()).orElse(null);
        if (verifiedAlbum != null) {
            List<Beat> verifiedBeats = verifiedAlbum.getBeats();
            log.info("🔍 Verified album ID {} contains {} beats after save", verifiedAlbum.getId(), verifiedBeats != null ? verifiedBeats.size() : 0);
            if (verifiedBeats != null) {
                for (Beat beat : verifiedBeats) {
                    log.info("✅ Verified beat linked: '{}' (ID: {}) - Sample: {}", beat.getTitle(), beat.getId(), beat.isSample());
                }
            }
        } else {
            log.error("❌ Failed to re-fetch album after saving. ID: {}", savedAlbum.getId());
        }

        log.info("✅ Finalized album '{}' with {} beats", savedAlbum.getTitle(), beats.size());
    }

    @Transactional
    public void updateAlbum(Long albumId, AlbumUpdateRequest request, User user) throws IOException {
        Album album = albumRepository.findById(albumId)
                .orElseThrow(() -> new IllegalArgumentException("Album not found"));

        // Only album owner can update
        if (!album.getOwner().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to update this album.");
        }

        // Update title and description
        if (request.getTitle() != null) {
            album.setTitle(request.getTitle());
        }

        if (request.getDescription() != null) {
            album.setDescription(request.getDescription());
        }

        // Update cover image if provided
        if (request.getNewCover() != null && !request.getNewCover().isEmpty()) {
            // Prepare upload directory
            Path uploadDir = Paths.get("uploads", "albums").toAbsolutePath();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            // Generate unique file name and save file
            String fileName = UUID.randomUUID() + "-" + request.getNewCover().getOriginalFilename();
            Path coverPath = uploadDir.resolve(fileName);
            request.getNewCover().transferTo(coverPath.toFile());

            // Store relative path in DB
            album.setCoverImagePath("uploads/albums/" + fileName);
        }

        // Update beats if provided
        if (request.getUpdatedBeatIdsToInclude() != null && !request.getUpdatedBeatIdsToInclude().isEmpty()) {
            List<Beat> beats = beatRepository.findAllById(request.getUpdatedBeatIdsToInclude());

            for (Beat beat : beats) {
                beat.setAlbum(album);
                beat.setSample(request.getUpdatedBeatIdsAsSamples().contains(beat.getId()));
            }

            album.setBeats(beats);
            beatRepository.saveAll(beats);

        }

        albumRepository.save(album);
        log.info("✅ Album ID {} updated successfully", albumId);

    }

    @Transactional
    public void deleteAlbum(Long albumId, User user) {
        Album album = albumRepository.findById(albumId)
                .orElseThrow(() -> new IllegalArgumentException("Album not found"));

        // Only the owner can delete the album
        if (!album.getOwner().getId().equals(user.getId())) {
            throw new SecurityException("You do not have permission to delete this album.");
        }

        // Optional: Disassociate beats from album before deletion
        for (Beat beat : album.getBeats()) {
            beat.setAlbum(null);
            beat.setSample(false);
        }

        String coverPath = album.getCoverImagePath();
        if (coverPath != null) {
            File coverFile = new File(coverPath);
            if (coverFile.exists()) {
                coverFile.delete();
            }
        }
        albumRepository.delete(album);
    }
}