// src/main/java/com/drilldex/drillbackend/kit/dto/CreateKitResponseDto.java
package com.drilldex.drillbackend.kit.dto;

import java.math.BigDecimal;

public record CreateKitResponseDto(
        Long id,
        String title,
        String type,
        BigDecimal price,
        String coverUrl,
        String status
) {}