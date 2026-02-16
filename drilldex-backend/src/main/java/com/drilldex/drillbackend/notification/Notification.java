package com.drilldex.drillbackend.notification;

import com.drilldex.drillbackend.user.User;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Data
public class Notification {

    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private User recipient;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private NotificationType type; // CHAT, PURCHASE, PROMOTION, FOLLOW, SYSTEM

    @Enumerated(EnumType.STRING)
    private RelatedType relatedType; // BEAT, PACK, KIT, SUBSCRIPTION, CHAT

    private Long referenceId; // e.g., chatMessageId, beatId, promotionId

    @Column(length = 128, nullable = false)
    private String title;   // short headline like "Beat Rejected", "New Follower"

    @Column(length = 1024)
    private String message; // detailed description or reason

    private boolean read = false;
    private Instant readAt;

    private Instant createdAt = Instant.now();
    private Instant updatedAt;
}