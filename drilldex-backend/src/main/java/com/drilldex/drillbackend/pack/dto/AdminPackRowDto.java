// src/main/java/com/drilldex/drillbackend/pack/dto/AdminPackRowDto.java
package com.drilldex.drillbackend.pack.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminPackRowDto(
        Long id,
        String title,
        String ownerName,
        BigDecimal price,
        Instant submittedAt,
        String coverUrl,
        String status, // pending|approved|rejected
        int beatsCount
) {}