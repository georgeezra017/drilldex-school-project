package com.drilldex.drillbackend.user;

import com.drilldex.drillbackend.notification.Notification;
import com.drilldex.drillbackend.notification.NotificationService;
import com.drilldex.drillbackend.notification.NotificationType;
import com.drilldex.drillbackend.notification.RelatedType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
public class FollowService {

    private final UserRepository userRepo;
    private final NotificationService notificationService;

    public void follow(User follower, Long followedId) {
        if (follower.getId().equals(followedId)) return;

        User followed = userRepo.findById(followedId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (followed.getFollowers().contains(follower)) return;

        followed.getFollowers().add(follower);
        userRepo.save(followed);

        // Create notification
        Notification n = notificationService.create(
                followed,
                NotificationType.FOLLOW,
                RelatedType.USER,
                follower.getId(),
                "New follower",
                follower.getDisplayName() + " followed you"
        );

        // Push notification via SSE
        notificationService.pushNotificationToUser(followed.getId(), n);
    }

    public void unfollow(User follower, Long followedId) {
        if (follower.getId().equals(followedId)) return;

        User followed = userRepo.findById(followedId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (followed.getFollowers().contains(follower)) {
            followed.getFollowers().remove(follower);
            userRepo.save(followed);
        }
    }

    public boolean isFollowing(User follower, Long followedId) {
        return userRepo.findById(followedId)
                .map(u -> u.getFollowers().contains(follower))
                .orElse(false);
    }

    public long countFollowers(Long userId) {
        return userRepo.findById(userId)
                .map(u -> (long) u.getFollowers().size())
                .orElse(0L);
    }
}