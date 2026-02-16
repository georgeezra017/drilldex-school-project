package com.drilldex.drillbackend.user.dto;

public record ProducerSummaryDto(
        Long id,
        String displayName,
        String avatarUrl,
        long beatsCount,
        long followersCount
) {}