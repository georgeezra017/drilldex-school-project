// src/main/java/com/drilldex/drillbackend/purchase/PurchaseService.java
package com.drilldex.drillbackend.purchase;

import com.drilldex.drillbackend.beat.*;
import com.drilldex.drillbackend.kit.Kit;
import com.drilldex.drillbackend.kit.KitRepository;
import com.drilldex.drillbackend.licensing.LicensePdfGenerator;
import com.drilldex.drillbackend.licensing.LicenseTerms;
import com.drilldex.drillbackend.licensing.LicenseTermsConfig;
import com.drilldex.drillbackend.notification.Notification;
import com.drilldex.drillbackend.notification.NotificationService;
import com.drilldex.drillbackend.notification.NotificationType;
import com.drilldex.drillbackend.notification.RelatedType;
import com.drilldex.drillbackend.pack.Pack;
import com.drilldex.drillbackend.pack.PackLicense;
import com.drilldex.drillbackend.pack.PackRepository;
import com.drilldex.drillbackend.promotions.Promotion;
import com.drilldex.drillbackend.promotions.PromotionRequest;
import com.drilldex.drillbackend.promotions.PromotionService;
import com.drilldex.drillbackend.subscription.Subscription;
import com.drilldex.drillbackend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PurchaseService {

    private final BeatRepository beatRepository;
    private final PurchaseRepository purchaseRepository;
    private final PackRepository packRepository;
    private final KitRepository kitRepository;
    private final NotificationService notificationService;
    private final PromotionService promotionService;

    @Value("${app.payments.currency:USD}")
    private String defaultCurrency;

//    @Transactional
//    public Purchase buyBeat(User buyer, Long beatId, LicenseType licenseType) throws Exception {
//        Beat beat = beatRepository.findById(beatId)
//                .orElseThrow(() -> new IllegalArgumentException("Beat not found"));
//
//        // find enabled license/price for the chosen type
//        BeatLicense selected = beat.getLicenses().stream()
//                .filter(l -> l.getType() == licenseType && l.isEnabled())
//                .findFirst()
//                .orElseThrow(() -> new IllegalArgumentException("Selected license not available for this beat"));
//
//        // generate license PDF (terms driven by license type)
//        LicenseTerms terms = LicenseTermsConfig.getTermsFor(licenseType);
//        String pdfPath = LicensePdfGenerator.generateLicensePdf(buyer, beat, terms);
//
//        Purchase p = new Purchase();
//        p.setBuyer(buyer);
//        p.setBeat(beat);
//        p.setLicenseType(licenseType);
//        p.setPricePaid(selected.getPrice());
//        p.setCurrency(defaultCurrency);
//        p.setPurchasedAt(Instant.now());
//        p.setLicensePdfPath(pdfPath);
//
//        Purchase saved = purchaseRepository.save(p);
//
//        // --- 🔔 Send notification to buyer ---
//        Notification n = notificationService.create(
//                buyer,
//                NotificationType.PURCHASE,
//                RelatedType.BEAT,
//                saved.getId(),
//                "Purchase complete",
//                "You purchased " + beat.getTitle()
//        );
//
//// Push notification via SSE to all active emitters for this user
//        notificationService.pushNotificationToUser(buyer.getId(), n);
//
//        return saved;
//    }

@Transactional
public Purchase buyBeat(User buyer, Long beatId, LicenseType licenseType, String orderId) throws Exception {
    Beat beat = beatRepository.findById(beatId)
            .orElseThrow(() -> new IllegalArgumentException("Beat not found"));

    BeatLicense selected = beat.getLicenses().stream()
            .filter(l -> l.getType() == licenseType && l.isEnabled())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Selected license not available for this beat"));

    LicenseTerms terms = LicenseTermsConfig.getTermsFor(licenseType);
    String pdfPath = LicensePdfGenerator.generateLicensePdf(buyer, beat, terms);

    Purchase p = new Purchase();
    p.setBuyer(buyer);
    p.setBeat(beat);
    p.setLicenseType(licenseType);
    p.setPricePaid(selected.getPrice());
    p.setCurrency(defaultCurrency);
    p.setPurchasedAt(Instant.now());
    p.setLicensePdfPath(pdfPath);
    p.setOrderId(orderId); // <--- NEW

    if ((licenseType == LicenseType.PREMIUM || licenseType == LicenseType.EXCLUSIVE)
            && beat.getStemsFilePath() != null) {
        p.setStemsPath(beat.getStemsFilePath());
    }

    Purchase saved = purchaseRepository.save(p);

    Notification n = notificationService.create(
            buyer,
            NotificationType.PURCHASE,
            RelatedType.BEAT,
            saved.getId(),
            "Purchase complete",
            "You purchased " + beat.getTitle()
    );

    notificationService.pushNotificationToUser(buyer.getId(), n);

    if ((licenseType == LicenseType.PREMIUM || licenseType == LicenseType.EXCLUSIVE)
            && beat.getStemsFilePath() == null) {
        Notification producerNotif = notificationService.create(
                beat.getOwner(),
                NotificationType.SYSTEM,
                RelatedType.BEAT,
                beat.getId(),
                "Stems missing for premium/exclusive sale",
                "A buyer purchased a premium/exclusive license for your beat '" +
                        beat.getTitle() + "', but no stems were uploaded."
        );
        notificationService.pushNotificationToUser(beat.getOwner().getId(), producerNotif);

        Notification buyerMissingNotif = notificationService.create(
                buyer,
                NotificationType.SYSTEM,
                RelatedType.BEAT,
                beat.getId(),
                "Producer notified about missing stems",
                "The producer has been notified that stems for '" + beat.getTitle() +
                        "' are missing. They will be uploaded soon."
        );
        notificationService.pushNotificationToUser(buyer.getId(), buyerMissingNotif);
    }

    return saved;
}

    public Purchase getPurchaseOwned(User buyer, Long purchaseId) {
        Purchase p = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase not found"));
        if (!p.getBuyer().getId().equals(buyer.getId())) {
            throw new SecurityException("Not your purchase");
        }
        return p;
    }

    // PurchaseService.java
    @Transactional
    public Purchase buyPack(User buyer, Long packId, LicenseType licenseType, String orderId) throws Exception {
        Pack pack = packRepository.findById(packId)
                .orElseThrow(() -> new IllegalArgumentException("Pack not found"));

        if (!Boolean.TRUE.equals(pack.isApproved())) {
            throw new IllegalStateException("Pack is not for sale");
        }

        // --- Find selected license for the pack ---
        PackLicense selected = pack.getLicenses().stream()
                .filter(l -> l.getType() == licenseType && l.isEnabled())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Selected license not available for this pack"));

        // --- Generate license PDF ---
        LicenseTerms terms = LicenseTermsConfig.getTermsFor(licenseType);
        String pdfPath = LicensePdfGenerator.generatePackLicensePdf(
                buyer, pack, terms, licenseType, selected.getPrice()
        );

        Purchase p = new Purchase();
        p.setBuyer(buyer);
        p.setPack(pack);
        p.setLicenseType(licenseType);      // store selected license
        p.setPricePaid(selected.getPrice()); // price comes from license
        p.setCurrency(defaultCurrency);
        p.setPurchasedAt(Instant.now());
        p.setLicensePdfPath(pdfPath);
        p.setOrderId(orderId);               // <-- store orderId

        if ((licenseType == LicenseType.PREMIUM || licenseType == LicenseType.EXCLUSIVE)
                && pack.getStemsFilePath() != null) {
            p.setStemsPath(pack.getStemsFilePath());
        }

        Purchase saved = purchaseRepository.save(p);

        // --- 🔔 Send notification to buyer ---
        Notification n = notificationService.create(
                buyer,
                NotificationType.PURCHASE,
                RelatedType.PACK,
                saved.getId(),
                "Purchase complete",
                "You purchased " + pack.getTitle()
        );

        notificationService.pushNotificationToUser(buyer.getId(), n);

        if ((licenseType == LicenseType.PREMIUM || licenseType == LicenseType.EXCLUSIVE)
                && pack.getStemsFilePath() == null) {
            Notification producerNotif = notificationService.create(
                    pack.getOwner(),
                    NotificationType.SYSTEM,
                    RelatedType.PACK,
                    pack.getId(),
                    "Stems missing for premium/exclusive sale",
                    "A buyer purchased a premium/exclusive license for your pack '" +
                            pack.getTitle() + "', but no stems were uploaded."
            );
            notificationService.pushNotificationToUser(pack.getOwner().getId(), producerNotif);

            Notification buyerMissingNotif = notificationService.create(
                    buyer,
                    NotificationType.SYSTEM,
                    RelatedType.PACK,
                    pack.getId(),
                    "Producer notified about missing stems",
                    "The producer has been notified that stems for '" + pack.getTitle() +
                            "' are missing. They will be uploaded soon."
            );
            notificationService.pushNotificationToUser(buyer.getId(), buyerMissingNotif);
        }

        return saved;
    }


    @Transactional
    public Purchase buyKit(User buyer, Long kitId, String orderId) throws Exception {
        System.out.println("=== buyKit called ===");
        System.out.println("Buyer: " + (buyer != null ? buyer.getId() + " / " + buyer.getDisplayName() : "null"));
        System.out.println("Kit ID: " + kitId);
        System.out.println("Order ID: " + orderId);

        Kit kit = kitRepository.findById(kitId)
                .orElseThrow(() -> {
                    System.out.println("Kit not found for id=" + kitId);
                    return new IllegalArgumentException("Kit not found");
                });

        System.out.println("Kit retrieved: " + kit.getTitle() + " / status=" + kit.getStatus() + " / price=" + kit.getPrice());

        // Availability check
        boolean isPublished = "published".equalsIgnoreCase(kit.getStatus());
        boolean hasPublishDate = kit.getPublishedAt() != null; // optional
        System.out.println("isPublished=" + isPublished + ", hasPublishDate=" + hasPublishDate);

        if (!isPublished /* || !hasPublishDate */) {
            System.out.println("Kit is not for sale: " + kit.getTitle());
            throw new IllegalStateException("Kit is not for sale");
        }

        // Build Purchase
        Purchase p = new Purchase();
        p.setBuyer(buyer);
        p.setKit(kit);
        p.setPricePaid(kit.getPrice());
        p.setCurrency(defaultCurrency);
        p.setPurchasedAt(Instant.now());
        p.setOrderId(orderId);

        System.out.println("Saving purchase: buyer=" + buyer.getId() + ", kit=" + kit.getId() +
                ", price=" + kit.getPrice() + ", currency=" + defaultCurrency);

        Purchase saved = purchaseRepository.save(p);
        System.out.println("Purchase saved with ID: " + saved.getId());

        // Notification
        Notification n = notificationService.create(
                buyer,
                NotificationType.PURCHASE,
                RelatedType.KIT,
                saved.getId(),
                "Purchase complete",
                "You purchased " + kit.getTitle()
        );

        System.out.println("Notification created with ID: " + n.getId());
        notificationService.pushNotificationToUser(buyer.getId(), n);
        System.out.println("Notification pushed to user: " + buyer.getId());

        System.out.println("=== buyKit finished ===");
        return saved;
    }

    @Transactional
    public Purchase buyPromotion(
            User buyer,
            String targetType,
            Long targetId,
            String tier,
            int days,
            BigDecimal pricePaid,
            String orderId,
            String paymentMethod
    ) {
        if (buyer == null) throw new IllegalArgumentException("Buyer cannot be null");
        if (targetId == null) throw new IllegalArgumentException("Promotion target ID cannot be null");

        Purchase purchase = new Purchase();
        purchase.setBuyer(buyer);
        purchase.setCurrency(defaultCurrency);
        purchase.setPurchasedAt(Instant.now());
        purchase.setOrderId(orderId);
        purchase.setPaymentMethod(paymentMethod != null ? paymentMethod : "test");

        // Store promotion metadata
        purchase.setPromotionTargetType(targetType.toUpperCase());
        purchase.setPromotionTargetId(targetId);
        purchase.setPromotionTier(tier);
        purchase.setPromotionDays(days);
        purchase.setPricePaid(pricePaid); // <-- use the price from CheckoutRequest

        return purchaseRepository.save(purchase);
    }

    public Purchase buySubscription(User user, String planId, String billingCycle, BigDecimal price, String orderId, String paymentMethod) {
        Purchase p = new Purchase();
        p.setBuyer(user);
        p.setOrderId(orderId);
        p.setPricePaid(price);
        p.setPaymentMethod(paymentMethod);
        p.setPurchasedAt(Instant.now());

        // Mark as subscription type
        p.setPromotionTargetType("SUBSCRIPTION");
        p.setPromotionTier(planId);                     // store planId (e.g., "growth" or "pro")
        p.setPromotionDays("yearly".equalsIgnoreCase(billingCycle) ? 365 : 30);
        p.setPromotionTargetId(null);                  // no subscription entity yet

        return purchaseRepository.save(p);
    }

}
