// src/main/java/com/drilldex/drillbackend/inbox/InboxMessageService.java
package com.drilldex.drillbackend.inbox;

import com.drilldex.drillbackend.user.User;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InboxMessageService {

    private final InboxMessageRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void send(Long recipientId, String subject, String body) {
        // Recipient
        User recipient = new User();
        recipient.setId(recipientId);

        // Sender (admin/system)
        User sender = new User();
        sender.setId(2L);

        InboxMessage msg = InboxMessage.builder()
                .recipient(recipient)
                .sender(sender)
                .subject(subject)
                .body(body)
                .build();

        repo.save(msg);
    }
}