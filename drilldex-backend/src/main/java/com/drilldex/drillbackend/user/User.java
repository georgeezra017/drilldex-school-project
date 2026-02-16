package com.drilldex.drillbackend.user;

import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.subscription.Subscription;
import com.drilldex.drillbackend.token.RefreshToken;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.util.*;

@Entity
@Data
@ToString(exclude = {"refreshTokens", "likedBeats", "followers", "following"})
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String displayName;

    @Column(nullable = true, unique = true)
    private String email;

    @Column(nullable = true)
    private String password;

    private String plan;
    private String planBillingCycle;
    private Integer trialDaysLeft;
    private Instant subscriptionStart;

    @Column(name = "profile_picture_path")
    private String profilePicturePath;

    @Column(name = "banner_image_path", length = 1024)
    private String bannerImagePath;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(unique = true)
    private String googleId;

    @Column(length = 255)
    private String instagram;

    @Column(length = 255)
    private String twitter;

    @Column(length = 255)
    private String youtube;

    @Column(length = 255)
    private String facebook;

    @Column(length = 255)
    private String soundcloud;

    @Column(length = 255)
    private String tiktok;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private BigDecimal promoCredits = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal referralCredits = BigDecimal.ZERO; // new: earned via referrals

    @Column(nullable = false)
    private int referralCount = 0; // optional: number of referrals

    @Column(nullable = true)
    private String referralCode; // optional: unique code or ID for referral links

    @JsonIgnore
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RefreshToken> refreshTokens = new ArrayList<>();

    @ManyToMany(mappedBy = "upvotedBy")
    private Set<Beat> likedBeats = new HashSet<>();

    @ManyToMany
    @JoinTable(
            name = "user_followers",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "follower_id")
    )
    private Set<User> followers = new HashSet<>();

    @ManyToMany(mappedBy = "followers")
    private Set<User> following = new HashSet<>();

    // --- Subscription state (authoritative) ---
    private Instant trialEndsAt;        // replaces trialDaysLeft for checks
    private Instant currentPeriodEnd;   // provider’s current period end
    private Instant canceledAt;         // set when user clicks cancel (keeps access until currentPeriodEnd)
    @Column(nullable = false)
    private boolean paused = false;

    @Column(nullable = false)
    private boolean banned = false;

    public boolean isBanned() {
        return banned;
    }

    public void setBanned(boolean banned) {
        this.banned = banned;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return id != null && id.equals(user.id); // Match only on ID
    }

    @Transient
    @JsonIgnore
    private Subscription activeSubscription;



    @Override
    public int hashCode() {
        return 31;
    }

    public boolean isSubscriptionActive() {
        // Free or missing plan → not active
        if (plan == null || "free".equalsIgnoreCase(plan)) return false;

        final Instant now = Instant.now();

        // Paused means not active
        if (paused) return false;

        // Trial window (if set) grants access
        if (trialEndsAt != null && now.isBefore(trialEndsAt)) return true;

        // Paid window (set from billing provider/webhook) grants access
        if (currentPeriodEnd != null && now.isBefore(currentPeriodEnd)) return true;

        // Otherwise not active
        return false;
    }

    public void addReferralCredits(BigDecimal amount) {
        if (referralCredits == null) referralCredits = BigDecimal.ZERO;
        referralCredits = referralCredits.add(amount);
        referralCount++;
    }

}
