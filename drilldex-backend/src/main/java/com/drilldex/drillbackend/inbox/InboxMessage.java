// src/main/java/com/drilldex/drillbackend/inbox/InboxMessage.java
package com.drilldex.drillbackend.inbox;

import com.drilldex.drillbackend.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @ManyToOne(fetch = FetchType.LAZY)
    private User sender;

    private String subject;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Builder.Default
    private boolean readFlag = false;

    @Builder.Default
    private Instant createdAt = Instant.now();
}
