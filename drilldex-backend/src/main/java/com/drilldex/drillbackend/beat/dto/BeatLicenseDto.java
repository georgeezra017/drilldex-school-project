package com.drilldex.drillbackend.beat.dto;


import com.drilldex.drillbackend.beat.LicenseType;

import java.math.BigDecimal;

public record BeatLicenseDto(
        Long id,
        LicenseType type,
        BigDecimal price
) {}