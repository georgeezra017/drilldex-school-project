package com.drilldex.drillbackend.purchase.dto;

// dto/SubscriptionPurchaseRequest.java
public record SubscriptionPurchaseRequest(
        String planId,         // "free" | "growth" | "pro"
        String billingCycle,   // "monthly" | "yearly"
        int trialDays          // optional (0 = none)
) {}