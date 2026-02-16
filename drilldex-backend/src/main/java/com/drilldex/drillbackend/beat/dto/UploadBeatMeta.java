// src/main/java/.../beat/dto/UploadBeatMeta.java
package com.drilldex.drillbackend.beat.dto;

import java.math.BigDecimal;
import java.util.List;

public record UploadBeatMeta(
        String title,
        Integer bpm,
        String genre,
        String tags,
        List<LicenseLine> licenses
) {
    public record LicenseLine(
            String type,        // "MP3" | "WAV" | "PREMIUM" | "EXCLUSIVE"
            BigDecimal price,   // > 0
            Boolean enabled     // true to include
    ) {}
}