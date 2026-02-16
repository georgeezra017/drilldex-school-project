// src/main/java/com/drilldex/drillbackend/chat/ChatMessage.java
package com.drilldex.drillbackend.chat;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "chat_messages",
        indexes = {
                @Index(name = "idx_sender_receiver_time", columnList = "senderId,receiverId,timestamp"),
                @Index(name = "idx_receiver_time", columnList = "receiverId,timestamp")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long senderId;
    private Long receiverId;

    @Column(columnDefinition = "text")
    private String content;

    private Instant timestamp;
}