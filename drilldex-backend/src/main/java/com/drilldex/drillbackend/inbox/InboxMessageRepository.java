// src/main/java/com/drilldex/drillbackend/inbox/InboxMessageRepository.java
package com.drilldex.drillbackend.inbox;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InboxMessageRepository extends JpaRepository<InboxMessage, Long> {
    List<InboxMessage> findByRecipientIdOrderByCreatedAtDesc(Long recipientId);
}