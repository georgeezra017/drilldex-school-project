package com.drilldex.drillbackend.me;

import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.user.User;

import java.math.BigDecimal;
import java.time.Instant;

public record BeatSummaryDto(
        Long id,
        String tags,
        String artistName,
        String slug,
        String title,
        String genre,
        Integer bpm,
        BigDecimal price,
        Long plays,
        Integer likes,
        Integer sales,
        BigDecimal earnings,
        Instant updatedAt,
        String coverUrl,
        String coverImagePath,
        String kind,
        String status,
        Boolean liked,
        Boolean hasStems,     // ✅ NEW
        String stemsPath      // ✅ NEW
) {
    public static BeatSummaryDto from(Beat b, int sales, BigDecimal earnings, User currentUser) {
        String url = b.getAlbumCoverUrl();
        if ((url == null || url.isBlank()) && b.getCoverImagePath() != null) {
            url = b.getCoverImagePath().replace("\\", "/");
        }

        boolean liked = currentUser != null &&
                b.getUpvotedBy() != null &&
                b.getUpvotedBy().stream().anyMatch(u -> u.getId().equals(currentUser.getId()));

        boolean hasStems = b.getStemsFilePath() != null && !b.getStemsFilePath().isBlank();

        return new BeatSummaryDto(
                b.getId(),
                b.getTags(),
                b.getArtist(),
                b.getSlug(),
                b.getTitle(),
                b.getGenre(),
                b.getBpm(),
                b.getPrice(),
                b.getPlayCount() == null ? 0L : b.getPlayCount(),
                b.getLikeCount(),
                sales,
                earnings,
                b.getCreatedAt(), // ✅ fallback since Beat has no updatedAt
                url,
                b.getCoverImagePath(),
                "Beat",
                "published",
                liked,
                hasStems,
                hasStems ? b.getStemsFilePath() : null
        );
    }
}