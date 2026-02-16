package com.drilldex.drillbackend.checkout.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutSession {
    private String provider;         // "test" (local simulation)
    private String sessionId;        // PayPal Order ID or Stripe PaymentIntent ID
    private String paymentUrl;       // For redirect-based flows (e.g., PayPal)
    private boolean requiresRedirect; // Whether frontend should open a redirect
    private String clientSecret;     // Optional: Stripe client_secret (for frontend handling)
    private String orderId;
}
