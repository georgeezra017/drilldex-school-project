// src/main/java/com/drilldex/drillbackend/promotions/dto/PromotionDto.java
package com.drilldex.drillbackend.promotions.dto;

import java.time.Instant;

public record PromotionDto(
        Long id,
        String type,          // "promotion"
        String targetType,    // "BEAT" | "PACK" | "KIT"
        Long targetId,
        String title,         // resolved live
        String thumb,         // resolved live
        String tier,          // e.g. "premium"
        String status,        // "active" | "inactive" | "canceled"
        Instant startedAt,
        Instant endsAt
) {}