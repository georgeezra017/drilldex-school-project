package com.drilldex.drillbackend.user.dto;

import com.drilldex.drillbackend.subscription.Subscription;
import com.drilldex.drillbackend.subscription.dto.SubscriptionDto;
import com.drilldex.drillbackend.user.User;

import java.math.BigDecimal;

public record MeDto(
        Long id,
        String displayName,
        String username,
        String avatarUrl,
        String bannerUrl,
        String bio,
        String role,
        String instagram,
        String twitter,
        String youtube,
        String facebook,
        String soundcloud,
        String tiktok,
        Totals totals,
        long followers,
        boolean oauthUser,
        SubscriptionDto subscription,
        BigDecimal promoCredits,
        BigDecimal referralCredits
) {

    /** Build MeDto including active subscription */
    public static MeDto of(User u, Totals totals, long followers, Subscription activeSub, BigDecimal promoCredits,  BigDecimal referralCredits) {
        return new MeDto(
                u.getId(),
                u.getDisplayName(),
                u.getDisplayName(),
                u.getProfilePicturePath(),
                u.getBannerImagePath(),
                u.getBio(),
                u.getRole() != null ? u.getRole().name() : null,
                u.getInstagram(),
                u.getTwitter(),
                u.getYoutube(),
                u.getFacebook(),
                u.getSoundcloud(),
                u.getTiktok(),
                totals,
                followers,
                u.getPassword() == null || u.getPassword().isBlank(),
                activeSub != null ? SubscriptionDto.from(activeSub) : null,
                promoCredits,
                referralCredits
        );
    }

    /** Build MeDto without active subscription info */
    public static MeDto of(User u, Totals totals, long followers, BigDecimal promoCredits,  BigDecimal referralCredits) {
        return new MeDto(
                u.getId(),
                u.getDisplayName(),
                u.getDisplayName(),
                u.getProfilePicturePath(),
                u.getBannerImagePath(),
                u.getBio(),
                u.getRole() != null ? u.getRole().name() : null,
                u.getInstagram(),
                u.getTwitter(),
                u.getYoutube(),
                u.getFacebook(),
                u.getSoundcloud(),
                u.getTiktok(),
                totals,
                followers,
                u.getPassword() == null || u.getPassword().isBlank(),
                null, // subscription not included,
                promoCredits,
                referralCredits
        );
    }
    public MeDto withSyntheticSubscription(SubscriptionDto sub) {
        return new MeDto(
                id, displayName, username, avatarUrl, bannerUrl, bio, role,
                instagram, twitter, youtube, facebook, soundcloud, tiktok,
                totals, followers, oauthUser,
                sub, // ← replace subscription here
                promoCredits, referralCredits
        );
    }

    public record Totals(
            long beats,
            long packs,
            long kits,
            long tracks,
            long beatsInPacks,
            long kitSounds,
            long plays
    ) {}
}