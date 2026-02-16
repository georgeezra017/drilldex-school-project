// src/main/java/com/drilldex/drillbackend/chat/dto/ChatMessageDto.java
package com.drilldex.drillbackend.chat.dto;

import com.drilldex.drillbackend.chat.ChatMessage;

import java.time.Instant;

public class ChatMessageDto {
    private Long id;
    private Long senderId;
    private Long receiverId;
    private String content;
    private Instant timestamp;

    public ChatMessageDto(ChatMessage msg) {
        this.id = msg.getId();
        this.senderId = msg.getSenderId();
        this.receiverId = msg.getReceiverId();
        this.content = msg.getContent();
        this.timestamp = msg.getTimestamp();
    }

    // Getters
    public Long getId() { return id; }
    public Long getSenderId() { return senderId; }
    public Long getReceiverId() { return receiverId; }
    public String getContent() { return content; }
    public Instant getTimestamp() { return timestamp; }
}