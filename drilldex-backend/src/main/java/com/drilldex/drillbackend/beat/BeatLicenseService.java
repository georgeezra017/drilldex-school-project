// com/drilldex/drillbackend/beat/BeatLicenseService.java
package com.drilldex.drillbackend.beat;

import com.drilldex.drillbackend.beat.dto.BeatLicenseDto;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class BeatLicenseService {
    private final BeatRepository beatRepository;
    private final BeatLicenseRepository licenseRepository;

    public BeatLicenseService(BeatRepository beatRepository, BeatLicenseRepository licenseRepository) {
        this.beatRepository = beatRepository;
        this.licenseRepository = licenseRepository;
    }

    public List<BeatLicenseDto> getEnabledLicenses(Long beatId) {
        if (!beatRepository.existsById(beatId)) {
            throw new IllegalArgumentException("Beat not found: " + beatId);
        }
        return licenseRepository.findByBeatIdAndEnabledTrue(beatId)
                .stream()
                .sorted((a, b) -> a.getType().name().compareTo(b.getType().name()))
                .map(l -> new BeatLicenseDto(l.getId(), l.getType(), l.getPrice()))
                .toList();
    }
}