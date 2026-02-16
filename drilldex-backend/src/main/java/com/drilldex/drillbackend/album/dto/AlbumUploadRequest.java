package com.drilldex.drillbackend.album.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Data
public class AlbumUploadRequest {
    private String title;
    private String description;
    private List<Long> beatIdsToInclude;
    private List<Long> beatIdsAsSamples;
    private MultipartFile cover;


}