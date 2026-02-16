// src/main/java/com/drilldex/drillbackend/subscription/SubscriptionService.java
package com.drilldex.drillbackend.subscription;

import com.drilldex.drillbackend.notification.Notification;
import com.drilldex.drillbackend.notification.NotificationService;
import com.drilldex.drillbackend.notification.NotificationType;
import com.drilldex.drillbackend.notification.RelatedType;
import com.drilldex.drillbackend.subscription.dto.SubscriptionStartRequest;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository repo;
    private final UserRepository userRepo;
    private final NotificationService notificationService;


    @Transactional
    public Subscription start(User user, SubscriptionStartRequest req, String orderId) {
        System.out.println("[SUBSCRIPTION] Starting subscription for user: " + user.getId());
        System.out.println("[SUBSCRIPTION] Received planId: " + req.planId());
        System.out.println("[SUBSCRIPTION] Received planName: " + req.planName());
        System.out.println("[SUBSCRIPTION] Received billingCycle: " + req.billingCycle());
        System.out.println("[SUBSCRIPTION] Received trialDays: " + req.trialDays());
        System.out.println("[SUBSCRIPTION] OrderId: " + orderId);

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);

        // --- Calculate backend price ---
        BigDecimal price = calculatePrice(req.planId(), req.billingCycle());

        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setPlanId(req.planId());
        sub.setPlanName(req.planName());
        sub.setBillingCycle(req.billingCycle() == null ? "monthly" : req.billingCycle().toLowerCase());
        sub.setPriceCents(price.multiply(new BigDecimal("100")).intValue());
        sub.setStatus("active");
        sub.setStartedAt(now.toInstant());

        // --- Compute period end ---
        ZonedDateTime periodEnd;
        if (req.trialDays() != null && req.trialDays() > 0) {
            periodEnd = now.plusDays(req.trialDays());
        } else {
            periodEnd = "yearly".equalsIgnoreCase(sub.getBillingCycle())
                    ? now.plusYears(1)
                    : now.plusMonths(1);
        }
        sub.setCurrentPeriodEnd(periodEnd.toInstant());

        sub.setLastOrderId(orderId);

        // --- Sync user plan info ---
        user.setPlan(req.planName() != null ? req.planName() : "pro");
        user.setPlanBillingCycle(sub.getBillingCycle());
        user.setTrialDaysLeft(req.trialDays() != null ? req.trialDays() : 0);
        user.setSubscriptionStart(now.toInstant());

        if (req.trialDays() != null && req.trialDays() > 0) {
            user.setTrialEndsAt(periodEnd.toInstant());
            user.setCurrentPeriodEnd(null); // not paid yet
        } else {
            user.setTrialEndsAt(null);
            user.setCurrentPeriodEnd(periodEnd.toInstant());
        }

        // --- 1️⃣ Award first-time subscription promo credits ---
        if (!hasEverHadPaidSubscription(user)) { // helper method to check prior paid subs
            int promoCredits = switch (user.getPlan() != null ? user.getPlan().toLowerCase() : "") {
                case "growth" -> 75; // increased from 30
                case "pro" -> 150;   // increased from 50
                default -> 0;
            };

            if (promoCredits > 0) {
                BigDecimal currentCredits = user.getPromoCredits() != null ? user.getPromoCredits() : BigDecimal.ZERO;
                user.setPromoCredits(currentCredits.add(BigDecimal.valueOf(promoCredits)));
            }
        }

        // --- Deduct promo credits ---
        BigDecimal availableCredits = user.getPromoCredits() != null ? user.getPromoCredits() : BigDecimal.ZERO;
        BigDecimal creditsToUse = price.min(availableCredits);
        price = price.subtract(creditsToUse);
        user.setPromoCredits(availableCredits.subtract(creditsToUse));

        // --- Simulated local charge (no external payment processors) ---
        if (price.compareTo(BigDecimal.ZERO) > 0) {
            String method = req.paymentMethod() != null ? req.paymentMethod() : "test";
            String tx = "local_tx_" + java.util.UUID.randomUUID();
            System.out.println("[SUBSCRIPTION] Simulated charge via " + method + " (tx=" + tx + ")");
        }


        // Save user + subscription
        userRepo.save(user);
        Subscription saved = repo.save(sub);

        // --- Send notification 🔔 ---
        Notification n = notificationService.create(
                user,
                NotificationType.SYSTEM,
                RelatedType.SUBSCRIPTION,
                saved.getId(),
                "Subscription active",
                "Your subscription to " + saved.getPlanName() + " is now active"
        );

        // --- Push notification via SSE ---
        notificationService.pushNotificationToUser(user.getId(), n);

        return saved;
    }

    private boolean hasEverHadPaidSubscription(User user) {
        return repo.existsByUserAndPriceCentsGreaterThan(user, 0);
    }



    @Transactional
    public Subscription cancel(Subscription sub) {
        sub.setStatus("canceled");
        sub.setCanceledAt(Instant.now());

        return repo.save(sub);
    }

    public Subscription resume(Subscription sub) {
        sub.setStatus("active");

        Instant now = Instant.now();
        Instant currentEnd = sub.getCurrentPeriodEnd();

        if (currentEnd == null || currentEnd.isBefore(now)) {
            currentEnd = now;
        }

        // Use Duration instead of Period because Instant does not support months/years
        if ("yearly".equalsIgnoreCase(sub.getBillingCycle())) {
            sub.setCurrentPeriodEnd(currentEnd.plus(Duration.ofDays(365))); // Approx 1 year
        } else {
            sub.setCurrentPeriodEnd(currentEnd.plus(Duration.ofDays(30))); // Approx 1 month
        }

        Subscription updated = repo.save(sub);

        // 🔔 Send notification
        Notification n = notificationService.create(
                sub.getUser(),
                NotificationType.SYSTEM,
                RelatedType.SUBSCRIPTION,
                updated.getId(),
                "Subscription resumed",
                "Your subscription to " + updated.getPlanName() + " has been resumed"
        );

        // 🔔 Push notification via SSE to all active user connections
        notificationService.pushNotificationToUser(sub.getUser().getId(), n);

        return updated;
    }

    private BigDecimal calculatePrice(String planId, String billingCycle) {
        if (planId == null) planId = "growth";
        if (billingCycle == null) billingCycle = "monthly";

        return switch (planId.toLowerCase()) {
            case "growth" -> switch (billingCycle.toLowerCase()) {
                case "yearly" -> new BigDecimal("59.0");
                default -> new BigDecimal("5.99");
            };
            case "pro" -> switch (billingCycle.toLowerCase()) {
                case "yearly" -> new BigDecimal("149.0");
                default -> new BigDecimal("14.99");
            };
            default -> BigDecimal.ZERO; // free or unknown plan
        };
    }

    @Transactional
    public Subscription save(Subscription sub) {
        return repo.save(sub);
    }

}
