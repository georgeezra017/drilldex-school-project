package com.drilldex.drillbackend.promotions;

import com.drilldex.drillbackend.purchase.PurchaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PromotionScheduler {

    private final PromotionRepository promotionRepository;
    private final PurchaseRepository purchaseRepository;
    private final PromotionService promotionService; // contains handleUnsoldPromotion

    // Runs every hour
    @Scheduled(cron = "0 0 * * * *")
    public void checkForUnsoldPromotions() {
        List<Promotion> activePromotions = promotionRepository.findByStatus("active");

        for (Promotion promo : activePromotions) {
            Instant startDate = promo.getStartDate();
            Instant endDate = startDate.plus(Duration.ofDays(promo.getDurationDays()));

            boolean hasSales = purchaseRepository.existsByPromotionTarget(
                    promo.getTargetType().name(), // String now
                    promo.getTargetId(),
                    startDate,
                    endDate
            );

            if (!hasSales) {
                promotionService.handleUnsoldPromotion(promo.getOwner(), promo);
            }
        }
    }
}