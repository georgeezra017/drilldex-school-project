package com.drilldex.drillbackend.pack.dto;

import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.pack.Pack;

import java.math.BigDecimal;

public record FeaturedPackDto(
        Long id,
        String slug,
        String title,
        String creatorName,
        Long ownerId,
        String coverUrl,
        String tags,
        BigDecimal price,
        Integer trackCount,
        String totalDuration
) {
    public static FeaturedPackDto from(Pack p) {
        String creator = "Unknown";
        Long ownerId = null;

        if (p.getOwner() != null) {
            if (p.getOwner().getDisplayName() != null && !p.getOwner().getDisplayName().isBlank()) {
                creator = p.getOwner().getDisplayName().trim();
            }
            ownerId = p.getOwner().getId();  // <-- set ownerId here
        }

        String tags = (p.getTags() == null || p.getTags().isBlank()) ? null : p.getTags().trim();

        int count = (p.getBeats() != null) ? p.getBeats().size() : 0;

        int totalSec = 0;
        if (p.getBeats() != null) {
            totalSec = p.getBeats().stream()
                    .mapToInt(Beat::getDurationInSeconds)
                    .sum();
        }

        String formattedDuration = String.format("%d:%02d", totalSec / 60, totalSec % 60);

        return new FeaturedPackDto(
                p.getId(),
                p.getSlug(),
                p.getTitle(),
                creator,
                ownerId,
                toPublicUrl(p.getCoverImagePath()),
                tags,
                p.getPrice() == null ? BigDecimal.ZERO : p.getPrice(),
                count,
                formattedDuration
        );
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