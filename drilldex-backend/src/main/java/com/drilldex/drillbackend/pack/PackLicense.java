package com.drilldex.drillbackend.pack;

import com.drilldex.drillbackend.beat.LicenseType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class PackLicense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LicenseType type;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean enabled = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pack_id", nullable = false)
    private Pack pack;
}