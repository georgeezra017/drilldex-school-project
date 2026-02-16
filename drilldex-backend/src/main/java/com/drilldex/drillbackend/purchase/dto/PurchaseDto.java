package com.drilldex.drillbackend.purchase.dto;

import com.drilldex.drillbackend.purchase.Purchase;
import com.drilldex.drillbackend.beat.LicenseType;
import com.drilldex.drillbackend.subscription.Subscription;
import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.pack.Pack;
import com.drilldex.drillbackend.kit.Kit;
import com.drilldex.drillbackend.beat.BeatRepository;
import com.drilldex.drillbackend.pack.PackRepository;
import com.drilldex.drillbackend.kit.KitRepository;
import lombok.*;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseDto {
    private Long purchaseId;
    private String type; // "beat" | "pack" | "kit" | "promotion" | "subscription"
    private Long itemId;
    private String title;
    private String img;
    private LicenseType licenseType;
    private String licenseUrl;
    private String zipUrl;
    private BigDecimal pricePaid;

    /**
     * Map a Purchase to a DTO.
     * @param p the purchase entity
     * @param beatRepo for fetching beat info for promotions
     * @param packRepo for fetching pack info for promotions
     * @param kitRepo for fetching kit info for promotions
     */
    public static PurchaseDto from(Purchase p,
                                   BeatRepository beatRepo,
                                   PackRepository packRepo,
                                   KitRepository kitRepo) {
        String type;
        Long itemId = null;
        String title = null;
        String img = null;

        // --- Promotions have priority ---
        if (p.getPromotionTargetType() != null && p.getPromotionTargetId() != null) {
            type = "promotion";
            itemId = p.getPromotionTargetId();

            switch (p.getPromotionTargetType()) {
                case "BEAT" -> {
                    Beat beat = beatRepo.findById(itemId).orElse(null);
                    if (beat != null) {
                        title = beat.getTitle();
                        img = beat.getCoverImagePath();
                    }
                }
                case "PACK" -> {
                    Pack pack = packRepo.findById(itemId).orElse(null);
                    if (pack != null) {
                        title = pack.getTitle();
                        img = pack.getCoverImagePath();
                    }
                }
                case "KIT" -> {
                    Kit kit = kitRepo.findById(itemId).orElse(null);
                    if (kit != null) {
                        title = kit.getTitle();
                        img = kit.getCoverImagePath();
                    }
                }
            }
        }
        // --- Regular beat/pack/kit purchase ---
        else if (p.getBeat() != null) {
            type = "beat";
            itemId = p.getBeat().getId();
            title = p.getBeat().getTitle();
            img = p.getBeat().getCoverImagePath();
        } else if (p.getPack() != null) {
            type = "pack";
            itemId = p.getPack().getId();
            title = p.getPack().getTitle();
            img = p.getPack().getCoverImagePath();
        } else if (p.getKit() != null) {
            type = "kit";
            itemId = p.getKit().getId();
            title = p.getKit().getTitle();
            img = p.getKit().getCoverImagePath();
        } else {
            type = "unknown";
        }

        return PurchaseDto.builder()
                .purchaseId(p.getId())
                .type(type)
                .itemId(itemId)
                .title(title)
                .img(img)
                .licenseType(p.getLicenseType())
                .licenseUrl(p.getLicensePdfPath())
                .zipUrl(p.getZipDownloadPath())
                .pricePaid(p.getPricePaid())
                .build();
    }

    public static PurchaseDto fromSubscription(Subscription sub) {
        PurchaseDto dto = new PurchaseDto();
        dto.setPurchaseId(sub.getId());
        dto.setType("subscription");
        dto.setTitle("Subscription: " + sub.getPlanName());
        dto.setImg("/logo.png"); // frontend logo
        dto.setLicenseUrl(null); // no license PDF
        dto.setPricePaid(sub.getPriceCents() != null
                ? new BigDecimal(sub.getPriceCents()).divide(new BigDecimal(100))
                : BigDecimal.ZERO);
        return dto;
    }
}