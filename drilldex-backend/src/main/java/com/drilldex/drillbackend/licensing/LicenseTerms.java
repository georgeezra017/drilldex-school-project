package com.drilldex.drillbackend.licensing;

import com.drilldex.drillbackend.beat.LicenseType;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LicenseTerms {
    private LicenseType type;
    private int maxStreams;
    private int maxMusicVideos;
    private boolean radioRights;
    private boolean livePerformanceRights;
    private boolean commercialUse;
    private boolean includesStems;
    private boolean exclusive; // true only for EXCLUSIVE
    private double price; // in USD or EUR
}