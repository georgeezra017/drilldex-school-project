package com.drilldex.drillbackend.subscription.dto;

public record SubscriptionStartRequest(
        String planId,
        String planName,
        String billingCycle, // "monthly" | "yearly"
        Integer priceCents,
        Integer trialDays,    // optional; if > 0, period end = now + trialDays
        String paymentMethod  // "test" (local simulation)
) {}
