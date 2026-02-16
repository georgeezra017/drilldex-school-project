package com.drilldex.drillbackend.pack.dto;

import java.math.BigDecimal;
import java.util.List;

public record PackUploadMeta(
        String name,
        String description,
        String tags,            // "dark, fast, drill"
        BigDecimal price,       // base price / display price for the pack
        List<Long> beatIds,     // optional: include existing beats
        List<LicenseLine> licenses // optional: if you sell pack-level licenses too
) {
    public record LicenseLine(String type, Boolean enabled, BigDecimal price) {}
}