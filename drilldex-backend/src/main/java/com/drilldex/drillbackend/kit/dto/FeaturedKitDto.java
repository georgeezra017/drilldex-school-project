// src/main/java/com/drilldex/drillbackend/kit/dto/FeaturedKitDto.java
package com.drilldex.drillbackend.kit.dto;

import com.drilldex.drillbackend.kit.Kit;
import java.math.BigDecimal;

public record FeaturedKitDto(
        Long id,
        String slug,
        String title,
        String creatorName,
        String coverUrl,
        BigDecimal price,
        String tags,
        long playCount,
        Long ownerId
) {
    public static FeaturedKitDto from(Kit k) {
        String creator = "Unknown";
        Long ownerId = null;
        if (k.getOwner() != null) {
            if (k.getOwner().getDisplayName() != null && !k.getOwner().getDisplayName().isBlank()) {
                creator = k.getOwner().getDisplayName().trim();
            }
            ownerId = k.getOwner().getId();
        }

        String tags = (k.getTags() == null || k.getTags().isBlank())
                ? null
                : k.getTags().trim();

        return new FeaturedKitDto(
                k.getId(),
                k.getSlug(),
                k.getTitle(),
                creator,
                toPublicUrl(k.getCoverImagePath()),
                k.getPrice() == null ? BigDecimal.ZERO : k.getPrice(),
                tags,
                k.getPlayCount(),
                ownerId
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