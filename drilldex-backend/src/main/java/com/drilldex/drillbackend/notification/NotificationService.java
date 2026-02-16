package com.drilldex.drillbackend.notification;

import com.drilldex.drillbackend.beat.BeatRepository;
import com.drilldex.drillbackend.chat.ChatStorageService;
import com.drilldex.drillbackend.kit.KitRepository;
import com.drilldex.drillbackend.notification.dto.NotificationDto;
import com.drilldex.drillbackend.pack.PackRepository;
import com.drilldex.drillbackend.user.User;
import lombok.RequiredArgsConstructor;
import org.hibernate.validator.internal.util.stereotypes.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository repository;
    private final Map<Long, List<SseEmitter>> userEmitters = new ConcurrentHashMap<>();
    private final BeatRepository beatRepository;
    private final PackRepository packRepository;
    private final KitRepository kitRepository;
    private final SseNotificationBroadcaster broadcaster;
    private final ChatStorageService chatStorageService;

    /**
     * Create a generic notification.
     */
    public Notification create(
            User recipient,
            NotificationType type,
            RelatedType relatedType,
            Long referenceId,
            String title,
            String message
    ) {
        Notification n = new Notification();
        n.setRecipient(recipient);
        n.setType(type);
        n.setRelatedType(relatedType);
        n.setReferenceId(referenceId);
        n.setTitle(title);
        n.setMessage(message);
        n.setRead(false);
        n.setCreatedAt(Instant.now());
        return repository.save(n);
    }

    /**
     * Aggregate chat notifications: if there's already an unread notification from the same sender,
     * update the message count instead of creating a new notification.
     */
    public Notification createOrAggregateChat(User recipient, User sender, Long chatMessageId, String latestMessage) {
        Optional<Notification> existing = repository
                .findTopByRecipientAndTypeAndReferenceIdAndReadFalseOrderByCreatedAtDesc(
                        recipient, NotificationType.CHAT, chatMessageId
                );

        Notification n;

        if (existing.isPresent()) {
            n = existing.get();
            // Aggregate message count
            String msg = n.getMessage();
            int count = 1;
            if (msg != null && msg.matches(".*\\d+ messages")) {
                count = Integer.parseInt(msg.replaceAll(".*?(\\d+) messages.*", "$1")) + 1;
            }
            n.setMessage(sender.getDisplayName() + " sent " + count + " messages");
            n.setUpdatedAt(Instant.now());
            n = repository.save(n);
        } else {
            // Create new notification
            n = create(
                    recipient,
                    NotificationType.CHAT,
                    RelatedType.CHAT,
                    chatMessageId,
                    "New message from " + sender.getDisplayName(),
                    latestMessage
            );
        }

        // --- Push to recipient via SSE (real-time) ---
        pushNotificationToUser(recipient.getId(), n);

        return n;
    }

    public void registerEmitter(Long userId, SseEmitter emitter) {
        userEmitters.computeIfAbsent(userId, k -> new ArrayList<>()).add(emitter);
    }

    public void removeEmitter(Long userId, SseEmitter emitter) {
        List<SseEmitter> emitters = userEmitters.get(userId);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }

    @Async
    public void pushNotificationToUser(Long userId, Notification notification) {
        // Use the broadcaster to send the notification to all connected emitters
        SseNotificationBroadcaster broadcaster = this.broadcaster; // inject or use your existing instance

        if (broadcaster == null) {
            System.err.println("SSE broadcaster is not initialized");
            return;
        }

        String slug = null;
        Long profileId = null;
        Long chatId = null;

        switch (notification.getRelatedType()) {
            case BEAT, PACK, KIT:
                slug = fetchSlugForItem(notification.getReferenceId(), notification.getRelatedType());
                break;
            case USER:
                profileId = notification.getReferenceId();
                break;
            default:
                break; // leave null for SUBSCRIPTION, PROMOTION, SYSTEM, CHAT
        }

        // Convert Notification entity to DTO
        NotificationDto dto = new NotificationDto(
                notification.getId(),
                notification.getTitle(),
                notification.getMessage(),
                notification.isRead(),
                notification.getRelatedType(),
                notification.getReferenceId(),
                slug,
                profileId,
                chatId
        );

        // Send the DTO as a named event
        broadcaster.sendTo(userId, dto);
    }

    private String fetchSlugForItem(Long referenceId, RelatedType type) {
        if (referenceId == null) return null;

        try {
            return switch (type) {
                case BEAT -> beatRepository.findById(referenceId).map(beat -> beat.getSlug()).orElse(null);
                case PACK -> packRepository.findById(referenceId).map(pack -> pack.getSlug()).orElse(null);
                case KIT -> kitRepository.findById(referenceId).map(kit -> kit.getSlug()).orElse(null);
                default -> null;
            };
        } catch (Exception e) {
            System.err.println("Failed to fetch slug for " + type + " id=" + referenceId);
            return null;
        }
    }


    /**
     * Mark a single notification as read.
     */
    public void markAsRead(Long notificationId, User recipient) {
        Notification n = repository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found"));
        if (!n.getRecipient().getId().equals(recipient.getId())) {
            throw new SecurityException("Not your notification");
        }
        n.setRead(true);
        n.setReadAt(Instant.now());
        repository.save(n);
    }

    /**
     * Mark all notifications as read for a user.
     */
    public void markAllAsRead(User recipient) {
        int page = 0;
        int size = 100; // batch size
        Pageable pageable = PageRequest.of(page, size);
        Page<Notification> unreadPage;

        do {
            unreadPage = repository.findByRecipientAndReadFalseOrderByCreatedAtDesc(recipient, pageable);
            List<Notification> unread = unreadPage.getContent();

            for (Notification n : unread) {
                n.setRead(true);
                n.setReadAt(Instant.now());
            }

            repository.saveAll(unread);
            page++;
            pageable = PageRequest.of(page, size);
        } while (!unreadPage.isLast());
    }

    /**
     * Fetch unread notifications for a user with pagination.
     */
    public Page<Notification> getUnread(User recipient, Pageable pageable) {
        return repository.findByRecipientAndReadFalseOrderByCreatedAtDesc(recipient, pageable);
    }

    /**
     * Fetch all notifications for a user with pagination.
     */
    public Page<Notification> getAll(User recipient, Pageable pageable) {
        return repository.findByRecipientOrderByCreatedAtDesc(recipient, pageable);
    }

    public NotificationDto toDto(Notification n) {
        if (n == null) return null;

        String slug = null;
        Long profileId = null;
        Long chatId = null; // now a Long

        switch (n.getRelatedType()) {
            case BEAT, PACK, KIT:
                slug = fetchSlugForItem(n.getReferenceId(), n.getRelatedType());
                break;
            case USER:
                profileId = n.getReferenceId();
                break;
            case CHAT:
                // derive chat partner ID from the message ID stored in referenceId
                chatId = chatStorageService.getChatPartnerIdByMessageId(
                        n.getReferenceId(),
                        n.getRecipient().getId()
                );
                break;
            default:
                break; // leave null for SUBSCRIPTION, PROMOTION, SYSTEM
        }

        return new NotificationDto(
                n.getId(),
                n.getTitle(),
                n.getMessage(),
                n.isRead(),
                n.getRelatedType(),
                n.getReferenceId(), // still the message ID
                slug,
                profileId,
                chatId // now matches NotificationDto.chatId type
        );
    }

    public void delete(Long id, User user) {
        Notification notif = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found"));
        if (!notif.getRecipient().equals(user)) {
            throw new IllegalArgumentException("Cannot delete notification of another user");
        }
        repository.delete(notif);
    }
}