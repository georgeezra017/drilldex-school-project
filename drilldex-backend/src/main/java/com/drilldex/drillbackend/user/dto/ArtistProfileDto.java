package com.drilldex.drillbackend.user.dto;

public record ArtistProfileDto(
        Long id,
        String displayName,
        String profilePicture,
        String bannerImage,
        String bio,
        String instagram,
        String twitter,
        String youtube,
        String facebook,
        String soundcloud,
        String tiktok,
        int followersCount,
        int totalPlays,
        int trackCount,
        String plan
) {}