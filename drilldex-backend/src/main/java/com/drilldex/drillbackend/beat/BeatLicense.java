// domain/beat/BeatLicense.java
package com.drilldex.drillbackend.beat;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Entity
@Table(
        name = "beat_license",
        uniqueConstraints = @UniqueConstraint(columnNames = {"beat_id", "type"})
)
public class BeatLicense {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "beat_id", nullable = false)
    private Beat beat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LicenseType type; // MP3, WAV, PREMIUM, EXCLUSIVE

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean enabled = true;

    // getters/setters
}