package com.drilldex.drillbackend.checkout;

import com.drilldex.drillbackend.beat.LicenseType;
import com.drilldex.drillbackend.checkout.dto.CheckoutRequest;
import com.drilldex.drillbackend.checkout.dto.CheckoutResult;
import com.drilldex.drillbackend.checkout.dto.CheckoutSession;
import com.drilldex.drillbackend.checkout.dto.ConfirmCheckoutRequest;
import com.drilldex.drillbackend.promotions.PromotionRequest;
import com.drilldex.drillbackend.promotions.PromotionService;
import com.drilldex.drillbackend.purchase.PurchaseService;
import com.drilldex.drillbackend.subscription.Subscription;
import com.drilldex.drillbackend.subscription.SubscriptionService;
import com.drilldex.drillbackend.subscription.dto.SubscriptionStartRequest;
import com.drilldex.drillbackend.user.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CheckoutServiceImpl implements CheckoutService {

    private final PurchaseService purchaseService;
    private final SubscriptionService subscriptionService;
    private final PromotionService promotionService;

    @Override
    public CheckoutSession start(User user, CheckoutRequest request, HttpServletRequest httpRequest) {
        for (CheckoutRequest.CartItem i : request.getItems()) {
            System.out.println("[CHECKOUT] Item received: type=" + i.getType()
                    + ", targetId=" + i.getBeatId() + "/" + i.getPackId() + "/" + i.getKitId()
                    + ", tier=" + i.getTier()
                    + ", days=" + i.getDays());
        }

        CheckoutSession session = new CheckoutSession();

        boolean hasSubscription = request.getItems().stream()
                .anyMatch(i -> "subscription".equalsIgnoreCase(i.getType()));
        boolean hasOneTime = request.getItems().stream()
                .anyMatch(i -> !"subscription".equalsIgnoreCase(i.getType()));

        if (hasSubscription && hasOneTime) {
            throw new IllegalArgumentException(
                    "Subscriptions cannot be purchased together with other items. Please checkout separately."
            );
        }

        // Local-only simulated payment session
        session.setProvider("test");
        session.setSessionId("local_" + java.util.UUID.randomUUID());
        session.setOrderId(java.util.UUID.randomUUID().toString());
        session.setRequiresRedirect(false);
        session.setPaymentUrl(null);
        session.setClientSecret(null);
        return session;
    }

    @Override
    public CheckoutResult confirm(User user, ConfirmCheckoutRequest request, String orderId) {
        String provider = request.getProvider() != null ? request.getProvider() : "test";
        if (!"test".equalsIgnoreCase(provider)) {
            throw new IllegalArgumentException("Only local test payments are supported in the school build");
        }

        String confirmationId = null;

        List<ConfirmCheckoutRequest.CartItem> items = request.getItems();
        List<String> promotionPayloads = new ArrayList<>();
        List<String> subscriptionPayloads = new ArrayList<>();

        for (ConfirmCheckoutRequest.CartItem item : items) {
            String type = item.getType().toLowerCase();

            try {
                switch (type) {
                    case "subscription" -> {
                        if (user == null) {
                            throw new IllegalArgumentException("You must be logged in to purchase a subscription");
                        }

                        confirmationId = "local_sub_" + java.util.UUID.randomUUID();

                        String planName = item.getPlanId();
                        String billingCycle = item.getBillingCycle();
                        if (billingCycle == null || (!"monthly".equalsIgnoreCase(billingCycle) && !"yearly".equalsIgnoreCase(billingCycle))) {
                            throw new IllegalArgumentException(
                                    "Invalid or missing billingCycle for subscription: " + planName
                            );
                        }
                        billingCycle = billingCycle.toLowerCase();

                        SubscriptionStartRequest subReq = new SubscriptionStartRequest(
                                planName,           // planId
                                planName,           // planName
                                billingCycle,       // billingCycle
                                null,               // trialDays
                                item.getDays(),     // days
                                provider            // payment method
                        );
                        Subscription sub = subscriptionService.start(user, subReq, orderId);
                        sub.setPaymentProvider(provider);
                        sub.setProviderSubscriptionId(confirmationId);
                        subscriptionService.save(sub);

                        subscriptionPayloads.add(planName + "-" + billingCycle + "-" + item.getDays());
                    }
                    case "beat" -> purchaseService.buyBeat(user, item.getBeatId(),
                            item.getLicenseType() != null
                                    ? LicenseType.valueOf(item.getLicenseType().toUpperCase())
                                    : null, orderId);
                    case "pack" -> purchaseService.buyPack(user, item.getPackId(),
                            item.getLicenseType() != null
                                    ? LicenseType.valueOf(item.getLicenseType().toUpperCase())
                                    : null, orderId);
                    case "kit" -> purchaseService.buyKit(user, item.getKitId(), orderId);
                    case "promotion" -> {
                        if (user == null) {
                            throw new IllegalArgumentException("You must be logged in to purchase a promotion");
                        }

                        Long targetId = item.getBeatId() != null ? item.getBeatId()
                                : item.getPackId() != null ? item.getPackId()
                                : item.getKitId();

                        if (targetId == null) {
                            throw new IllegalArgumentException("Promotion must target a beat, pack, or kit");
                        }

                        String targetType;
                        if (item.getBeatId() != null) targetType = "BEAT";
                        else if (item.getPackId() != null) targetType = "PACK";
                        else targetType = "KIT";

                        String tier = item.getTier() != null ? item.getTier() : "standard";
                        int days = item.getDays() > 0 ? item.getDays() : 1;

                        PromotionRequest req = new PromotionRequest();
                        req.setTargetType(targetType);
                        req.setTargetId(targetId);
                        req.setTier(tier);
                        req.setDays(days);
                        req.setPaymentMethod(provider);
                        req.setOrderId(orderId);

                        promotionService.promote(user, List.of(req));

                        java.math.BigDecimal backendPrice = item.getPrice();
                        purchaseService.buyPromotion(user, targetType, targetId, tier, days, backendPrice, orderId, provider);

                        promotionPayloads.add(targetType + "-" + targetId + "-" + tier + "-" + days);
                    }
                    default -> throw new IllegalArgumentException("Unknown cart item type: " + type);
                }
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to process item: " + item.getType() + " / id=" +
                                (item.getBeatId() != null ? item.getBeatId() :
                                        item.getPackId() != null ? item.getPackId() :
                                                item.getKitId()), e);
            }
        }

        if (subscriptionPayloads.isEmpty()) {
            confirmationId = "local_" + java.util.UUID.randomUUID();
        }

        CheckoutResult result = new CheckoutResult();
        result.setSuccess(true);
        result.setMessage("Payment confirmed and items processed");
        result.setConfirmationId(confirmationId);
        result.setOrderId(orderId);
        result.setSubscriptionPayloads(subscriptionPayloads);
        result.setPromotionPayloads(promotionPayloads);

        return result;
    }
}
