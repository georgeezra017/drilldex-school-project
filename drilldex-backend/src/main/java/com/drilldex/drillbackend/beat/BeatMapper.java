package com.drilldex.drillbackend.beat;

import com.drilldex.drillbackend.promotions.Promotion;
import com.drilldex.drillbackend.user.User;

import java.math.BigDecimal;
import java.util.Objects;

public class BeatMapper {

    // === Legacy overload ===
    public static BeatDto mapToDto(Beat beat) {
        return mapToDto(beat, null, null, 0, BigDecimal.ZERO);
    }

    // === Overload with user ===
    public static BeatDto mapToDto(Beat beat, User currentUser) {
        return mapToDto(beat, null, currentUser, 0, BigDecimal.ZERO);
    }

    // === ✅ New promotion-aware method ===
    public static BeatDto mapToDto(Beat beat, Promotion promo) {
        return mapToDto(beat, promo, null, 0, BigDecimal.ZERO);
    }

    // === ✅ Master method with all context ===
    public static BeatDto mapToDto(Beat beat, Promotion promo, User currentUser, int sales, BigDecimal earnings) {
        boolean liked = false;
        if (currentUser != null && beat.getUpvotedBy() != null) {
            liked = beat.getUpvotedBy().stream()
                    .anyMatch(u -> u.getId().equals(currentUser.getId()));
        }

        boolean isFeatured = promo != null && promo.isActive();
        var featuredAt = (promo != null) ? promo.getStartDate() : null;

        return new BeatDto(
                beat.getId(),
                beat.getSlug(),
                beat.getTitle(),
                beat.getOwner() != null ? beat.getOwner().getDisplayName() : null,
                beat.getOwner() != null ? beat.getOwner().getId() : null,
                null,                       // audioUrl
                beat.getPlayCount(),
                beat.getDurationInSeconds(),
                beat.getGenre(),
                beat.getTags(),
                beat.getPrice(),
                beat.getCoverImagePath(),   // raw path
                beat.getLikeCount(),
                Objects.requireNonNullElse(beat.getCommentCount(), 0),
                isFeatured,                 // ✅ dynamic
                featuredAt,                 // ✅ from promotion
                toPublicUrl(beat.getCoverImagePath()),
                null,                       // previewUrl
                beat.getBpm(),
                beat.getCreatedAt(),
                liked,
                sales,
                earnings
        );
    }

    private static String toPublicUrl(String path) {
        return (path != null && !path.isBlank()) ? "/uploads/" + path : null;
    }
}