// src/main/java/com/drilldex/drillbackend/purchase/Purchase.java
package com.drilldex.drillbackend.purchase;

import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.beat.LicenseType;
import com.drilldex.drillbackend.kit.Kit;
import com.drilldex.drillbackend.pack.Pack;
import com.drilldex.drillbackend.user.User;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
public class Purchase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = true, length = 64)
    private String orderId;

    @Column(name = "stems_path")
    private String stemsPath;

    @ManyToOne(optional = false)
    private User buyer;

    // Exactly one of (beat, pack) is set
    @ManyToOne
    private Pack pack;

    @ManyToOne
    private Beat beat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kit_id")
    private Kit kit;

    // Null for PACK purchases
    @Enumerated(EnumType.STRING)
    @Column(nullable = true, length = 20)
    private LicenseType licenseType;

    // Allow null for schema update; set in service
    @Column(nullable = true, precision = 12, scale = 2)
    private BigDecimal pricePaid;

    // Allow null for schema update; default at persist
    @Column(nullable = true)
    private Instant purchasedAt;

    // Generated license PDF path; allow null for existing rows
    @Column(nullable = true, length = 512)
    private String licensePdfPath;

    // Only for PACK purchases; null otherwise
    @Column(nullable = true, length = 512)
    private String zipDownloadPath;

    @Column(nullable = true, length = 3)
    private String currency;

    // --- New fields for promotions ---
    @Column(nullable = true, length = 16)
    private String promotionTargetType; // "BEAT", "PACK", "KIT"

    @Column(nullable = true)
    private Long promotionTargetId;

    @Column(nullable = true, length = 16)
    private String promotionTier; // "standard", "premium", "spotlight"

    @Column(nullable = true)
    private Integer promotionDays;

    @Column(nullable = true, length = 32)
    private String paymentMethod;

    @Column(name = "paid_out", nullable = false)
    private boolean paidOut = false;


    @PrePersist
    void prePersist() {
        if (purchasedAt == null) purchasedAt = Instant.now();
        // You can also enforce runtime checks here if you want:
        // if (pricePaid == null) throw new IllegalStateException("pricePaid required");
        // If it's a beat purchase, ensure licenseType is set; for packs it can be null.
    }
}
