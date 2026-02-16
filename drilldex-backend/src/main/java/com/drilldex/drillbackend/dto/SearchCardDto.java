package com.drilldex.drillbackend.dto; // src/main/java/.../dto/SearchCardDto.java

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchCardDto {

    // Basic info
    private Long id;
    private String slug;
    private String title;
    private String coverUrl;
    private BigDecimal price;
    private Instant createdAt;
    private String _kind;          // "BEAT" | "PACK" | "KIT"
    private String artistName;     // Display artist/owner name
    private Long artistId;
    private String ownerName;      // Kept if you ever used it
    private String tags;           // Raw comma-separated string
    private Integer likeCount;
    private boolean liked;

    // Musical / Kit-specific info
    private Integer bpm;           // Beats: BPM; Kits leave null
    private Integer durationSec;   // Seconds
    private Integer beatsCount;    // Packs: number of tracks
    private Integer bpmMin;        // Kits (optional)
    private Integer bpmMax;        // Kits (optional)
    private String location;       // e.g., city/region (optional)
    private String genre;          // e.g., UK Drill, Hip-Hop

    // Badge / promotion flags
    private boolean featured;      // true if actively featured
    private String featuredTier;   // "spotlight" | "premium" | "standard" | null
    private boolean popular;       // true if item is popular
    private boolean trending;      // true if item is trending
    private boolean isNew;         // true if newly released
}