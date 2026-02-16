package com.drilldex.drillbackend.user;// src/main/java/com/drilldex/drillbackend/me/MeController.java


import com.drilldex.drillbackend.auth.CustomUserDetails;
import com.drilldex.drillbackend.auth.JwtService;
import com.drilldex.drillbackend.beat.*;
import com.drilldex.drillbackend.kit.KitRepository;
import com.drilldex.drillbackend.kit.KitService;
import com.drilldex.drillbackend.kit.dto.KitSummaryDto;
import com.drilldex.drillbackend.me.BeatSummaryDto;
import com.drilldex.drillbackend.me.PackSummaryDto;
import com.drilldex.drillbackend.pack.Pack;
import com.drilldex.drillbackend.pack.PackLicenseRepository;
import com.drilldex.drillbackend.pack.PackRepository;
import com.drilldex.drillbackend.pack.PackService;
import com.drilldex.drillbackend.purchase.PurchaseRepository;
import com.drilldex.drillbackend.shared.PaginatedResponse;
import com.drilldex.drillbackend.storage.FileStorageService;
import com.drilldex.drillbackend.storage.StorageService;
import com.drilldex.drillbackend.subscription.Subscription;
import com.drilldex.drillbackend.subscription.SubscriptionRepository;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.dto.MeDto;
import com.drilldex.drillbackend.util.AudioUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;


import java.time.Instant;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
@Slf4j
public class MeController {

    private final BeatRepository beatRepository;
    private final PackRepository packRepository;
    private final BeatService beatService;
    private final PackService packService;
    private final KitService kitService;
    private final KitRepository kitRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final FileStorageService fileStorageService;
    private final StorageService storageService;
    private final CurrentUserService currentUserService;
    private final PurchaseRepository purchaseRepository;
    private final SubscriptionRepository subscriptionRepository;

    @Value("${frontend.base.url}")
    private String frontendBaseUrl;

    public static record BioPayload(String bio) {}


    @GetMapping("/beats")
    public ResponseEntity<PaginatedResponse<BeatSummaryDto>> myBeats(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        var principal = (CustomUserDetails) auth.getPrincipal();
        User user = principal.getUser();

        // 1. Fetch all standalone beats owned by the user
        var beats = beatRepository.findStandaloneByOwnerId(user.getId())
                .stream()
                .filter(Beat::isApproved)
                .filter(b -> !b.isRejected())
                .sorted(Comparator.comparing(Beat::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        // 2. Apply pagination
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);
        int from = Math.min(pageIndex * cappedLimit, beats.size());
        int to = Math.min(from + cappedLimit, beats.size());
        List<Beat> pagedBeats = beats.subList(from, to);

        // 3. Map to DTOs including sales/earnings and liked info
        List<BeatSummaryDto> dtos = pagedBeats.stream()
                .map(beat -> {
                    List<Object[]> rows = purchaseRepository.getBeatSalesAndEarningsForProfile(beat.getId());
                    Object[] stats = (rows != null && !rows.isEmpty()) ? rows.get(0) : null;

                    int sales = 0;
                    BigDecimal earnings = BigDecimal.ZERO;

                    if (stats != null && stats.length == 2) {
                        if (stats[0] instanceof Number n1) sales = n1.intValue();
                        if (stats[1] instanceof BigDecimal bd) earnings = bd;
                        else if (stats[1] instanceof Number n2) earnings = BigDecimal.valueOf(n2.doubleValue());
                    }

                    return BeatSummaryDto.from(beat, sales, earnings, user);
                })
                .toList();

        // 4. Wrap in PaginatedResponse
        PaginatedResponse<BeatSummaryDto> response = new PaginatedResponse<>(dtos, beats.size(), pageIndex, cappedLimit);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/packs")
    public ResponseEntity<PaginatedResponse<PackSummaryDto>> myPacks(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        var principal = (CustomUserDetails) auth.getPrincipal();
        User user = principal.getUser();

        // 1. Fetch all approved + non-rejected packs owned by the user
        var packs = packRepository.findDistinctByOwnerId(user.getId())
                .stream()
                .filter(Pack::isApproved)
                .filter(p -> !p.isRejected())
                .sorted(Comparator.comparing(Pack::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        // 2. Apply pagination manually
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);
        int from = Math.min(pageIndex * cappedLimit, packs.size());
        int to = Math.min(from + cappedLimit, packs.size());
        List<Pack> pagedPacks = packs.subList(from, to);

        // 3. Map to DTOs including sales & earnings
        List<PackSummaryDto> dtos = pagedPacks.stream()
                .map(pack -> {
                    List<Object[]> rows = purchaseRepository.getPackSalesAndEarnings(pack.getId());
                    Object[] stats = (rows != null && !rows.isEmpty()) ? rows.get(0) : null;

                    int sales = 0;
                    BigDecimal earnings = BigDecimal.ZERO;

                    if (stats != null && stats.length == 2) {
                        if (stats[0] instanceof Number n1) sales = n1.intValue();
                        if (stats[1] instanceof BigDecimal bd) earnings = bd;
                        else if (stats[1] instanceof Number n2) earnings = BigDecimal.valueOf(n2.doubleValue());
                    }

                    return PackSummaryDto.from(pack, sales, earnings, user);
                })
                .toList();

        // 4. Wrap in PaginatedResponse
        PaginatedResponse<PackSummaryDto> response = new PaginatedResponse<>(dtos, packs.size(), pageIndex, cappedLimit);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/beats/{id}")
    public ResponseEntity<?> deleteMyBeat(@PathVariable Long id, Authentication auth) {
        var principal = (CustomUserDetails) auth.getPrincipal();
        User user = principal.getUser();
        return beatRepository.findById(id)
                .filter(b -> b.getOwner().getId().equals(user.getId()))
                .map(b -> { beatRepository.delete(b); return ResponseEntity.ok().build(); })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/packs/{id}")
    public ResponseEntity<?> deleteMyPack(@PathVariable Long id, Authentication auth) {
        var principal = (CustomUserDetails) auth.getPrincipal();
        User user = principal.getUser();
        return packRepository.findById(id)
                .filter(p -> p.getOwner().getId().equals(user.getId()))
                .map(p -> { packRepository.delete(p); return ResponseEntity.ok().build(); })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/beats/featured")
    public ResponseEntity<PaginatedResponse<BeatDto>> myFeatured(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();

        // 1. Fetch featured beats
        List<Beat> beats = beatService.getFeaturedBeatsByOwner(user.getId(), limit * 5); // fetch extra for paging

        // 2. Map to DTOs including "liked" info
        List<BeatDto> dtos = beats.stream()
                .map(b -> BeatMapper.mapToDto(b, user))
                .toList();

        // 3. Apply manual pagination
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);
        int from = Math.min(pageIndex * cappedLimit, dtos.size());
        int to = Math.min(from + cappedLimit, dtos.size());
        List<BeatDto> paginated = dtos.subList(from, to);

        return ResponseEntity.ok(new PaginatedResponse<>(paginated, dtos.size(), pageIndex, cappedLimit));
    }

    @GetMapping("/beats/new")
    public ResponseEntity<PaginatedResponse<BeatDto>> myNew(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();

        List<Beat> beats = beatService.getNewBeatsByOwner(user.getId(), limit * 5);

        List<BeatDto> dtos = beats.stream()
                .map(b -> BeatMapper.mapToDto(b, user))
                .toList();

        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);
        int from = Math.min(pageIndex * cappedLimit, dtos.size());
        int to = Math.min(from + cappedLimit, dtos.size());
        List<BeatDto> paginated = dtos.subList(from, to);

        return ResponseEntity.ok(new PaginatedResponse<>(paginated, dtos.size(), pageIndex, cappedLimit));
    }

    @GetMapping("/beats/popular")
    public ResponseEntity<PaginatedResponse<BeatDto>> myPopular(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();

        List<Beat> beats = beatService.getPopularBeatsByOwner(user.getId(), limit * 5);

        List<BeatDto> dtos = beats.stream()
                .map(b -> BeatMapper.mapToDto(b, user))
                .toList();

        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);
        int from = Math.min(pageIndex * cappedLimit, dtos.size());
        int to = Math.min(from + cappedLimit, dtos.size());
        List<BeatDto> paginated = dtos.subList(from, to);

        return ResponseEntity.ok(new PaginatedResponse<>(paginated, dtos.size(), pageIndex, cappedLimit));
    }

    @GetMapping("/beats/trending")
    public ResponseEntity<PaginatedResponse<BeatDto>> myTrending(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();

        // 1. Fetch trending beats for this user (fetch extra for safe paging)
        List<Beat> beats = beatService.getTrendingBeatsByOwner(user.getId(), limit * 5);

        // 2. Map to DTOs including "liked" info
        List<BeatDto> dtos = beats.stream()
                .map(b -> BeatMapper.mapToDto(b, user))
                .toList();

        // 3. Apply manual pagination
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);
        int from = Math.min(pageIndex * cappedLimit, dtos.size());
        int to = Math.min(from + cappedLimit, dtos.size());
        List<BeatDto> paginated = dtos.subList(from, to);

        return ResponseEntity.ok(new PaginatedResponse<>(paginated, dtos.size(), pageIndex, cappedLimit));
    }

    @GetMapping("/packs/featured")
    public ResponseEntity<PaginatedResponse<PackSummaryDto>> myPacksFeatured(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        var currentUser = ((CustomUserDetails) auth.getPrincipal()).getUser();
        Long uid = currentUser.getId();

        var packs = packService.getFeaturedPacksByOwner(uid, limit * 5).stream() // fetch extra for safe paging
                .map(pack -> toPackSummaryWithStats(pack, currentUser))
                .toList();

        // Manual pagination
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);
        int from = Math.min(pageIndex * cappedLimit, packs.size());
        int to = Math.min(from + cappedLimit, packs.size());
        List<PackSummaryDto> paginated = packs.subList(from, to);

        return ResponseEntity.ok(new PaginatedResponse<>(paginated, packs.size(), pageIndex, cappedLimit));
    }

    @GetMapping("/packs/new")
    public ResponseEntity<PaginatedResponse<PackSummaryDto>> myPacksNew(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        var currentUser = ((CustomUserDetails) auth.getPrincipal()).getUser();
        Long uid = currentUser.getId();

        var packs = packService.getNewPacksByOwner(uid, limit * 5, page).stream()
                .map(pack -> toPackSummaryWithStats(pack, currentUser))
                .toList();

        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);
        int from = Math.min(pageIndex * cappedLimit, packs.size());
        int to = Math.min(from + cappedLimit, packs.size());
        List<PackSummaryDto> paginated = packs.subList(from, to);

        return ResponseEntity.ok(new PaginatedResponse<>(paginated, packs.size(), pageIndex, cappedLimit));
    }

    @GetMapping("/packs/popular")
    public ResponseEntity<PaginatedResponse<PackSummaryDto>> myPacksPopular(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        var currentUser = ((CustomUserDetails) auth.getPrincipal()).getUser();
        Long uid = currentUser.getId();

        var packs = packService.getPopularPacksByOwner(uid, limit * 5, page).stream()
                .map(pack -> toPackSummaryWithStats(pack, currentUser))
                .toList();

        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);
        int from = Math.min(pageIndex * cappedLimit, packs.size());
        int to = Math.min(from + cappedLimit, packs.size());
        List<PackSummaryDto> paginated = packs.subList(from, to);

        return ResponseEntity.ok(new PaginatedResponse<>(paginated, packs.size(), pageIndex, cappedLimit));
    }

    @GetMapping("/packs/trending")
    public ResponseEntity<PaginatedResponse<PackSummaryDto>> myPacksTrending(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        var currentUser = ((CustomUserDetails) auth.getPrincipal()).getUser();
        Long uid = currentUser.getId();

        var packs = packService.getTrendingPacksByOwner(uid, limit * 5, page).stream()
                .map(pack -> toPackSummaryWithStats(pack, currentUser))
                .toList();

        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);
        int from = Math.min(pageIndex * cappedLimit, packs.size());
        int to = Math.min(from + cappedLimit, packs.size());
        List<PackSummaryDto> paginated = packs.subList(from, to);

        return ResponseEntity.ok(new PaginatedResponse<>(paginated, packs.size(), pageIndex, cappedLimit));
    }

    private PackSummaryDto toPackSummaryWithStats(Pack pack, User user) {
        List<Object[]> rows = purchaseRepository.getPackSalesAndEarnings(pack.getId());
        Object[] stats = (rows != null && !rows.isEmpty()) ? rows.get(0) : null;

        int sales = 0;
        BigDecimal earnings = BigDecimal.ZERO;

        if (stats != null && stats.length == 2) {
            if (stats[0] instanceof Number n1) sales = n1.intValue();
            if (stats[1] instanceof BigDecimal bd) earnings = bd;
            else if (stats[1] instanceof Number n2) earnings = BigDecimal.valueOf(n2.doubleValue());
        }

        return PackSummaryDto.from(pack, sales, earnings, user);
    }

    @GetMapping("/kits/featured")
    public ResponseEntity<PaginatedResponse<KitSummaryDto>> myKitsFeatured(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        var currentUser = ((CustomUserDetails) auth.getPrincipal()).getUser();

        var kits = kitService.getFeaturedKitsByOwner(currentUser.getId(), limit * 5).stream() // fetch extra for safe paging
                .map(kit -> {
                    Object[] stats = purchaseRepository.getKitSalesAndEarnings(kit.getId());

                    int sales = 0;
                    BigDecimal earnings = BigDecimal.ZERO;

                    if (stats != null && stats.length == 2) {
                        if (stats[0] instanceof Number n) sales = n.intValue();
                        if (stats[1] instanceof BigDecimal bd) earnings = bd;
                        else if (stats[1] instanceof Number n2) earnings = BigDecimal.valueOf(n2.doubleValue());
                    }

                    return KitSummaryDto.from(
                            kit,
                            earnings,
                            sales,
                            kit.getDurationInSeconds(),
                            currentUser
                    );
                })
                .toList();

        // Apply manual pagination
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);
        int from = Math.min(pageIndex * cappedLimit, kits.size());
        int to = Math.min(from + cappedLimit, kits.size());
        List<KitSummaryDto> paginated = kits.subList(from, to);

        return ResponseEntity.ok(new PaginatedResponse<>(paginated, kits.size(), pageIndex, cappedLimit));
    }

    @GetMapping("/kits/new")
    public ResponseEntity<PaginatedResponse<KitSummaryDto>> myKitsNew(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        var currentUser = ((CustomUserDetails) auth.getPrincipal()).getUser();
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, limit));

        var kits = kitService.getNewKitsByOwner(currentUser.getId(), pageable).stream()
                .map(kit -> {
                    Object[] stats = purchaseRepository.getKitSalesAndEarnings(kit.getId());

                    int sales = 0;
                    BigDecimal earnings = BigDecimal.ZERO;

                    if (stats != null && stats.length == 2) {
                        if (stats[0] instanceof Number n) sales = n.intValue();
                        if (stats[1] instanceof BigDecimal bd) earnings = bd;
                        else if (stats[1] instanceof Number n2) earnings = BigDecimal.valueOf(n2.doubleValue());
                    }

                    return KitSummaryDto.from(
                            kit,
                            earnings,
                            sales,
                            kit.getDurationInSeconds(),
                            currentUser
                    );
                })
                .toList();

        // Apply manual pagination
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);
        int from = Math.min(pageIndex * cappedLimit, kits.size());
        int to = Math.min(from + cappedLimit, kits.size());
        List<KitSummaryDto> paginated = kits.subList(from, to);

        return ResponseEntity.ok(new PaginatedResponse<>(paginated, kits.size(), pageIndex, cappedLimit));
    }

    @GetMapping("/kits/popular")
    public ResponseEntity<PaginatedResponse<KitSummaryDto>> myKitsPopular(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        var currentUser = ((CustomUserDetails) auth.getPrincipal()).getUser();
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, limit));

        var kits = kitService.getPopularKitsByOwner(currentUser.getId(), pageable).stream()
                .map(kit -> {
                    Object[] stats = purchaseRepository.getKitSalesAndEarnings(kit.getId());

                    int sales = 0;
                    BigDecimal earnings = BigDecimal.ZERO;

                    if (stats != null && stats.length == 2) {
                        if (stats[0] instanceof Number n) sales = n.intValue();
                        if (stats[1] instanceof BigDecimal bd) earnings = bd;
                        else if (stats[1] instanceof Number n2) earnings = BigDecimal.valueOf(n2.doubleValue());
                    }

                    return KitSummaryDto.from(
                            kit,
                            earnings,
                            sales,
                            kit.getDurationInSeconds(),
                            currentUser
                    );
                })
                .toList();

        // Apply manual pagination
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);
        int from = Math.min(pageIndex * cappedLimit, kits.size());
        int to = Math.min(from + cappedLimit, kits.size());
        List<KitSummaryDto> paginated = kits.subList(from, to);

        return ResponseEntity.ok(new PaginatedResponse<>(paginated, kits.size(), pageIndex, cappedLimit));
    }

    @GetMapping("/kits/trending")
    public ResponseEntity<PaginatedResponse<KitSummaryDto>> myKitsTrending(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page
    ) {
        var currentUser = ((CustomUserDetails) auth.getPrincipal()).getUser();
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, limit));

        var kits = kitService.getTrendingKitsByOwner(currentUser.getId(), pageable).stream()
                .map(kit -> {
                    Object[] stats = purchaseRepository.getKitSalesAndEarnings(kit.getId());

                    int sales = 0;
                    BigDecimal earnings = BigDecimal.ZERO;

                    if (stats != null && stats.length == 2) {
                        if (stats[0] instanceof Number n) sales = n.intValue();
                        if (stats[1] instanceof BigDecimal bd) earnings = bd;
                        else if (stats[1] instanceof Number n2) earnings = BigDecimal.valueOf(n2.doubleValue());
                    }

                    return KitSummaryDto.from(
                            kit,
                            earnings,
                            sales,
                            kit.getDurationInSeconds(),
                            currentUser
                    );
                })
                .toList();

        // Apply manual pagination
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);
        int from = Math.min(pageIndex * cappedLimit, kits.size());
        int to = Math.min(from + cappedLimit, kits.size());
        List<KitSummaryDto> paginated = kits.subList(from, to);

        return ResponseEntity.ok(new PaginatedResponse<>(paginated, kits.size(), pageIndex, cappedLimit));
    }


//    @GetMapping
//    public ResponseEntity<MeDto> me(Authentication auth) {
//        var principal = (CustomUserDetails) auth.getPrincipal();
//        var user = principal.getUser();
//        Long uid = user.getId();
//
//
//        // Base entity counts
//        long beats = beatRepository.countByOwnerId(uid);
//        long packs = packRepository.countByOwnerId(uid);
//        long kits  = kitRepository.countByOwnerId(uid);
//
//        // Optional: total “sound units” in kits (samples + loops + presets)
//        long kitSounds = kitRepository.sumSoundUnitsByOwnerId(uid); // returns 0 if none
//
//        // Deduplicated track count: standalone + from packs
//        Set<Long> standaloneBeatIds = beatRepository.findByOwnerId(uid).stream()
//                .filter(Beat::isApproved)
//                .filter(b -> !b.isRejected())
//                .map(Beat::getId)
//                .collect(Collectors.toSet());
//
//        Set<Long> beatIdsInPacks = packRepository.findByOwnerId(uid).stream()
//                .filter(p -> p.isApproved() && !p.isRejected())
//                .flatMap(p -> p.getBeats().stream())
//                .map(b -> b.getId())
//                .collect(Collectors.toSet());
//
//        standaloneBeatIds.addAll(beatIdsInPacks);
//        long deduplicatedTracks = standaloneBeatIds.size();
//        long beatsInPacks = beatIdsInPacks.size();
//
//        // Total plays = sum of playCount across your content
//        long playsFromBeats = beatRepository.sumPlayCountByOwnerId(uid);
//        long playsFromPacks = packRepository.sumPlayCountByOwnerId(uid);
//        long playsFromKits  = kitRepository.sumPlayCountByOwnerId(uid);
//        long plays = playsFromBeats + playsFromPacks + playsFromKits;
//
//
//        long followers = userRepository.countFollowers(uid);
//
//        var totals = new MeDto.Totals(
//                beats,
//                packs,
//                kits,
//                deduplicatedTracks,
//                beatsInPacks,
//                kitSounds,
//                plays
//        );
//
//        List<Subscription> activeSubs = subscriptionRepository.findActiveByUserId(uid);
//        Subscription activeSub = activeSubs.stream()
//                .max(Comparator.comparingInt(s -> {
//                    String plan = s.getPlanName();
//                    if (plan == null) return -1;   // treat null plan as lowest
//                    switch (plan.toLowerCase()) {
//                        case "free": return 0;
//                        case "growth": return 1;
//                        case "pro": return 2;
//                        default: return -1;          // unknown plan treated as lowest
//                    }
//                }))
//                .orElse(null);
//
//        BigDecimal promoCredits = user.getPromoCredits();
//        BigDecimal referralCredits = user.getReferralCredits();
//
//// Build DTO with the selected subscription
//        var dto = MeDto.of(user, totals, followers, activeSub, promoCredits, referralCredits);
//        return ResponseEntity.ok(dto);
//    }

@GetMapping
public ResponseEntity<MeDto> me(Authentication auth) {
    var principal = (CustomUserDetails) auth.getPrincipal();
    var user = principal.getUser();
    Long uid = user.getId();

    // Base entity counts
    long beats = beatRepository.countByOwnerId(uid);
    long packs = packRepository.countByOwnerId(uid);
    long kits  = kitRepository.countByOwnerId(uid);

    long kitSounds = kitRepository.sumSoundUnitsByOwnerId(uid); // returns 0 if none

    // Deduplicated track count: standalone + from packs
    Set<Long> standaloneBeatIds = beatRepository.findByOwnerId(uid).stream()
            .filter(Beat::isApproved)
            .filter(b -> !b.isRejected())
            .map(Beat::getId)
            .collect(Collectors.toSet());

    Set<Long> beatIdsInPacks = packRepository.findByOwnerId(uid).stream()
            .filter(p -> p.isApproved() && !p.isRejected())
            .flatMap(p -> p.getBeats().stream())
            .map(b -> b.getId())
            .collect(Collectors.toSet());

    standaloneBeatIds.addAll(beatIdsInPacks);
    long deduplicatedTracks = standaloneBeatIds.size();
    long beatsInPacks = beatIdsInPacks.size();

    // Total plays = sum of playCount across your content
    long playsFromBeats = beatRepository.sumPlayCountByOwnerId(uid);
    long playsFromPacks = packRepository.sumPlayCountByOwnerId(uid);
    long playsFromKits  = kitRepository.sumPlayCountByOwnerId(uid);
    long plays = playsFromBeats + playsFromPacks + playsFromKits;

    long followers = userRepository.countFollowers(uid);

    var totals = new MeDto.Totals(
            beats,
            packs,
            kits,
            deduplicatedTracks,
            beatsInPacks,
            kitSounds,
            plays
    );

    // Check for active subscriptions first
    List<Subscription> activeSubs = subscriptionRepository.findActiveByUserId(uid);
    Subscription activeSub = activeSubs.stream()
            .max(Comparator.comparingInt(s -> {
                String plan = s.getPlanName();
                if (plan == null) return -1;   // treat null plan as lowest
                switch (plan.toLowerCase()) {
                    case "free": return 0;
                    case "growth": return 1;
                    case "pro": return 2;
                    default: return -1;          // unknown plan treated as lowest
                }
            }))
            .orElse(null);

    BigDecimal promoCredits = user.getPromoCredits();
    BigDecimal referralCredits = user.getReferralCredits();

    // ✅ Fallback: Founding 200 lifetime users (no subscription record)
// ✅ Fallback: Founding 200 lifetime users (no subscription record)
    if (activeSub == null && "pro".equalsIgnoreCase(user.getPlan()) &&
            "lifetime".equalsIgnoreCase(user.getPlanBillingCycle())) {

        var lifetimeSub = new com.drilldex.drillbackend.subscription.dto.SubscriptionDto(
                null,
                "pro",
                "Pro",
                "Lifetime",
                0,
                "active",
                null,
                user.getCurrentPeriodEnd(), // ✅ already an Instant, no .toInstant()
                null,
                null,
                "Pro Lifetime"
        );

        var dto = MeDto.of(user, totals, followers, null, promoCredits, referralCredits)
                .withSyntheticSubscription(lifetimeSub);
        return ResponseEntity.ok(dto);
    }

    // Default path for regular users
    var dto = MeDto.of(user, totals, followers, activeSub, promoCredits, referralCredits);
    return ResponseEntity.ok(dto);
}

    @PatchMapping(path = "/bio", consumes = MimeTypeUtils.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateBio(Authentication auth, @RequestBody BioPayload payload) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();
        user.setBio(payload.bio() != null ? payload.bio().trim() : null);
        userRepository.save(user);
        return ResponseEntity.ok().body(
                java.util.Map.of("ok", true, "bio", user.getBio())
        );
    }

    @DeleteMapping("/bio")
    public ResponseEntity<?> removeBio(Authentication auth) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();
        user.setBio(null);
        userRepository.save(user);
        return ResponseEntity.ok(java.util.Map.of("ok", true));
    }

    // --- Upload AVATAR ---
    @PostMapping(path = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAvatar(Authentication auth, @RequestPart("file") MultipartFile file) throws IOException {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();

        String prev = user.getProfilePicturePath();              // keep old to delete later (optional)
        String key  = storageService.save(file, "avatars");      // ✅ use StorageService

        user.setProfilePicturePath(key);
        userRepository.save(user);

        try { if (prev != null && !prev.isBlank()) storageService.delete(prev); } catch (IOException ignored) {}
        return ResponseEntity.ok(java.util.Map.of("ok", true, "avatarUrl", key));
    }

    @DeleteMapping("/avatar")
    public ResponseEntity<?> removeAvatar(Authentication auth) throws IOException {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();
        String prev = user.getProfilePicturePath();
        user.setProfilePicturePath(null);
        userRepository.save(user);
        try { if (prev != null && !prev.isBlank()) storageService.delete(prev); } catch (IOException ignored) {}
        return ResponseEntity.ok(java.util.Map.of("ok", true));
    }

    // --- Upload BANNER ---
    @PostMapping(path = "/banner", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadBanner(Authentication auth, @RequestPart("file") MultipartFile file) throws IOException {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();

        String prev = user.getBannerImagePath();
        String key  = storageService.save(file, "banners");      // ✅ use StorageService

        user.setBannerImagePath(key);
        userRepository.save(user);

        try { if (prev != null && !prev.isBlank()) storageService.delete(prev); } catch (IOException ignored) {}
        return ResponseEntity.ok(java.util.Map.of("ok", true, "bannerUrl", key));
    }

    @DeleteMapping("/banner")
    public ResponseEntity<?> removeBanner(Authentication auth) throws IOException {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();
        String prev = user.getBannerImagePath();
        user.setBannerImagePath(null);
        userRepository.save(user);
        try { if (prev != null && !prev.isBlank()) storageService.delete(prev); } catch (IOException ignored) {}
        return ResponseEntity.ok(java.util.Map.of("ok", true));
    }

    @PostMapping(value = "/profile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> updateProfileCombined(
            Authentication auth,
            @RequestPart(value = "avatar", required = false) MultipartFile avatar,
            @RequestPart(value = "banner", required = false) MultipartFile banner,
            @RequestPart(value = "bio", required = false) String bio,
            @RequestPart(value = "instagram", required = false) String instagram,
            @RequestPart(value = "twitter", required = false) String twitter,
            @RequestPart(value = "youtube", required = false) String youtube,
            @RequestPart(value = "facebook", required = false) String facebook,
            @RequestPart(value = "soundcloud", required = false) String soundcloud,
            @RequestPart(value = "tiktok", required = false) String tiktok
    ) throws IOException {

        var user = ((com.drilldex.drillbackend.auth.CustomUserDetails) auth.getPrincipal()).getUser();

        // Save avatar (optional)
        if (avatar != null && !avatar.isEmpty()) {
            String key = storageService.save(avatar, "avatars");
            user.setProfilePicturePath(toWebPath(key));
        }

        // Save banner (optional)
        if (banner != null && !banner.isEmpty()) {
            String key = storageService.save(banner, "banners");
            user.setBannerImagePath(toWebPath(key));
        }

        // Update bio (optional, max 600 chars)
        if (bio != null) {
            String trimmed = bio.trim();
            user.setBio(trimmed.isBlank() ? null : trimmed.substring(0, Math.min(trimmed.length(), 600)));
        }

        // Update socials (optional)
        if (instagram != null) user.setInstagram(instagram.trim());
        if (twitter != null) user.setTwitter(twitter.trim());
        if (youtube != null) user.setYoutube(youtube.trim());
        if (facebook != null) user.setFacebook(facebook.trim());
        if (soundcloud != null) user.setSoundcloud(soundcloud.trim());
        if (tiktok != null) user.setTiktok(tiktok.trim());

        userRepository.save(user);

        // Build null-safe response
        Map<String, Object> response = new HashMap<>();
        response.put("ok", true);
        response.put("avatarUrl", user.getProfilePicturePath() != null ? user.getProfilePicturePath() : "");
        response.put("bannerUrl", user.getBannerImagePath() != null ? user.getBannerImagePath() : "");
        response.put("bio", user.getBio() != null ? user.getBio() : "");

        return ResponseEntity.ok(response);
    }

    /** Normalize storage return values to a browser-usable path/URL. */
    private static String toWebPath(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return null;
        String s = keyOrUrl.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) return s;
        if (s.startsWith("/uploads/")) return s;
        return "/uploads/" + s.replaceFirst("^/+", "");
    }


    @PatchMapping(path = "/username", consumes = MimeTypeUtils.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateDisplayName(Authentication auth, @RequestBody Map<String, String> payload) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();
        String newDisplayName = payload.get("username");

        if (newDisplayName == null || newDisplayName.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Display name cannot be empty"));
        }

        String trimmed = newDisplayName.trim();
        if (trimmed.length() > 50) {
            trimmed = trimmed.substring(0, 50); // Limit to 50 characters
        }

        user.setDisplayName(trimmed);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("ok", true, "displayName", user.getDisplayName()));
    }

    @PatchMapping(path = "/email", consumes = MimeTypeUtils.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateEmail(
            Authentication auth,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, String> payload
    ) {
        var user = resolveUser(auth, authHeader);
        if (user == null) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "Unauthenticated"));
        }
        String newEmail = payload.get("email");

        if (newEmail == null || !newEmail.matches("^[\\w-.]+@([\\w-]+\\.)+[\\w-]{2,4}$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid email"));
        }

        user.setEmail(newEmail.trim());
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("ok", true, "email", user.getEmail()));
    }

    private User resolveUser(Authentication auth, String authHeader) {
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails cud) {
            return cud.getUser();
        }
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        try {
            String token = authHeader.substring(7);
            String email = jwtService.extractUsername(token);
            return userRepository.findByEmail(email).orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    @PatchMapping(path = "/password", consumes = MimeTypeUtils.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updatePassword(
            Authentication auth,
            @RequestBody Map<String, String> payload
    ) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();

        String currentPassword = payload.get("currentPassword");
        String newPassword = payload.get("newPassword");

        if (currentPassword == null || newPassword == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing current or new password"));
        }

        if (newPassword.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 6 characters"));
        }

        // Verify current password
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        if (!encoder.matches(currentPassword, user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Current password is incorrect"));
        }

        // Set new password
        user.setPassword(encoder.encode(newPassword));
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PatchMapping(value = "/profile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> patchProfileMultipart(
            Authentication auth,
            @RequestPart(value = "avatar", required = false) MultipartFile avatar,
            @RequestPart(value = "banner", required = false) MultipartFile banner,
            @RequestPart(value = "bio", required = false) String bio,
            @RequestPart(value = "username", required = false) String username,
            @RequestPart(value = "instagram", required = false) String instagram,
            @RequestPart(value = "twitter", required = false) String twitter,
            @RequestPart(value = "youtube", required = false) String youtube,
            @RequestPart(value = "facebook", required = false) String facebook,
            @RequestPart(value = "soundcloud", required = false) String soundcloud,
            @RequestPart(value = "tiktok", required = false) String tiktok
    ) throws IOException {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();

        if (avatar != null && !avatar.isEmpty()) {
            String key = storageService.save(avatar, "avatars");
            user.setProfilePicturePath(toWebPath(key));
        }

        if (banner != null && !banner.isEmpty()) {
            String key = storageService.save(banner, "banners");
            user.setBannerImagePath(toWebPath(key));
        }

        if (bio != null) {
            String trimmed = bio.trim();
            user.setBio(trimmed.length() > 600 ? trimmed.substring(0, 600) : trimmed);
        }

        if (username != null) user.setDisplayName(username.trim());
        if (instagram != null) user.setInstagram(instagram.trim());
        if (twitter != null) user.setTwitter(twitter.trim());
        if (youtube != null) user.setYoutube(youtube.trim());
        if (facebook != null) user.setFacebook(facebook.trim());
        if (soundcloud != null) user.setSoundcloud(soundcloud.trim());
        if (tiktok != null) user.setTiktok(tiktok.trim());

        userRepository.save(user);

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "avatarUrl", user.getProfilePicturePath() != null ? user.getProfilePicturePath() : "",
                "bannerUrl", user.getBannerImagePath() != null ? user.getBannerImagePath() : "",
                "bio", user.getBio() != null ? user.getBio() : ""
        ));
    }

    @PatchMapping(value = "/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> patchProfileJson(
            Authentication auth,
            @RequestBody Map<String, String> body
    ) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();

        if (body.containsKey("bio")) {
            String trimmed = body.get("bio").trim();
            user.setBio(trimmed.length() > 600 ? trimmed.substring(0, 600) : trimmed);
        }

        if (body.containsKey("username")) user.setDisplayName(body.get("username").trim());
        if (body.containsKey("instagram")) user.setInstagram(body.get("instagram").trim());
        if (body.containsKey("twitter")) user.setTwitter(body.get("twitter").trim());
        if (body.containsKey("youtube")) user.setYoutube(body.get("youtube").trim());
        if (body.containsKey("facebook")) user.setFacebook(body.get("facebook").trim());
        if (body.containsKey("soundcloud")) user.setSoundcloud(body.get("soundcloud").trim());
        if (body.containsKey("tiktok")) user.setTiktok(body.get("tiktok").trim());

        userRepository.save(user);

        return ResponseEntity.ok(Map.of("ok", true));
    }


    @GetMapping("/packs/{id}/contents")
    public ResponseEntity<List<Map<String, Object>>> myPackContents(
            @PathVariable Long id,
            Authentication auth
    ) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();
        var pack = packRepository.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Pack not found"));

        if (!pack.getOwner().getId().equals(user.getId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Not your pack");
        }

        // Normalize cover → public URL (reuse your helper logic if you prefer)
        String cover = pack.getCoverImagePath();
        String coverUrl = (cover == null || cover.isBlank()) ? null
                : (cover.startsWith("http") ? cover : "/uploads/" + cover.replaceFirst("^/+", ""));

        var items = pack.getBeats().stream().map(b -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("title", b.getTitle());
            m.put("artistName", b.getArtist());
            m.put("bpm", b.getBpm());
            m.put("durationInSeconds", b.getDurationInSeconds());
            m.put("coverUrl", coverUrl);
            // previewUrl optional — mirror your PackController logic if needed
            m.put("previewUrl", null);
            return m;
        }).toList();

        return ResponseEntity.ok(items);
    }

    @GetMapping("/kits/{id}/contents")
    public ResponseEntity<Map<String, Object>> getKitContents(
            @PathVariable Long id,
            Authentication auth
    ) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();
        var kit = kitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));

        if (!kit.getOwner().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your kit");
        }

        // Normalize cover and preview URLs
        String cover = kit.getCoverImagePath();
        String preview = kit.getPreviewAudioPath();

        String coverUrl = (cover == null || cover.isBlank()) ? null
                : (cover.startsWith("http") ? cover : "/uploads/" + cover.replaceFirst("^/+", ""));

        String previewUrl = (preview == null || preview.isBlank()) ? null
                : (preview.startsWith("http") ? preview : "/uploads/" + preview.replaceFirst("^/+", ""));

        // Build response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", kit.getId());
        response.put("title", kit.getTitle());
        response.put("type", kit.getType());
        response.put("description", kit.getDescription());
        response.put("bpmMin", kit.getBpmMin());
        response.put("bpmMax", kit.getBpmMax());
        response.put("keySignature", kit.getKeySignature());
        response.put("durationInSeconds", kit.getDurationInSeconds());
        response.put("tags", kit.getTags());
        response.put("coverUrl", coverUrl);
        response.put("previewUrl", previewUrl);
        response.put("price", kit.getPrice());
        response.put("samplesCount", kit.getSamplesCount());
        response.put("loopsCount", kit.getLoopsCount());
        response.put("presetsCount", kit.getPresetsCount());

        // Convert file paths to URLs
        List<String> fileUrls = kit.getFilePaths().stream()
                .map(path -> path.startsWith("http") ? path : "/uploads/" + path.replaceFirst("^/+", ""))
                .toList();

        response.put("fileUrls", fileUrls);

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/packs/{packId}/beats/{beatId}")
    public ResponseEntity<Map<String,Object>> removeBeatFromMyPack(
            @PathVariable Long packId,
            @PathVariable Long beatId,
            Authentication auth
    ) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();

        var pack = packRepository.findById(packId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));
        if (!pack.getOwner().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your pack");
        }

        boolean changed = pack.getBeats().removeIf(b -> java.util.Objects.equals(b.getId(), beatId));
        if (!changed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not in this pack");
        }

        packRepository.save(pack);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/kits/{kitId}/files/{index}")
    public ResponseEntity<Map<String,Object>> removeFileFromMyKit(
            @PathVariable Long kitId,
            @PathVariable int index,
            Authentication auth
    ) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();
        var kit = kitRepository.findById(kitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));

        if (!kit.getOwner().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your kit");
        }

        var files = new java.util.ArrayList<>(kit.getFilePaths());
        if (index < 0 || index >= files.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File index out of range");
        }

        files.remove(index);
        kit.setFilePaths(files);
        // TODO: if you derive samples/loops/presets counts from files, recompute here.
        kitRepository.save(kit);

        return ResponseEntity.ok(Map.of("ok", true, "remaining", files.size()));
    }

    // ===== BEATS =====
    @GetMapping("/beats/{id}/licenses")
    public ResponseEntity<List<Map<String,Object>>> myBeatLicenses(@PathVariable Long id, Authentication auth) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();
        var beat = beatRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found"));
        if (!beat.getOwner().getId().equals(user.getId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);

        var rows = beat.getLicenses().stream().map(l -> {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("type", l.getType().name());     // e.g. BASIC, WAV, STEMS, EXCLUSIVE...
            m.put("enabled", l.isEnabled());
            m.put("price", l.getPrice());
            return m;
        }).toList();
        return ResponseEntity.ok(rows);
    }

    @PatchMapping("/beats/{id}/licenses")
    public ResponseEntity<?> updateMyBeatLicenses(
            @PathVariable Long id,
            @RequestBody List<Map<String,Object>> body,
            Authentication auth
    ) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();
        var beat = beatRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found"));
        if (!beat.getOwner().getId().equals(user.getId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);

        // Simple validation + apply
        var byId = beat.getLicenses().stream().collect(java.util.stream.Collectors.toMap(l -> l.getId(), l -> l));
        for (var row : body) {
            Long licId = ((Number)row.get("id")).longValue();
            var lic = byId.get(licId);
            if (lic == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown license id " + licId);

            Boolean enabled = (Boolean) row.get("enabled");
            Object priceObj = row.get("price");
            java.math.BigDecimal price = (priceObj == null ? null :
                    (priceObj instanceof Number
                            ? java.math.BigDecimal.valueOf(((Number)priceObj).doubleValue())
                            : new java.math.BigDecimal(priceObj.toString())));

            if (enabled != null) lic.setEnabled(enabled);
            if (price != null) {
                if (price.signum() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Price must be >= 0");
                lic.setPrice(price);
            }
        }
        beatRepository.save(beat);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ===== PACKS =====
    @GetMapping("/packs/{id}/licenses")
    public ResponseEntity<List<Map<String,Object>>> myPackLicenses(@PathVariable Long id, Authentication auth) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();
        var pack = packRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));
        if (!pack.getOwner().getId().equals(user.getId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);

        var rows = pack.getLicenses().stream().map(l -> {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("type", l.getType().name());
            m.put("enabled", l.isEnabled());
            m.put("price", l.getPrice());
            return m;
        }).toList();
        return ResponseEntity.ok(rows);
    }

    @PatchMapping("/packs/{id}/licenses")
    public ResponseEntity<?> updateMyPackLicenses(
            @PathVariable Long id,
            @RequestBody List<Map<String,Object>> body,
            Authentication auth
    ) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();
        var pack = packRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));
        if (!pack.getOwner().getId().equals(user.getId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);

        var byId = pack.getLicenses().stream().collect(java.util.stream.Collectors.toMap(l -> l.getId(), l -> l));
        for (var row : body) {
            Long licId = ((Number)row.get("id")).longValue();
            var lic = byId.get(licId);
            if (lic == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown license id " + licId);

            Boolean enabled = (Boolean) row.get("enabled");
            Object priceObj = row.get("price");
            java.math.BigDecimal price = (priceObj == null ? null :
                    (priceObj instanceof Number
                            ? java.math.BigDecimal.valueOf(((Number)priceObj).doubleValue())
                            : new java.math.BigDecimal(priceObj.toString())));

            if (enabled != null) lic.setEnabled(enabled);
            if (price != null) {
                if (price.signum() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Price must be >= 0");
                lic.setPrice(price);
            }
        }
        packRepository.save(pack);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // GET /api/me/beats/{id}
    @GetMapping("/beats/{id}")
    public ResponseEntity<BeatSummaryDto> myBeatDetail(@PathVariable Long id, Authentication auth) {
        var currentUser = ((CustomUserDetails) auth.getPrincipal()).getUser();
        Long uid = currentUser.getId();

        var beat = beatRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found"));

        if (!beat.getOwner().getId().equals(uid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your beat");
        }

        Object[] stats = purchaseRepository.getBeatSalesAndEarnings(beat.getId());

        int sales = 0;
        BigDecimal earnings = BigDecimal.ZERO;

        if (stats != null && stats.length == 2) {
            if (stats[0] instanceof Number n1) sales = n1.intValue();
            if (stats[1] instanceof BigDecimal bd) earnings = bd;
            else if (stats[1] instanceof Number n2) earnings = BigDecimal.valueOf(n2.doubleValue());
        }

        return ResponseEntity.ok(BeatSummaryDto.from(beat, sales, earnings, currentUser)); // ✅ Updated to include user
    }

    // GET /api/me/packs/{id}
    @GetMapping("/packs/{id}")
    public ResponseEntity<PackSummaryDto> myPackDetail(@PathVariable Long id, Authentication auth) {
        var currentUser = ((CustomUserDetails) auth.getPrincipal()).getUser();
        Long uid = currentUser.getId();

        var pack = packRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));

        if (!pack.getOwner().getId().equals(uid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your pack");
        }

        List<Object[]> rows = purchaseRepository.getPackSalesAndEarnings(pack.getId());
        Object[] stats = (rows != null && !rows.isEmpty()) ? rows.get(0) : null;

        int sales = 0;
        BigDecimal earnings = BigDecimal.ZERO;

        if (stats != null && stats.length == 2) {
            if (stats[0] instanceof Number n1) sales = n1.intValue();
            if (stats[1] instanceof BigDecimal bd) earnings = bd;
            else if (stats[1] instanceof Number n2) earnings = BigDecimal.valueOf(n2.doubleValue());
        }

        return ResponseEntity.ok(PackSummaryDto.from(pack, sales, earnings, currentUser)); // ✅ Updated
    }

    // GET /api/me/kits/{id}
    @GetMapping("/kits/{id}")
    public ResponseEntity<KitSummaryDto> myKitDetail(@PathVariable Long id, Authentication auth) {
        var currentUser = ((CustomUserDetails) auth.getPrincipal()).getUser();

        var kit = kitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));

        if (!kit.getOwner().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your kit");
        }

        Object[] stats = purchaseRepository.getKitSalesAndEarnings(id);

        int sales = 0;
        BigDecimal earnings = BigDecimal.ZERO;

        if (stats != null && stats.length == 2) {
            if (stats[0] instanceof Number n) sales = n.intValue();
            if (stats[1] instanceof BigDecimal bd) earnings = bd;
            else if (stats[1] instanceof Number n2) earnings = BigDecimal.valueOf(n2.doubleValue());
        }

        return ResponseEntity.ok(
                KitSummaryDto.from(
                        kit,
                        earnings,
                        sales,
                        kit.getDurationInSeconds(),
                        currentUser
                )
        );
    }


    // ===================== BEAT =====================
    @PatchMapping(path = "/beats/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BeatSummaryDto> patchMyBeat(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication auth
    ) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();

        var beat = beatRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beat not found"));

        if (!beat.getOwner().getId().equals(user.getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your beat");

        if (body.containsKey("title")) {
            String v = String.valueOf(body.get("title")).trim();
            if (!v.isBlank()) beat.setTitle(v);
        }
        if (body.containsKey("genre")) {
            String v = String.valueOf(body.get("genre")).trim();
            beat.setGenre(v);
        }
        if (body.containsKey("bpm")) {
            try {
                Integer v = Integer.valueOf(String.valueOf(body.get("bpm")).trim());
                beat.setBpm(v);
            } catch (Exception ignored) {}
        }
        if (body.containsKey("tags")) {
            // Accept "tag1, tag2" or ["tag1","tag2"]
            Object t = body.get("tags");
            String tags =
                    (t instanceof java.util.Collection<?> coll)
                            ? coll.stream().map(Object::toString).map(String::trim)
                            .filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.joining(", "))
                            : String.valueOf(t);
            beat.setTags(tags);
        }

        beatRepository.save(beat);

        // 🧮 Fetch sales & earnings
        Object[] stats = purchaseRepository.getBeatSalesAndEarnings(beat.getId());
        int sales = 0;
        BigDecimal earnings = BigDecimal.ZERO;

        if (stats != null && stats.length == 2) {
            if (stats[0] instanceof Number n) sales = n.intValue();
            if (stats[1] instanceof BigDecimal bd) earnings = bd;
            else if (stats[1] instanceof Number n2) earnings = BigDecimal.valueOf(n2.doubleValue());
        }

        return ResponseEntity.ok(BeatSummaryDto.from(beat, sales, earnings, user));
    }


    // ===================== PACK =====================
    @PatchMapping(path = "/packs/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PackSummaryDto> patchMyPack(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication auth
    ) {
        var user = ((CustomUserDetails) auth.getPrincipal()).getUser();

        var pack = packRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));

        if (!pack.getOwner().getId().equals(user.getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your pack");

        // title/name (support either key from frontend)
        if (body.containsKey("title") || body.containsKey("name")) {
            String v = String.valueOf(body.getOrDefault("title", body.get("name"))).trim();
            if (!v.isBlank()) pack.setTitle(v);
        }

        if (body.containsKey("tags")) {
            Object t = body.get("tags");
            String tags =
                    (t instanceof java.util.Collection<?> coll)
                            ? coll.stream().map(Object::toString).map(String::trim)
                            .filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.joining(", "))
                            : String.valueOf(t);
            pack.setTags(tags);
        }

        packRepository.save(pack);


        List<Object[]> rows = purchaseRepository.getPackSalesAndEarnings(pack.getId());
        Object[] stats = (rows != null && !rows.isEmpty()) ? rows.get(0) : null;
        int sales = 0;
        BigDecimal earnings = BigDecimal.ZERO;

        if (stats != null && stats.length == 2) {
            if (stats[0] instanceof Number n) sales = n.intValue();
            if (stats[1] instanceof BigDecimal bd) earnings = bd;
            else if (stats[1] instanceof Number n2) earnings = BigDecimal.valueOf(n2.doubleValue());
        }

        return ResponseEntity.ok(PackSummaryDto.from(pack, sales, earnings, user));
    }

    // ===================== KIT =====================
    @PatchMapping(path = "/kits/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<KitSummaryDto> patchMyKit(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            Authentication auth
    ) {
        var currentUser = ((CustomUserDetails) auth.getPrincipal()).getUser();

        var kit = kitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kit not found"));

        if (!Objects.equals(kit.getOwner().getId(), currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your kit");
        }

        // --- apply partial updates ---
        if (body.containsKey("title") || body.containsKey("name")) {
            String v = String.valueOf(body.getOrDefault("title", body.get("name"))).trim();
            if (!v.isBlank()) kit.setTitle(v);
        }

        if (body.containsKey("tags")) {
            Object t = body.get("tags");
            String tags =
                    (t instanceof Collection<?> coll)
                            ? coll.stream()
                            .map(Object::toString)
                            .map(String::trim)
                            .filter(s -> !s.isBlank())
                            .collect(java.util.stream.Collectors.joining(", "))
                            : String.valueOf(t);
            kit.setTags(tags);
        }

        if (body.containsKey("price")) {
            try {
                var v = new java.math.BigDecimal(String.valueOf(body.get("price")).trim());
                if (v.signum() >= 0) kit.setPrice(v);
            } catch (Exception ignored) { /* ignore bad price */ }
        }

        // (optional) allow updating type
        if (body.containsKey("type")) {
            String type = String.valueOf(body.get("type")).trim();
            if (!type.isBlank()) kit.setType(type);
        }

        kitRepository.save(kit);

        // fetch sales and earnings
        Object[] stats = purchaseRepository.getKitSalesAndEarnings(id);
        int sales = 0;
        BigDecimal earnings = BigDecimal.ZERO;

        if (stats != null && stats.length == 2) {
            if (stats[0] instanceof Number n) sales = n.intValue();
            if (stats[1] instanceof BigDecimal bd) earnings = bd;
            else if (stats[1] instanceof Number n2) earnings = BigDecimal.valueOf(n2.doubleValue());
        }

        return ResponseEntity.ok(
                KitSummaryDto.from(
                        kit,
                        earnings,
                        sales,
                        kit.getDurationInSeconds(),
                        currentUser
                )
        );
    }

    @GetMapping("/referral")
    public ResponseEntity<Map<String, Object>> getReferralInfo(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        User user = userDetails.getUser();

        // Provide default values if null
        String referralCode = user.getReferralCode() != null ? user.getReferralCode() : "";
        BigDecimal referralCredits = user.getReferralCredits() != null ? user.getReferralCredits() : BigDecimal.ZERO;
        int referralCount = user.getReferralCount();  // already has a default 0 if uninitialized

        Map<String, Object> response = Map.of(
                "referralCode", referralCode,
                "referralCredits", referralCredits,
                "referralCount", referralCount,
                "referralLink", String.format("%s/register?ref=%s", frontendBaseUrl, referralCode)
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/beats/{id}/stems")
    public ResponseEntity<?> uploadBeatStems(@PathVariable Long id,
                                             @RequestPart("stems") MultipartFile stemsZip) {
        User user = currentUserService.getCurrentUserOrThrow();
        beatService.uploadBeatStems(user, id, stemsZip);
        return ResponseEntity.ok(Map.of("message", "Stems uploaded successfully"));
    }

    @PostMapping("/packs/{id}/stems")
    public ResponseEntity<?> uploadPackStems(@PathVariable Long id,
                                             @RequestPart("stems") MultipartFile stemsZip) {
        User user = currentUserService.getCurrentUserOrThrow();
        packService.uploadPackStems(user, id, stemsZip);
        return ResponseEntity.ok(Map.of("message", "Stems uploaded successfully"));
    }
}
