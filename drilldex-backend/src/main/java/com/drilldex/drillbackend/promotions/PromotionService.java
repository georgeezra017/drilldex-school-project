package com.drilldex.drillbackend.promotions;

import com.drilldex.drillbackend.beat.BeatRepository;
import com.drilldex.drillbackend.kit.KitRepository;
import com.drilldex.drillbackend.notification.Notification;
import com.drilldex.drillbackend.notification.NotificationService;
import com.drilldex.drillbackend.notification.NotificationType;
import com.drilldex.drillbackend.notification.RelatedType;
import com.drilldex.drillbackend.pack.PackRepository;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.drilldex.drillbackend.promotions.PromotionRequest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class PromotionService {

    private final PromotionRepository repo;
    private final UserRepository userRepo;
    private final NotificationService notificationService;
    private final BeatRepository beatRepository;
    private final PackRepository packRepository;
    private final KitRepository kitRepository;
    private final UserRepository userRepository;




    public PromotionService(PromotionRepository repo, UserRepository userRepo, NotificationService notificationService,
                            BeatRepository beatRepository,
                            PackRepository packRepository,
                            KitRepository kitRepository,
                            UserRepository userRepository) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.notificationService = notificationService;
        this.beatRepository = beatRepository;
        this.packRepository = packRepository;
        this.kitRepository = kitRepository;
        this.userRepository = userRepository;
    }

    private static final Map<String, BigDecimal> RATE_PER_DAY = Map.of(
            "standard", BigDecimal.valueOf(1.5),
            "premium", BigDecimal.valueOf(3.0),
            "spotlight", BigDecimal.valueOf(6.0)
    );

    /** Promote multiple items for a user */
    @Transactional
    public void promote(User user, List<PromotionRequest> reqs) {
        BigDecimal availableCredits = (user.getPromoCredits() != null ? user.getPromoCredits() : BigDecimal.ZERO)
                .add(user.getReferralCredits() != null ? user.getReferralCredits() : BigDecimal.ZERO);
        List<Promotion> savedPromotions = new java.util.ArrayList<>();

        for (PromotionRequest req : reqs) {
            // --- LOG THE INCOMING REQUEST ---
            System.out.println("[PROMOTE] Incoming PromotionRequest:");
            System.out.println("  targetType: " + req.getTargetType());
            System.out.println("  targetId: " + req.getTargetId());
            System.out.println("  tier: " + req.getTier());
            System.out.println("  days: " + req.getDays());
            System.out.println("  paymentMethod: " + req.getPaymentMethod());
            System.out.println("  orderId: " + req.getOrderId());

            Promotion p = new Promotion();
            p.setOwner(user);
            p.setTargetType(Promotion.TargetType.valueOf(req.getTargetType().toUpperCase()));
            p.setTargetId(req.getTargetId());
            p.setTier(req.getTier());
            p.setDurationDays(req.getDays());
            p.setStartDate(Instant.now());
            p.setStatus("active");

            BigDecimal basePrice = getPromotionPrice(p.getTargetType(), p.getTier(), p.getDurationDays());
            BigDecimal price = applyPlanDiscount(user, basePrice);
            System.out.println("[PROMOTE] Created Promotion object:");
            System.out.println("  targetType: " + p.getTargetType());
            System.out.println("  targetId: " + p.getTargetId());
            System.out.println("  tier: " + p.getTier());
            System.out.println("  durationDays: " + p.getDurationDays());
            System.out.println("  calculatedPrice: " + price);

            BigDecimal creditsToUse = price.min(availableCredits);
            price = price.subtract(creditsToUse);

// Deduct from promoCredits first
            BigDecimal remainingCredits = creditsToUse;

// Deduct from promoCredits first
            BigDecimal userPromo = user.getPromoCredits() != null ? user.getPromoCredits() : BigDecimal.ZERO;
            BigDecimal promoUsed = remainingCredits.min(userPromo);
            remainingCredits = remainingCredits.subtract(promoUsed);
            user.setPromoCredits(userPromo.subtract(promoUsed).max(BigDecimal.ZERO));

// Then deduct from referralCredits
            BigDecimal userReferral = user.getReferralCredits() != null ? user.getReferralCredits() : BigDecimal.ZERO;
            BigDecimal referralUsed = remainingCredits.min(userReferral);
            user.setReferralCredits(userReferral.subtract(referralUsed).max(BigDecimal.ZERO));

// Total credits applied to the promotion
            p.setCreditsUsed(promoUsed.add(referralUsed));

            repo.save(p);
            savedPromotions.add(p);

            if (price.compareTo(BigDecimal.ZERO) > 0) {
                String paymentMethod = req.getPaymentMethod() != null ? req.getPaymentMethod() : "test";
                String transactionId = "local_tx_" + java.util.UUID.randomUUID();
                System.out.println("Simulated charge for " + user.getEmail() + " via " + paymentMethod + ", transactionId=" + transactionId);
            }
        }

        userRepo.save(user);

        // --- Hybrid Notification Strategy ---
        if (savedPromotions.size() == 1) {
            Promotion p = savedPromotions.get(0);
            String title = resolveTitle(p.getTargetType(), p.getTargetId());
            Notification n = notificationService.create(
                    user,
                    NotificationType.PROMOTION,
                    RelatedType.valueOf(p.getTargetType().name()),
                    p.getId(),
                    "Promotion started",
                    "Your promotion for " + title + " is now live"
            );
            notificationService.pushNotificationToUser(user.getId(), n); // <-- SSE push
        } else if (!savedPromotions.isEmpty()) {
            Notification n = notificationService.create(
                    user,
                    NotificationType.PROMOTION,
                    RelatedType.SYSTEM,
                    null,
                    "Promotions started",
                    "Your prootions are now live"
            );
            notificationService.pushNotificationToUser(user.getId(), n); // <-- SSE push
        }
    }

    private BigDecimal applyPlanDiscount(User user, BigDecimal basePrice) {
        if (user == null || user.getPlan() == null) return basePrice;

        return switch (user.getPlan().toLowerCase()) {
            case "growth" -> basePrice.multiply(BigDecimal.valueOf(0.9));  // 10% off
            case "pro" -> basePrice.multiply(BigDecimal.valueOf(0.8));     // 20% off
            default -> basePrice;                                          // free/no discount
        };
    }

    private String resolveTitle(Promotion.TargetType type, Long id) {
        return switch (type) {
            case BEAT -> beatRepository.findById(id).map(b -> b.getTitle()).orElse("your beat");
            case PACK -> packRepository.findById(id).map(p -> p.getTitle()).orElse("your pack");
            case KIT -> kitRepository.findById(id).map(k -> k.getTitle()).orElse("your kit");
        };
    }

    /** Price calculation for promotions */
    public BigDecimal getPromotionPrice(Promotion.TargetType type, String tier, int days) {
        BigDecimal rate = RATE_PER_DAY.getOrDefault(tier.toLowerCase(), BigDecimal.valueOf(1.5));
        return rate.multiply(BigDecimal.valueOf(days));
    }

    /** Get active promotions for a type */
    public List<Promotion> getActivePromotions(Promotion.TargetType type, Instant now, int limit) {
        return repo.findByTargetTypeAndStartDateBefore(type, now, PageRequest.of(0, Math.max(1, limit)))
                .stream()
                .filter(Promotion::isActive)
                .toList();
    }

    /** Check if an item is currently featured */
    public boolean isCurrentlyFeatured(Promotion.TargetType type, Long targetId) {
        List<Promotion> promos = repo.findByTargetTypeAndTargetId(type, targetId);
        Instant now = Instant.now();
        return promos.stream().anyMatch(p ->
                p.getStartDate() != null &&
                        p.getStartDate().isBefore(now) &&
                        p.getStartDate().plus(Duration.ofDays(p.getDurationDays())).isAfter(now)
        );
    }


    @Scheduled(cron = "0 0 * * * *") // every hour on the hour
    @Transactional
    public void checkForExpiredPromotions() {
        Instant now = Instant.now();
        List<Promotion> activePromotions = repo.findAllActive();

        for (Promotion p : activePromotions) {
            Instant end = p.getStartDate().plus(Duration.ofDays(p.getDurationDays()));
            if (now.isAfter(end)) {
                p.setStatus("expired");
                repo.save(p);

                // Send expiration notification
                String title = resolveTitle(p.getTargetType(), p.getTargetId());
                Notification n = notificationService.create(
                        p.getOwner(),
                        NotificationType.PROMOTION,
                        RelatedType.valueOf(p.getTargetType().name()),
                        p.getId(),
                        "Promotion ended",
                        "Your promotion for " + title + " has ended"
                );

                // --- Push via SSE ---
                notificationService.pushNotificationToUser(p.getOwner().getId(), n);
            }
        }
    }

    public void handleUnsoldPromotion(User owner, Promotion promotion) {
        if (owner == null || promotion == null) return;

        // --- 1️⃣ Only handle expired promotions ---
        Instant endDate = promotion.getStartDate().plus(Duration.ofDays(promotion.getDurationDays()));
        if (Instant.now().isBefore(endDate)) return; // still running

        // --- 2️⃣ Only handle if not processed yet ---
        if ("completed".equalsIgnoreCase(promotion.getStatus())) return;

        // --- 3️⃣ Only Growth and Pro plans get rerun/promo credits ---
        String plan = owner.getPlan() != null ? owner.getPlan().toLowerCase() : "";
        int promoCreditsInt;
        switch (plan) {
            case "growth" -> promoCreditsInt = 10;
            case "pro" -> promoCreditsInt = 25;
            default -> {
                // Free plan: mark promotion as completed but give no credits
                promotion.setStatus("completed");
                repo.save(promotion);
                return;
            }
        }

        BigDecimal promoCredits = BigDecimal.valueOf(promoCreditsInt);

        // --- 4️⃣ Rerun the promotion ---
        PromotionRequest rerunRequest = new PromotionRequest();
        rerunRequest.setTargetType(promotion.getTargetType().name()); // String now
        rerunRequest.setTargetId(promotion.getTargetId());
        rerunRequest.setTier(promotion.getTier());
        rerunRequest.setDays(promotion.getDurationDays()); // use original duration
        rerunRequest.setPaymentMethod("SYSTEM");           // system-triggered
        rerunRequest.setOrderId(null);                     // no associated order

        promote(owner, List.of(rerunRequest)); // existing method to start a promotion

        // --- 5️⃣ Add promo credits to the user ---
        if (promoCredits.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal currentCredits = owner.getPromoCredits() != null ? owner.getPromoCredits() : BigDecimal.ZERO;
            owner.setPromoCredits(currentCredits.add(promoCredits));
            userRepository.save(owner); // persist updated credits
        }

        // --- 6️⃣ Notify the user ---
        String message = "Your promotion for " + promotion.getTargetType().name().toLowerCase() +
                " #" + promotion.getTargetId() + " did not sell. " +
                "We reran your promotion and credited $" + promoCreditsInt + " in promo credits to your account.";

        Notification n = notificationService.create(
                owner,
                NotificationType.PROMOTION,
                RelatedType.valueOf(promotion.getTargetType().name()), // BEAT, PACK, KIT
                promotion.getTargetId(),
                "Unsold Promotion Rerun",
                message
        );

        notificationService.pushNotificationToUser(owner.getId(), n);

        // --- 7️⃣ Mark promotion as processed ---
        promotion.setStatus("completed");
        repo.save(promotion);
    }


}
