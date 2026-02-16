package com.drilldex.drillbackend.kit.dto;

import com.drilldex.drillbackend.kit.Kit;
import com.drilldex.drillbackend.promotions.Promotion;
import com.drilldex.drillbackend.user.User;

import java.math.BigDecimal;
import java.time.Instant;

public record KitSummaryDto(
        Long id,
        String slug,
        String title,
        String type,
        int samples,
        int presets,
        int loops,
        BigDecimal price,
        BigDecimal earnings,
        int sales,                      // ✅ NEW
        long totalDurationSec,
        long downloads,
        int licenses,
        String status,
        Instant updatedAt,
        String coverUrl,
        String tags,
        String uploader,
        Long ownerId,
        Boolean liked
) {
    // no promo version
    public static KitSummaryDto from(Kit k, BigDecimal earnings, int sales, long totalDurationSec, User currentUser) {
        boolean liked = currentUser != null && k.getUpvotedBy() != null
                && k.getUpvotedBy().stream().anyMatch(u -> u.getId().equals(currentUser.getId()));

        return new KitSummaryDto(
                k.getId(),
                k.getSlug(),
                k.getTitle(),
                k.getType(),
                k.getSamplesCount(),
                k.getPresetsCount(),
                k.getLoopsCount(),
                k.getPrice(),
                earnings,
                sales, // ✅ NEW
                totalDurationSec,
                k.getDownloads(),
                k.getLicenses(),
                k.getStatus(),
                k.getUpdatedAt(),
                toPublicUrl(k.getCoverImagePath()),
                k.getTags() == null || k.getTags().isBlank() ? null : k.getTags().trim(),
                k.getOwner() != null ? k.getOwner().getDisplayName() : null,
                k.getOwner() != null ? k.getOwner().getId() : null,
                liked
        );
    }

    // promo-aware version
    public static KitSummaryDto from(Kit k, Promotion promo, BigDecimal earnings, int sales, long totalDurationSec, User currentUser) {
        return from(k, earnings, sales, totalDurationSec, currentUser);
    }

    private static String toPublicUrl(String path) {
        if (path == null || path.isBlank()) return null;
        String p = path.trim();
        String pl = p.toLowerCase();
        if (pl.startsWith("http://") || pl.startsWith("https://")) return p;
        if (pl.startsWith("/uploads/")) return p;
        return "/uploads/" + p;
    }
}