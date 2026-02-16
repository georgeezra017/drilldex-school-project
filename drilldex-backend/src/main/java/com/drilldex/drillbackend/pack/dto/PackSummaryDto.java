// src/main/java/com/drilldex/drillbackend/pack/dto/PackSummaryDto.java
package com.drilldex.drillbackend.pack.dto;

import com.drilldex.drillbackend.pack.Pack;

import java.math.BigDecimal;

public record PackSummaryDto(
        Long id,
        String slug,
        String title,
        String creatorName,
        String coverUrl,
        String tags,
        BigDecimal price,
        int durationInSeconds,
        int beatsCount,
        double totalDurationSec,
        BigDecimal earnings,
        int sales
) {
    public static PackSummaryDto from(Pack p, BigDecimal earnings, int sales, int durationInSeconds) {
        String creator = "Unknown";
        if (p.getOwner() != null && p.getOwner().getDisplayName() != null) {
            String dn = p.getOwner().getDisplayName().trim();
            if (!dn.isBlank()) creator = dn;
        }

        String tags = (p.getTags() == null || p.getTags().isBlank())
                ? null
                : p.getTags().trim();


        int beatsCount = (p.getBeats() != null) ? p.getBeats().size() : 0;
        double totalDurationSec = 0;
        if (p.getBeats() != null && !p.getBeats().isEmpty()) {
            totalDurationSec = p.getBeats().stream()
                    .mapToDouble(b -> {
                        try {
                            return b.getDurationInSeconds();
                        } catch (Exception e) {
                            return 0;
                        }
                    })
                    .sum();
        }

        return new PackSummaryDto(
                p.getId(),
                p.getSlug(),
                p.getTitle(),
                creator,
                toPublicUrl(p.getCoverImagePath()),
                tags,
                p.getPrice() == null ? BigDecimal.ZERO : p.getPrice(),
                durationInSeconds,
                beatsCount,
                totalDurationSec,
                earnings,
                sales
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