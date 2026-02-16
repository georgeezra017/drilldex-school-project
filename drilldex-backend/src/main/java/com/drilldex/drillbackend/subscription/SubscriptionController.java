// src/main/java/com/drilldex/drillbackend/subscription/SubscriptionController.java
package com.drilldex.drillbackend.subscription;

import com.drilldex.drillbackend.subscription.dto.SubscriptionDto;
import com.drilldex.drillbackend.subscription.dto.SubscriptionStartRequest;
import com.drilldex.drillbackend.user.CurrentUserService;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/billing/subscriptions")
public class SubscriptionController {

    private final SubscriptionRepository subscriptionRepository;
    private final CurrentUserService currentUserService;
    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;


    @GetMapping("/mine")
    public List<SubscriptionDto> mine() {
        User me = currentUserService.getCurrentUserOrThrow();
        return subscriptionRepository.findByUser(me)
                .stream()
                .map(SubscriptionDto::from)
                .toList();
    }

    // ---- Matches FE: /{id}/cancel --------------------
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id) {
        try {
            User me = currentUserService.getCurrentUserOrThrow();
            Subscription sub = subscriptionRepository.findById(id).orElseThrow();
            if (!sub.getUser().getId().equals(me.getId())) return ResponseEntity.status(403).build();

            subscriptionService.cancel(sub);

            // Optional: if this is the latest active sub, downgrade user fields for consistency
            if ("active".equalsIgnoreCase(me.getPlan())) {
                me.setPlan("free");
                me.setPlanBillingCycle(null);
                me.setTrialDaysLeft(0);
                me.setSubscriptionStart(null);
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to cancel subscription",
                    "details", e.getMessage()
            ));
        }
    }

    // ---- Matches FE: /{id}/resume --------------------
    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable Long id) {
        User me = currentUserService.getCurrentUserOrThrow();
        Subscription sub = subscriptionRepository.findById(id).orElseThrow();
        if (!sub.getUser().getId().equals(me.getId())) return ResponseEntity.status(403).build();

        subscriptionService.resume(sub);
        return ResponseEntity.ok().build();
    }
}
