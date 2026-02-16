package com.drilldex.drillbackend.subscription;

import com.drilldex.drillbackend.notification.Notification;
import com.drilldex.drillbackend.notification.NotificationService;
import com.drilldex.drillbackend.notification.NotificationType;
import com.drilldex.drillbackend.notification.RelatedType;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionCleanupTask {

    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Scheduled(cron = "0 0 3 * * *") // Every day at 3 AM
    public void expireInactiveSubscriptions() {
        List<User> users = userRepository.findAll();

        for (User user : users) {
            if (user.getPlan() == null || user.getPlan().equalsIgnoreCase("free")) continue;

            boolean isActive = user.isSubscriptionActive();

            if (!isActive) {
                log.info("⏳ Downgrading expired subscription for user: {}", user.getEmail());

                String oldPlan = user.getPlan();

                // Downgrade
                user.setPlan("free");
                user.setPlanBillingCycle(null);
                user.setTrialDaysLeft(0);
                user.setSubscriptionStart(null);

                // 🔔 Create and push notification via SSE
                Notification n = notificationService.create(
                        user,
                        NotificationType.SYSTEM,
                        RelatedType.SUBSCRIPTION,
                        null,
                        "Subscription expired",
                        "Your subscription to " + oldPlan + " has ended. You are now on the Free plan."
                );
                notificationService.pushNotificationToUser(user.getId(), n);
            }
        }

        userRepository.saveAll(users);
    }
}
