// src/main/java/com/drilldex/drillbackend/pack/dto/PackUploadRequest.java
package com.drilldex.drillbackend.pack.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

@Data
public class PackUploadRequest {
    private String title;
    private String description;
    private BigDecimal price;
    private List<Long> beatIds;   // beats to bundle
    private MultipartFile cover;  // optional

    private String tags;          // <— NEW
}