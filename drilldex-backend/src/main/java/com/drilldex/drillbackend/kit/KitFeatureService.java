// src/main/java/com/drilldex/drillbackend/kit/KitFeatureService.java
package com.drilldex.drillbackend.kit;

import com.drilldex.drillbackend.kit.dto.FeaturedKitDto;
import com.drilldex.drillbackend.promotions.Promotion;
import com.drilldex.drillbackend.promotions.PromotionRepository;
import com.drilldex.drillbackend.promotions.PromotionService;
import com.drilldex.drillbackend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class KitFeatureService {
    private final KitRepository repo;
    private final PromotionService promotionService;
    private final PromotionRepository promotionRepository;

    public List<FeaturedKitDto> getFeaturedKits(int limit) {
        int capped = Math.max(1, Math.min(100, limit));
        Instant now = Instant.now();

        return repo.findActiveFeatured(now, PageRequest.of(0, capped))
                .stream()
                .map(FeaturedKitDto::from)
                .toList();
    }

    public boolean isKitCurrentlyFeatured(Long kitId) {
        return promotionRepository.isCurrentlyFeatured(
                "KIT",
                kitId,
                Instant.now()
        );
    }

}