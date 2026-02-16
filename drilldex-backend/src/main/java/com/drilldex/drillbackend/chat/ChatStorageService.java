// src/main/java/com/drilldex/drillbackend/chat/ChatStorageService.java
package com.drilldex.drillbackend.chat;

import com.drilldex.drillbackend.notification.NotificationService;
import com.drilldex.drillbackend.notification.NotificationType;
import com.drilldex.drillbackend.notification.RelatedType;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatStorageService {

    private final ChatMessageRepository chatMessageRepository;
    private final SseChatBroadcaster broadcaster;



    public ChatMessage saveMessage(ChatMessage message) {
        if (message.getTimestamp() == null) {
            message.setTimestamp(Instant.now());
        }
        return chatMessageRepository.save(message);
    }

    public List<ChatMessage> getChatHistory(Long user1, Long user2) {
        return chatMessageRepository
                .findBySenderIdAndReceiverIdOrReceiverIdAndSenderIdOrderByTimestampAsc(
                        user1, user2, user1, user2
                );
    }

    public void sendAdminMessageToUser(Long recipientId, String content) {
        Long adminId = 2L;

        ChatMessage message = new ChatMessage();
        message.setSenderId(adminId);
        message.setReceiverId(recipientId);
        message.setContent(content);
        message.setTimestamp(Instant.now());

        ChatMessage saved = chatMessageRepository.save(message);

        broadcaster.sendTo(recipientId, saved);
    }


    public Long getChatPartnerIdByMessageId(Long messageId, Long currentUserId) {
        return chatMessageRepository.findById(messageId)
                .map(msg -> {
                    if (msg.getSenderId().equals(currentUserId)) {
                        return msg.getReceiverId();
                    } else {
                        return msg.getSenderId();
                    }
                })
                .orElse(null);
    }
}