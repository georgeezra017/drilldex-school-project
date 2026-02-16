package com.drilldex.drillbackend.subscription.dto;

import com.drilldex.drillbackend.subscription.Subscription;

import java.time.Instant;

public record SubscriptionDto(
        Long id,
        String planId,
        String planName,
        String billingCycle,
        Integer priceCents,
        String status,
        Instant startedAt,
        Instant currentPeriodEnd,
        Instant canceledAt,
        String manageUrl,
        String planLabel // ✅ NEW FIELD
) {
    public static SubscriptionDto from(Subscription sub) {
        return new SubscriptionDto(
                sub.getId(),
                sub.getPlanId(),
                sub.getPlanName(),
                sub.getBillingCycle(),
                sub.getPriceCents(),
                sub.getStatus(),
                sub.getStartedAt(),
                sub.getCurrentPeriodEnd(),
                sub.getCanceledAt(),
                sub.getManageUrl(),
                formatLabel(sub.getPlanName(), sub.getBillingCycle()) // ✅ set computed field
        );
    }

    private static String formatLabel(String planName, String billingCycle) {
        if (planName == null || planName.isBlank()) return "Unknown Plan";

        String cycle = (billingCycle == null || billingCycle.isBlank())
                ? "Monthly"
                : capitalize(billingCycle);

        return planName + " " + cycle;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }
}