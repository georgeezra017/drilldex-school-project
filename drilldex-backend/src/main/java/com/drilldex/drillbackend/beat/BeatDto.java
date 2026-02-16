package com.drilldex.drillbackend.beat;

import java.math.BigDecimal;
import java.time.Instant;

public record BeatDto(
        Long id,
        String slug,
        String title,
        String artistName,
        Long ownerId,
        String audioUrl,
        Long playCount,
        int durationInSeconds,
        String genre,
        String tags,
        BigDecimal price,
        String albumCoverUrl,
        int likeCount,
        Integer commentCount,
        boolean featured,
        Instant featuredAt,
        String coverUrl,
        String previewUrl,
        Integer bpm,
        Instant createdAt,
        boolean liked,
        Integer sales,
        BigDecimal earnings


) {
}