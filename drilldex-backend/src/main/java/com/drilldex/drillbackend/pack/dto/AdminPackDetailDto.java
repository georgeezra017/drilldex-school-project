// src/main/java/com/drilldex/drillbackend/pack/dto/AdminPackDetailDto.java
package com.drilldex.drillbackend.pack.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AdminPackDetailDto(
        Long id,
        String title,
        String description,
        String genre,          // optional (null if not on entity)
        BigDecimal price,
        String coverUrl,
        String coverImagePath,
        String ownerName,
        Instant createdAt,
        Instant updatedAt,     // optional (null if not tracked)
        String status,         // pending|approved|rejected
        List<BeatInPackDto> beats
) {
    public record BeatInPackDto(
            Long id,
            String title,
            Integer bpm,
            String genre,
            Integer durationSec,
            boolean beatApproved,
            String audioUrl,
            String coverUrl
    ) {}
}