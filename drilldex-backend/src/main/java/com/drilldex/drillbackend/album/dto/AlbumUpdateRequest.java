package com.drilldex.drillbackend.album.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Data
public class AlbumUpdateRequest {
    private String title;
    private String description;
    private MultipartFile newCover; // Optional new cover
    private List<Long> updatedBeatIdsToInclude;
    private List<Long> updatedBeatIdsAsSamples;

}