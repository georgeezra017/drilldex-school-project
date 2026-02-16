package com.drilldex.drillbackend.user;

import com.drilldex.drillbackend.auth.CustomUserDetails;
import com.drilldex.drillbackend.beat.*;
import com.drilldex.drillbackend.kit.KitRepository;
import com.drilldex.drillbackend.pack.PackService;
import com.drilldex.drillbackend.shared.PaginatedResponse;
import com.drilldex.drillbackend.user.dto.ArtistProfileDto;
import com.drilldex.drillbackend.user.dto.ArtistWithBeatsDto;
import com.drilldex.drillbackend.user.dto.ProducerSummaryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserRepository userRepository;
    private final BeatRepository beatRepository;
    private final UserRepository users;
    private final PackService packService;
    private final KitRepository kitRepository;
    private final UserService userService;
    private final com.drilldex.drillbackend.user.CurrentUserService currentUserService;
    private final BeatService beatService;

    @PostMapping("/profile-picture")
    public ResponseEntity<?> uploadProfilePicture(
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) throws IOException {
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        User user = userDetails.getUser();

        String uploadDir = Paths.get("").toAbsolutePath() + "/uploads/profile-pictures";
        Files.createDirectories(Paths.get(uploadDir));

        String filename = UUID.randomUUID() + "-" + file.getOriginalFilename();
        Path filepath = Paths.get(uploadDir, filename);
        file.transferTo(filepath.toFile());

        user.setProfilePicturePath(filepath.toString());
        // Save update
        userRepository.save(user);

        return ResponseEntity.ok("✅ Profile picture uploaded.");
    }

    @DeleteMapping("/profile-picture")
    public ResponseEntity<?> deleteProfilePicture(Authentication authentication) throws IOException {
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        User user = userDetails.getUser();

        String path = user.getProfilePicturePath();

        if (path != null && Files.exists(Paths.get(path))) {
            Files.delete(Paths.get(path));
        }

        user.setProfilePicturePath(null);
        userRepository.save(user);

        return ResponseEntity.ok("🗑️ Profile picture deleted.");
    }


    @GetMapping("/artist/{id}")
    public ResponseEntity<ArtistWithBeatsDto> getArtistProfile(@PathVariable Long id) {
        var user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artist not found"));

        if (user.getRole() != Role.ARTIST) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This user is not an artist.");
        }

        List<BeatDto> beatDtos = beatRepository.findByOwnerId(id).stream()
                .filter(Beat::isApproved)
                .filter(b -> !b.isRejected())
                .sorted(Comparator.comparing(Beat::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(this::mapToBeatDto)
                .toList();

        ArtistWithBeatsDto profile = new ArtistWithBeatsDto(
                user.getDisplayName(),
                toPublicUrl(user.getProfilePicturePath()),
                beatDtos
        );

        return ResponseEntity.ok(profile);
    }


    private BeatDto mapToBeatDto(Beat beat) {
        User current = null;
        try {
            current = currentUserService.getCurrentUserOrNull();
        } catch (Exception ignored) {}

        return BeatMapper.mapToDto(beat, null, current, 0, BigDecimal.ZERO);
    }



    @GetMapping("/producers")
    public ResponseEntity<PaginatedResponse<ProducerSummaryDto>> listProducers(
            @RequestParam(defaultValue = "popular") String sort,
            @RequestParam(defaultValue = "60") int limit,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String q
    ) {
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        Pageable pageable = PageRequest.of(page, cappedLimit);

        Page<User> usersPage;

        if (q != null && !q.isBlank()) {
            // search by display name or email
            usersPage = userRepository.findByDisplayNameContainingIgnoreCaseOrEmailContainingIgnoreCaseAndBannedFalse(
                    q.trim(), q.trim(), pageable
            );
        } else if ("alpha".equalsIgnoreCase(sort)) {
            usersPage = userRepository.findAllByBannedFalseOrderByDisplayNameAsc(pageable);
        } else {
            // default: popular = by number of beats
            usersPage = userRepository.findAllByBannedFalse(pageable); // we'll sort manually below
        }

        long totalCount = usersPage.getTotalElements();

        List<ProducerSummaryDto> dtos = usersPage.getContent().stream()
                .map(u -> new ProducerSummaryDto(
                        u.getId(),
                        u.getDisplayName(),
                        toPublicUrl(u.getProfilePicturePath()), // keep your existing helper
                        userService.countUniqueBeatsByProducer(u.getId()), // returns 0 if none
                        users.countFollowers(u.getId())
                ))
                .sorted((a, b) -> {
                    if ("popular".equalsIgnoreCase(sort)) {
                        return Long.compare(b.beatsCount(), a.beatsCount());
                    }
                    return 0; // already sorted for alpha
                })
                .toList();

        return ResponseEntity.ok(new PaginatedResponse<>(dtos, (int) totalCount, page, cappedLimit));
    }

    /* ---- helpers ---- */
    private static String toPublicUrl(String path) {
        if (path == null || path.isBlank()) return null;
        String p = path.toLowerCase();
        if (p.startsWith("http://") || p.startsWith("https://")) return path;
        return "/uploads/" + path;
    }

    @GetMapping("/{id}/beats")
    public ResponseEntity<PaginatedResponse<BeatDto>> getUserBeats(
            @PathVariable Long id,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        // Ensure sane limits
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);

        // Fetch beats for this owner
        List<BeatDto> beats = beatRepository.findByOwnerId(id).stream()
                .filter(Beat::isApproved)
                .filter(b -> !b.isRejected())
                .sorted(Comparator.comparing(Beat::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(this::mapToBeatDto)
                .toList();

        // Calculate total for pagination
        int totalCount = beats.size();

        // Slice the page
        int from = Math.min(pageIndex * cappedLimit, beats.size());
        int to = Math.min(from + cappedLimit, beats.size());
        List<BeatDto> paginated = beats.subList(from, to);

        return ResponseEntity.ok(new PaginatedResponse<>(paginated, totalCount, pageIndex, cappedLimit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getPublicUserProfile(@PathVariable Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // 1. Standalone beats
        var standaloneBeatIds = beatRepository.findByOwnerId(id).stream()
                .filter(Beat::isApproved)
                .filter(b -> !b.isRejected())
                .map(Beat::getId)
                .collect(java.util.stream.Collectors.toSet());

// 2. Beats in packs (distinct IDs)
        var packBeatIds = packService.getDistinctBeatIdsInPacksByOwner(id); // implement this method to return Set<Long>

// 3. Merge & deduplicate
        standaloneBeatIds.addAll(packBeatIds);
        int totalTracks = standaloneBeatIds.size();

// 4. Sum plays from standalone beats
        long beatPlays = beatRepository.findAllById(standaloneBeatIds).stream()
                .mapToLong(b -> b.getPlayCount() != null ? b.getPlayCount() : 0L)
                .sum();

// 5. Add pack + kit plays
        long packPlays = packService.sumPlayCountByOwnerId(id);
        long kitPlays = kitRepository.sumPlayCountByOwnerId(id);
        long totalPlays = beatPlays + packPlays + kitPlays;

        return ResponseEntity.ok(
                new ArtistProfileDto(
                        user.getId(),
                        user.getDisplayName(),
                        toPublicUrl(user.getProfilePicturePath()),
                        toPublicUrl(user.getBannerImagePath()),
                        user.getBio(),
                        user.getInstagram(),
                        user.getTwitter(),
                        user.getYoutube(),
                        user.getFacebook(),
                        user.getSoundcloud(),
                        user.getTiktok(),
                        user.getFollowers() != null ? user.getFollowers().size() : 0,
                        (int) totalPlays,
                        totalTracks,
                        user.getPlan()
                )
        );
    }

}
