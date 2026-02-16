package com.drilldex.drillbackend.ad;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdService {

    private final AdRepository adRepository;

    public List<Ad> getActiveAds() {
        return adRepository.findByApprovedTrueAndPaidTrueAndEndTimeAfter(LocalDateTime.now());
    }

    public Ad createAd(Ad ad) {
        return adRepository.save(ad);
    }

    public void incrementImpressions(Ad ad) {
        ad.setImpressions(ad.getImpressions() + 1);
        adRepository.save(ad);
    }
}