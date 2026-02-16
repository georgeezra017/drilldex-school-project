package com.drilldex.drillbackend.notification;

import com.drilldex.drillbackend.kit.Kit;
import com.drilldex.drillbackend.pack.Pack;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.beat.Beat;
import com.drilldex.drillbackend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FollowerNotificationService {

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @Transactional
    public void notifyFollowersOfNewBeat(User user, Beat beat) {
        notifyFollowers(user, NotificationType.SYSTEM, RelatedType.BEAT, beat.getId(), "New beat from " + user.getDisplayName(), user.getDisplayName() + " uploaded a new beat: " + beat.getTitle());
    }

    @Transactional
    public void notifyFollowersOfNewKit(User owner, Kit kit) {
        notifyFollowers(owner, NotificationType.SYSTEM, RelatedType.KIT, kit.getId(), "New kit from " + owner.getDisplayName(), owner.getDisplayName() + " uploaded a new kit: " + kit.getTitle());
    }

    @Transactional
    public void notifyFollowersOfNewPack(User artist, Pack pack) {
        notifyFollowers(artist, NotificationType.SYSTEM, RelatedType.PACK, pack.getId(), "New pack from " + artist.getDisplayName(), artist.getDisplayName() + " uploaded a new pack: " + pack.getTitle());
    }

    private void notifyFollowers(User user, NotificationType type, RelatedType relatedType, Long referenceId, String title, String body) {
        List<User> followers = userRepository.findFollowersByUserId(user.getId());
        if (followers.isEmpty()) return;

        for (User follower : followers) {
            Notification n = notificationService.create(
                    follower,
                    type,
                    relatedType,
                    referenceId,
                    title,
                    body
            );
            notificationService.pushNotificationToUser(follower.getId(), n);
        }
    }
}