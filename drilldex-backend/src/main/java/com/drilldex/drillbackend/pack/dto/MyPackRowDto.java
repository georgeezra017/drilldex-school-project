// src/main/java/com/drilldex/drillbackend/pack/dto/MyPackRowDto.java
package com.drilldex.drillbackend.pack.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record MyPackRowDto(
        Long id,
        String title,
        String genre,
        String coverUrl,
        BigDecimal price,   // Pack.getDisplayPrice() or Pack.getPrice()
        long plays,         // Pack.playCount
        int likes,          // upvotedBy.size()
        long sales,         // ← licenses sold (0 for now if you don’t track orders yet)
        Instant updatedAt,
        String status       // "published" | "pending" | "rejected"
) {}