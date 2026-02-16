package com.drilldex.drillbackend.checkout.dto;

import lombok.Data;

import java.util.List;
import java.math.BigDecimal;

@Data
public class ConfirmCheckoutRequest {
    private String provider;     // "test" (local simulation)
    private String sessionId;    // Order ID or PaymentIntent ID
    private String clientSecret; // Optional, used for Stripe if needed

    // The list of cart items that were purchased
    private List<CartItem> items;

    @Data
    public static class CartItem {
        private String type;           // "beat" | "pack" | "kit" | "promotion" | "subscription"
        private Long beatId;
        private Long packId;
        private Long kitId;
        private String licenseType;    // for beats/packs
        private String tier;           // for promotions/subscriptions
        private int days;              // for promotions/subscriptions
        private String planId;         // internal planId for subscriptions
        private String subscriptionId; // actual subscription ID returned by PayPal/Stripe
        private BigDecimal price;      // optional, mainly for display/verification
        private String billingCycle;
    }
}
