package com.drilldex.drillbackend.pack.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
// src/main/java/com/drilldex/drillbackend/pack/dto/PackDto.java
@Data
public class PackDto {
    private Long id;
    private String slug;
    private String title;
    private String description;
    private String coverUrl;
    private String ownerName;
    private Long ownerId;

    @JsonProperty("artistName")
    private String artistName;

    private BigDecimal price;
    private Integer totalDurationSec;
    private Integer beatsCount;

    private long playCount;
    private Instant createdAt;
    private List<String> tags;

    private int likeCount;
    private Boolean liked;

    private boolean featured;
    private Instant featuredFrom;
    private boolean isNew;
    private boolean isPopular;
    private boolean isTrending;
    private Integer sales;
    private BigDecimal earnings;


    public PackDto() {}

    public PackDto(Long id,
                   String slug,
                   String title,
                   String description,
                   String coverUrl,
                   String ownerName,
                   Long ownerId,
                   String artistName,
                   BigDecimal price,
                   Integer totalDurationSec,
                   Integer beatsCount,
                   long playCount,
                   Instant createdAt,
                   List<String> tags,
                   boolean featured,
                   Instant featuredFrom,
                   boolean isNew,
                   boolean isPopular,
                   boolean isTrending,
                   int likeCount,
                   Boolean liked,
                   Integer sales,
                   BigDecimal earnings
    ) {
        this.id = id;
        this.slug = slug;
        this.title = title;
        this.description = description;
        this.coverUrl = coverUrl;
        this.ownerName = ownerName;
        this.ownerId = ownerId;
        this.artistName = artistName;
        this.price = price;
        this.totalDurationSec = totalDurationSec;
        this.beatsCount = beatsCount;
        this.playCount = playCount;
        this.createdAt = createdAt;
        this.tags = tags;
        this.featured = featured;
        this.featuredFrom = featuredFrom;
        this.isNew = isNew;
        this.isPopular = isPopular;
        this.isTrending = isTrending;
        this.likeCount = likeCount;
        this.liked = liked;
        this.sales = sales;
        this.earnings = earnings;
    }
}