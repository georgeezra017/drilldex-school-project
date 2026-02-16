package com.drilldex.drillbackend.me;

import com.drilldex.drillbackend.pack.Pack;
import com.drilldex.drillbackend.user.User;

import java.math.BigDecimal;
import java.time.Instant;

public record PackSummaryDto(
        Long id,
        String artistName,
        String slug,
        String title,
        BigDecimal price,
        long playCount,
        int likeCount,
        int sales,
        BigDecimal earnings,
        Instant createdAt,
        Instant updatedAt,
        String coverUrl,
        String coverImagePath,
        String tags,
        String kind,
        String status,
        Boolean liked,
        Boolean hasStems,   // ✅ NEW
        String stemsPath    // ✅ NEW
) {
    public static PackSummaryDto from(Pack p, int sales, BigDecimal earnings, User currentUser) {
        String cover = p.getCoverImagePath();
        Instant created = p.getCreatedAt();
        Instant updated = (p.getUpdatedAt() != null) ? p.getUpdatedAt() : created;

        boolean liked = currentUser != null &&
                p.getUpvotedBy() != null &&
                p.getUpvotedBy().stream().anyMatch(u -> u.getId().equals(currentUser.getId()));

        boolean hasStems = p.getStemsFilePath() != null && !p.getStemsFilePath().isBlank();

        return new PackSummaryDto(
                p.getId(),
                p.getOwner().getDisplayName(),
                p.getSlug(),
                p.getTitle(),
                p.getPrice(),
                p.getPlayCount(),
                p.getLikeCount(),
                sales,
                earnings,
                created,
                updated,
                cover,
                cover,
                p.getTags(),
                "Pack",
                "published",
                liked,
                hasStems,
                hasStems ? p.getStemsFilePath() : null
        );
    }
}