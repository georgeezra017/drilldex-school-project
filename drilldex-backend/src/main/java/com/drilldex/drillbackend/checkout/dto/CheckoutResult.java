package com.drilldex.drillbackend.checkout.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutResult {
    private boolean success;
    private String message;

    private String orderId;
    private String provider;          // "test" (local simulation)
    private String confirmationId;    // Stripe PaymentIntent ID, PayPal order ID, etc.

    private List<String> licenseUrls;   // License PDF links
    private List<String> downloadUrls;  // ZIP or beat/kit/pack download URLs
    private List<String> subscriptionPayloads; // e.g., planId-tier-days
    private List<String> promotionPayloads;    // e.g., targetType-targetId-tier-days
}
