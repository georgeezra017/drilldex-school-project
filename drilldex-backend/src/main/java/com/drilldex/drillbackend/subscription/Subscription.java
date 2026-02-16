package com.drilldex.drillbackend.subscription;

import com.drilldex.drillbackend.user.User;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Data
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User user;

    @Column(nullable = true, length = 64)
    private String lastOrderId;

    // Plan metadata
    private String planId;          // e.g. "pro-monthly", "pro-yearly"
    private String planName;        // e.g. "Pro"
    private String billingCycle;    // "monthly" | "yearly"
    private Integer priceCents;     // optional, for UI

    @Column(nullable = true, length = 16)
    private String paymentProvider;

    // The subscription ID returned by the provider (Stripe / PayPal)
    @Column(nullable = true, length = 128)
    private String providerSubscriptionId;

    // Lifecycle
    private String status;          // "active" | "paused" | "canceled"
    private Instant startedAt;
    private Instant currentPeriodEnd;
    private Instant canceledAt;

    // Optional: link out to your billing portal/customer portal
    private String manageUrl;
}