package com.drilldex.drillbackend.kit;// src/main/java/com/drilldex/drillbackend/kit/Kit.java


import com.drilldex.drillbackend.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
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
public class Kit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "total_duration_sec", nullable = false)
    private int durationInSeconds = 0;

    /** Legacy toggle (quick “featured” without a window). */
    @Column(nullable = false)
    private boolean featured = false;

    /** Legacy timestamp for featured ordering. */
    private Instant featuredAt;

    @Column(nullable = false, unique = true, length = 191)
    private String slug;

    @Column(nullable = false)
    private long playCount = 0L;

    @Column(nullable = false)
    private int likeCount = 0;

    @Column(nullable = false)
    private int commentCount = 0;

    @ManyToMany
    @JoinTable(
            name = "kit_like",
            joinColumns = @JoinColumn(name = "kit_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"),
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_kit_like_kit_user",
                    columnNames = {"kit_id","user_id"}
            )
    )
    private Set<User> upvotedBy = new HashSet<>();

    @OneToMany(mappedBy = "kit", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<KitComment> comments = new ArrayList<>();

    /** Paid feature window (active if now ∈ [featuredFrom, featuredUntil)). */
    private Instant featuredFrom;
    private Instant featuredUntil;

    /** Tier: "standard", "premium", "spotlight" (lowercased). */
    @Column(length = 32)
    private String featuredTier;

    @Column(length = 2000)
    private String moderationNote;

    @Column(length = 512)
    private String tags;

    // pricing / stats
    @Column(nullable = false)
    private BigDecimal price = BigDecimal.ZERO;

    @Column(nullable = false)
    private long downloads = 0L;

    @Column(nullable = false)
    private int licenses = 0;

    // optional musical meta
    private Integer bpmMin;
    private Integer bpmMax;
    private String keySignature;
    private Instant publishedAt;

    private String coverImagePath;
    private String previewAudioPath;
    @ElementCollection
    @CollectionTable(name = "kit_files", joinColumns = @JoinColumn(name = "kit_id"))
    @Column(name = "path")
    private List<String> filePaths = new ArrayList<>();

    // derived content counts (for UI cards/table)
    @Column(nullable = false)
    private int samplesCount = 0;
    @Column(nullable = false)
    private int loopsCount = 0;
    @Column(nullable = false)
    private int presetsCount = 0;

    // moderation / status
    @Column(nullable = false)
    private String status = "published";

    // ownership
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User owner;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}