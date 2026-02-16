package com.drilldex.drillbackend.auth;

import com.drilldex.drillbackend.notification.Notification;
import com.drilldex.drillbackend.notification.NotificationService;
import com.drilldex.drillbackend.notification.NotificationType;
import com.drilldex.drillbackend.notification.RelatedType;
import com.drilldex.drillbackend.user.ReferralEvent;
import com.drilldex.drillbackend.user.ReferralEventRepository;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ReferralService {

    private final UserRepository userRepository;
    private final ReferralEventRepository referralEventRepository;
    private final NotificationService notificationService;

    public void applyReferralBonus(User newUser, String referralCode) {
        if (referralCode == null || referralCode.isBlank()) return;

        userRepository.findByReferralCode(referralCode).ifPresent(referrer -> {
            BigDecimal creditsPerReferral = switch (referrer.getPlan().toLowerCase()) {
                case "free" -> BigDecimal.valueOf(5);
                case "growth" -> BigDecimal.valueOf(10);
                case "pro" -> BigDecimal.valueOf(20);
                default -> BigDecimal.ZERO;
            };

            // Daily cap for free users
            if ("free".equalsIgnoreCase(referrer.getPlan())) {
                Instant startOfDay = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS);
                Instant endOfDay = startOfDay.plusSeconds(86399);
                BigDecimal earnedToday = referralEventRepository.sumCreditsForUserToday(
                        referrer.getId(), startOfDay, endOfDay
                );
                BigDecimal dailyCap = BigDecimal.valueOf(20);
                BigDecimal remaining = dailyCap.subtract(earnedToday);

                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    creditsPerReferral = BigDecimal.ZERO;
                } else if (creditsPerReferral.compareTo(remaining) > 0) {
                    creditsPerReferral = remaining;
                }
            }

            if (creditsPerReferral.compareTo(BigDecimal.ZERO) > 0) {
                referrer.addReferralCredits(creditsPerReferral);
                userRepository.save(referrer);

                ReferralEvent event = new ReferralEvent();
                event.setReferrer(referrer);
                event.setReferred(newUser);
                event.setAmount(creditsPerReferral);
                referralEventRepository.save(event);

                Notification n = notificationService.create(
                        referrer,
                        NotificationType.SYSTEM,
                        RelatedType.USER,
                        newUser.getId(),
                        "Referral Bonus Earned!",
                        "You earned $" + creditsPerReferral + " in promo credits because " +
                                newUser.getDisplayName() + " signed up with your referral link."
                );
                notificationService.pushNotificationToUser(referrer.getId(), n);
            }
        });
    }
}