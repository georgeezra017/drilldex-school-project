// src/main/java/com/drilldex/drillbackend/pack/mapper/PackMapper.java
package com.drilldex.drillbackend.pack.mapper;

import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.pack.Pack;
import com.drilldex.drillbackend.pack.dto.PackDto;
import com.drilldex.drillbackend.promotions.Promotion;
import com.drilldex.drillbackend.user.User;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class PackMapper {

    // === Legacy overload ===
    public static PackDto toDto(Pack pack) {
        return toDto(pack, null, null);
    }

    // === Overload with user ===
    public static PackDto toDto(Pack pack, User currentUser) {
        return toDto(pack, null, currentUser);
    }

    // === Overload with promo ===
    public static PackDto toDto(Pack pack, Promotion promo) {
        return toDto(pack, promo, null);
    }

public static PackDto toDto(Pack pack, Promotion promo, User currentUser) {
    return toDto(pack, promo, currentUser, 0, BigDecimal.ZERO);
}

    public static PackDto toDto(Pack pack, Promotion promo, User currentUser, int sales, BigDecimal earnings) {
        String name = null;
        if (pack.getOwner() != null) {
            name = (pack.getOwner().getDisplayName() != null && !pack.getOwner().getDisplayName().isBlank())
                    ? pack.getOwner().getDisplayName()
                    : pack.getOwner().getEmail();
        }

        Long ownerId = (pack.getOwner() != null) ? pack.getOwner().getId() : null;
        String coverRaw = firstNonBlank(pack.getAlbumCoverUrl(), pack.getCoverImagePath());
        String coverUrl = toPublicUrl(coverRaw);

        int totalDurationSec =
                (pack.getBeats() == null) ? 0 :
                        pack.getBeats().stream()
                                .map(Beat::getDurationInSeconds)
                                .filter(Objects::nonNull)
                                .mapToInt(Integer::intValue)
                                .sum();

        int beatsCount = (pack.getBeats() == null) ? 0 : pack.getBeats().size();

        // Flags
        Instant now = Instant.now();
        Instant newCutoff = now.minus(14, ChronoUnit.DAYS);
        Instant popularCutoff = now.minus(30, ChronoUnit.DAYS);

        boolean featured = promo != null && promo.isActive();
        Instant featuredFrom = (promo != null) ? promo.getStartDate() : null;
        boolean isNew = pack.getCreatedAt() != null && pack.getCreatedAt().isAfter(newCutoff);

        long plays = pack.getPlayCount();
        boolean isPopular = pack.getCreatedAt() != null
                && pack.getCreatedAt().isAfter(popularCutoff)
                && plays > 100;
        boolean isTrending = isPopular && isNew;

        boolean liked = false;
        if (currentUser != null && pack.getUpvotedBy() != null) {
            liked = pack.getUpvotedBy().stream()
                    .anyMatch(u -> u.getId().equals(currentUser.getId()));
        }

        return new PackDto(
                pack.getId(),
                pack.getSlug(),
                pack.getTitle(),
                pack.getDescription(),
                coverUrl,
                name,        // ownerName
                ownerId,     // ownerId
                name,        // artistName
                pack.getPrice(),
                totalDurationSec,
                beatsCount,
                pack.getPlayCount(),
                pack.getCreatedAt(),
                splitTags(pack.getTags()),
                featured,
                featuredFrom,
                isNew,
                isPopular,
                isTrending,
                pack.getLikeCount(),
                liked,
                sales,
                earnings
        );
    }

    private static List<String> splitTags(String t) {
        if (t == null || t.isBlank()) return List.of();
        return Arrays.stream(t.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String toPublicUrl(String any) {
        if (any == null || any.isBlank()) return null;
        if (any.startsWith("http://") || any.startsWith("https://")) return any;

        String p = any.replace('\\', '/').trim();

        int idx = p.indexOf("/uploads/");
        if (idx >= 0) {
            p = p.substring(idx);
        } else {
            p = p.replaceFirst("^/?uploads/?", "uploads/");
            if (!p.startsWith("uploads/")) p = "uploads/" + p.replaceFirst("^/?", "");
            p = "/" + p;
        }

        return p;
    }
}