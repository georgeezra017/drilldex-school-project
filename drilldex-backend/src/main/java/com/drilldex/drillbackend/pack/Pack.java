package com.drilldex.drillbackend.pack;

import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.user.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Pack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Basic fields
    @Column(length = 512)
    private String tags;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, unique = true, length = 255)
    private String slug;

    @Column(nullable = false)
    private int commentCount = 0;

    @OneToMany(mappedBy = "pack", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<PackComment> comments = new java.util.ArrayList<>();

    private String albumCoverUrl;
    private String coverImagePath;

    @Column(name = "stems_file_path")
    private String stemsFilePath;

    @Column(nullable = false)
    private BigDecimal price;


    private String genre;

    // Upvotes: likeCount is derived from this
    @ManyToMany
    @JoinTable(
            name = "pack_upvotes",
            joinColumns = @JoinColumn(name = "pack_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    @JsonIgnore // never serialize entities directly; DTOs only
    private Set<User> upvotedBy = new HashSet<>();

    public int getLikeCount() {
        return upvotedBy.size();
    }

    // Ownership / relationships
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    @JsonIgnore // avoid lazy proxy serialization problems; use DTOs
    private User owner;

    @ManyToMany
    @JoinTable(
            name = "pack_beats",
            joinColumns = @JoinColumn(name = "pack_id"),
            inverseJoinColumns = @JoinColumn(name = "beat_id")
    )
    @JsonIgnore // keep response lean; expose through a detailed endpoint if needed
    private List<Beat> beats = new ArrayList<>();

    // Moderation flags (like Beat)
    @Column(nullable = false)
    private boolean approved = false;

    @Column(nullable = false)
    private boolean rejected = false;

    // Timestamps as Instant (so DTO can use Instant consistently)
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @org.hibernate.annotations.UpdateTimestamp
    @Column(nullable = false)
    private java.time.Instant updatedAt;

    /* ---- FEATURE FIELDS ---- */
    @Column(nullable=false)
    private boolean featured = false;
    private Instant featuredFrom;
    private Instant featuredUntil;

    @Column(length = 24)
    private String featuredTier;

    @Column(nullable = false)
    private long playCount = 0L;

    @Column(nullable = false)
    private int likeCount = 0;

    @OneToMany(mappedBy = "pack", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PackLicense> licenses = new ArrayList<>();


    public BigDecimal getDisplayPrice() {
        return licenses.stream()
                .filter(PackLicense::isEnabled)
                .map(PackLicense::getPrice)
                .min(BigDecimal::compareTo)
                .orElse(price != null ? price : BigDecimal.ZERO);
    }
}