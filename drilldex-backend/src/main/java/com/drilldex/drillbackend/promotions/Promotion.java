package com.drilldex.drillbackend.promotions;

import com.drilldex.drillbackend.user.User;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

// com.drilldex.drillbackend.promotions.Promotion.java
@Entity
@Data
public class Promotion {
    @Id
    @GeneratedValue
    private Long id;

    @Enumerated(EnumType.STRING)
    private TargetType targetType; // BEAT, PACK, KIT

    private Long targetId;
    private String tier; // "standard", "premium", "spotlight"
    private int durationDays;
    private Instant startDate;

    private String status;
    private Double price;

    @ManyToOne(fetch = FetchType.LAZY)
    private User owner;

    @Column(precision = 10, scale = 2)
    private BigDecimal creditsUsed = BigDecimal.ZERO;

    public enum TargetType { BEAT, PACK, KIT }

    public boolean isActive() {
        if (status != null && status.equalsIgnoreCase("canceled")) return false;
        if (startDate == null) return false;
        return Instant.now().isBefore(startDate.plus(Duration.ofDays(durationDays)));
    }

    @Column(length = 255)
    private String targetTitleCached;


    @Column(length = 1024)
    private String targetThumbCached;


}
