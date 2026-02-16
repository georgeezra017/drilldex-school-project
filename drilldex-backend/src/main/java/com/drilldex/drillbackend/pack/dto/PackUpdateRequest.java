package com.drilldex.drillbackend.pack.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

@Data
public class PackUpdateRequest {
    private String title;
    private String description;
    private BigDecimal price;
    private MultipartFile newCover;
    private List<Long> beatIds;


    private String tags;


    private List<LicenseLine> licenses;

    @Data
    public static class LicenseLine {
        private String type;
        private boolean enabled;
        private BigDecimal price;
    }
}