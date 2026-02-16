package com.drilldex.drillbackend.promotions;

import com.drilldex.drillbackend.auth.CustomUserDetails;
import com.drilldex.drillbackend.beat.BeatRepository;
import com.drilldex.drillbackend.kit.KitRepository;
import com.drilldex.drillbackend.notification.NotificationService;
import com.drilldex.drillbackend.pack.PackRepository;
import com.drilldex.drillbackend.promotions.dto.PromotionDto;
import com.drilldex.drillbackend.user.CurrentUserService;
import com.drilldex.drillbackend.user.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/promotions")
public class PromotionController {

    private final PromotionService promotionService;
    private final PromotionRepository promotionRepository;
    private final CurrentUserService currentUserService;
    private final BeatRepository beatRepository;
    private final PackRepository packRepository;
    private final KitRepository kitRepository;


    @org.springframework.beans.factory.annotation.Value("${app.storage.local.web-base:/uploads}")
    private String webBase;

    public PromotionController(PromotionService promotionService, PromotionRepository promotionRepository, CurrentUserService currentUserService
            , BeatRepository beatRepository, PackRepository packRepository, KitRepository kitRepository, NotificationService notificationService) {
        this.promotionService = promotionService;
        this.promotionRepository = promotionRepository;
        this.currentUserService = currentUserService;
        this.beatRepository = beatRepository;
        this.packRepository = packRepository;
        this.kitRepository = kitRepository;


    }


    // PromotionController.java
    @GetMapping("/mine")
    public List<PromotionDto> mine() {
        User me = currentUserService.getCurrentUserOrThrow();

        return promotionRepository.findByOwner(me).stream()
                .map(p -> {
                    String title = null;
                    String thumb = null;

                    switch (p.getTargetType()) {
                        case BEAT -> {
                            var b = beatRepository.findById(p.getTargetId()).orElse(null);
                            if (b != null) {
                                title = b.getTitle();
                                // Beat: prefer albumCoverUrl, else coverImagePath
                                thumb = resolveCoverUrl(b.getAlbumCoverUrl(), b.getCoverImagePath());
                            }
                        }
                        case PACK -> {
                            var pack = packRepository.findById(p.getTargetId()).orElse(null);
                            if (pack != null) {
                                title = pack.getTitle();
                                // Pack: prefer albumCoverUrl, else coverImagePath
                                thumb = resolveCoverUrl(pack.getAlbumCoverUrl(), pack.getCoverImagePath());
                            }
                        }
                        case KIT -> {
                            var kit = kitRepository.findById(p.getTargetId()).orElse(null);
                            if (kit != null) {
                                title = kit.getTitle();
                                // Kit: only coverImagePath
                                thumb = resolveCoverUrl(null, kit.getCoverImagePath());
                            }
                        }
                    }

                    var started = p.getStartDate();
                    var endsAt  = (started == null) ? null
                            : started.plus(java.time.Duration.ofDays(p.getDurationDays()));

                    // status precedence: canceled > active > inactive
                    String computed = p.isActive() ? "active" : "inactive";
                    String status   = (p.getStatus() != null && p.getStatus().equalsIgnoreCase("canceled"))
                            ? "canceled" : computed;

                    return new PromotionDto(
                            p.getId(),
                            "promotion",                 // type
                            p.getTargetType().name(),    // targetType
                            p.getTargetId(),
                            title,
                            thumb,
                            (p.getTier() == null ? "standard" : p.getTier()),
                            status,
                            started,
                            endsAt
                    );
                })
                .toList();
    }

    private String resolveCoverUrl(String albumCoverUrl, String coverImagePath) {
        // Prefer already-hosted URL first
        if (albumCoverUrl != null && !albumCoverUrl.isBlank()) {
            return albumCoverUrl; // usually already an https URL
        }
        if (coverImagePath == null || coverImagePath.isBlank()) return null;

        // If the path is already a URL, return it as-is
        if (coverImagePath.startsWith("http://") || coverImagePath.startsWith("https://")) {
            return coverImagePath;
        }

        String base = (webBase == null ? "/uploads" : webBase).replaceAll("/+$", "");
        return base + "/" + coverImagePath.replaceFirst("^/+", "");
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id) {
        User me = currentUserService.getCurrentUserOrThrow();
        Promotion promo = promotionRepository.findById(id).orElseThrow();


        if (!promo.getOwner().getId().equals(me.getId())) {
            return ResponseEntity.status(403).build(); // Forbidden
        }

        promo.setStatus("canceled");
        promotionRepository.save(promo);
        return ResponseEntity.ok().build();
    }
}
