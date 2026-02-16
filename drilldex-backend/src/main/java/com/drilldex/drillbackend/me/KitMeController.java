// src/main/java/com/drilldex/drillbackend/me/KitMeController.java
package com.drilldex.drillbackend.me;

import com.drilldex.drillbackend.auth.CustomUserDetails;
import com.drilldex.drillbackend.kit.Kit;
import com.drilldex.drillbackend.kit.KitRepository;
import com.drilldex.drillbackend.kit.dto.KitSummaryDto;
import com.drilldex.drillbackend.purchase.PurchaseRepository;
import com.drilldex.drillbackend.shared.PaginatedResponse;
import com.drilldex.drillbackend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/me/kits")
@RequiredArgsConstructor
public class KitMeController {

    private final KitRepository kitRepository;
    private final PurchaseRepository purchaseRepository;


    @GetMapping
    public ResponseEntity<PaginatedResponse<KitSummaryDto>> myKits(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int limit
    ) {
        User currentUser = ((CustomUserDetails) auth.getPrincipal()).getUser();
        Long ownerId = currentUser.getId();

        // Fetch all kits by current user
        List<Kit> kits = kitRepository.findByOwnerId(ownerId);

        // Fetch kit sales and earnings in bulk
        List<Object[]> stats = purchaseRepository.getKitSalesAndEarningsByOwner(ownerId);
        Map<Long, Object[]> statsMap = new HashMap<>();
        for (Object[] row : stats) {
            Long kitId = ((Number) row[0]).longValue();
            statsMap.put(kitId, row);
        }

        // Map each kit to DTO with earnings + sales
        List<KitSummaryDto> dtos = kits.stream()
                .map(kit -> {
                    Object[] s = statsMap.get(kit.getId());
                    int sales = 0;
                    BigDecimal earnings = BigDecimal.ZERO;

                    if (s != null) {
                        if (s[1] instanceof Number n) sales = n.intValue();
                        if (s[2] instanceof BigDecimal bd) earnings = bd;
                        else if (s[2] instanceof Number n2) earnings = BigDecimal.valueOf(n2.doubleValue());
                    }

                    return KitSummaryDto.from(
                            kit,
                            earnings,
                            sales,
                            kit.getDurationInSeconds(),
                            currentUser
                    );
                })
                .toList();

        // Manual pagination
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        int pageIndex = Math.max(0, page);
        int from = Math.min(pageIndex * cappedLimit, dtos.size());
        int to = Math.min(from + cappedLimit, dtos.size());
        List<KitSummaryDto> paginated = dtos.subList(from, to);

        return ResponseEntity.ok(new PaginatedResponse<>(paginated, dtos.size(), pageIndex, cappedLimit));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(Authentication auth, @PathVariable Long id) {
        User user = ((CustomUserDetails) auth.getPrincipal()).getUser();
        return kitRepository.findById(id)
                .filter(k -> k.getOwner().getId().equals(user.getId()))
                .map(k -> { kitRepository.delete(k); return ResponseEntity.ok().build(); })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
