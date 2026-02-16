package com.drilldex.drillbackend.kit.dto;

import java.math.BigDecimal;
import java.util.List;

public record KitUploadMeta(
        String name,
        String type,
        String description,
        String tags,
        BigDecimal price,
        Integer bpmMin,
        Integer bpmMax,
        String key
) {}