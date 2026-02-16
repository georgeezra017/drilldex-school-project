package com.drilldex.drillbackend.beat;

import com.drilldex.drillbackend.album.Album;
import com.drilldex.drillbackend.user.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

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
public class Beat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Column(nullable = false)
    private boolean partOfPack = false;

    private String artist;
    //    private int bpm;
    @Column(nullable = false)
    private int durationInSeconds;

    @Column(name = "tags")
    private String tags;

    @Column(nullable = false)
    private String genre;

    private boolean onSale;

    @Column(name = "stems_file_path")
    private String stemsFilePath;

    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean featured = false;

    private Instant featuredAt;

    public boolean isFeatured() { return featured; }

    @Column(name = "featured_from")
    private Instant featuredFrom;          // when the paid slot starts

    @Column(name = "featured_until")
    private Instant featuredUntil;         // when the paid slot ends

    @Column(name = "featured_tier", length = 20)
    private String featuredTier;           // e.g. "spotlight", "premium", "standard"

    // helper (not used in queries)
    @JsonIgnore
    public boolean isFeaturedActive() {
        return featuredUntil != null && Instant.now().isBefore(featuredUntil);
    }

    private int plays = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @JsonIgnore
    private String audioFilePath;

    @JsonIgnore
    private String coverImagePath;

    @JsonIgnore
    @Column(name = "preview_audio_path")
    private String previewAudioPath;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_id")
    private User uploadedBy;

    // These are necessary if you're not using Lombok @Setter (or if @Setter is not applied to all fields)
    public void setUploadedBy(User user) {
        this.uploadedBy = user;
    }

    public void setOwner(User user) {
        this.owner = user;
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "album_id")
    private Album album;

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean isSample;

    @Column(nullable = false)
    private Long playCount = 0L;

    @Column
    private String albumCoverUrl; // URL or file path

    @Column(nullable = false)
    private BigDecimal price; // in dollars or euros, up to you BigDecimal

    @Column(nullable = false)
    private Integer bpm;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id")              // FK in beats table
    private User owner;

    @Column(nullable = false)
    private Integer likeCount = 0;

    @Column(name = "slug", nullable = false, unique = true, length = 255)
    private String slug;

    public void setLikeCount(Integer likeCount) {
        this.likeCount = likeCount;
    }


    @OneToMany(mappedBy = "beat", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<BeatLicense> licenses = new java.util.ArrayList<>();

    @Column(nullable = false)
    private boolean approved = false;

    @OneToMany(mappedBy = "beat", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt DESC")
    private List<BeatComment> comments = new ArrayList<>();

    @Column(nullable = false)
    private int commentCount = 0; // default

    @Column(nullable = false)
    private boolean rejected = false;

    @ManyToMany
    @JoinTable(
            name = "beat_like",
            joinColumns = @JoinColumn(name = "beat_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    private Set<User> upvotedBy = new HashSet<>();

    public int getLikeCount() {
        return upvotedBy.size();
    }

    public boolean isUpvotedBy(User user) {
        return upvotedBy.contains(user);
    }

    public void toggleUpvote(User user) {
        if (upvotedBy.contains(user)) {
            upvotedBy.remove(user);
        } else {
            upvotedBy.add(user);
        }
    }
}
