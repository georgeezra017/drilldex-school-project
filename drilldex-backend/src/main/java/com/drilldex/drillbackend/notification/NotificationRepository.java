package com.drilldex.drillbackend.notification;

import com.drilldex.drillbackend.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // Fetch unread notifications with pagination
    Page<Notification> findByRecipientAndReadFalseOrderByCreatedAtDesc(User recipient, Pageable pageable);

    // Fetch all notifications with pagination
    Page<Notification> findByRecipientOrderByCreatedAtDesc(User recipient, Pageable pageable);

    /**
     * Find the most recent unread notification of a specific type and reference for aggregation.
     * Used for chat message aggregation.
     */
    Optional<Notification> findTopByRecipientAndTypeAndReferenceIdAndReadFalseOrderByCreatedAtDesc(
            User recipient,
            NotificationType type,
            Long referenceId
    );

    @Transactional
    @Modifying
    void deleteAllByRecipient_Id(Long recipientId);
}