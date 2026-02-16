package com.drilldex.drillbackend.chat;

import com.drilldex.drillbackend.chat.dto.ChatMessageDto;
import com.drilldex.drillbackend.notification.NotificationService;
import com.drilldex.drillbackend.user.User;
import com.drilldex.drillbackend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatStorageService chatStorageService;
    private final SseChatBroadcaster broadcaster;
    private final UserRepository userRepo;
    private final NotificationService notificationService;

    @GetMapping("/history")
    public List<ChatMessage> getChatHistory(
            @RequestParam Long user1,
            @RequestParam Long user2
    ) {
        return chatStorageService.getChatHistory(user1, user2);
    }

    @PostMapping("/send")
    public ChatMessage send(@RequestBody ChatMessage msg) {
        // Set timestamp and save
        msg.setTimestamp(Instant.now());
        ChatMessage saved = chatStorageService.saveMessage(msg);

        // Convert to DTO to prevent deep/nested serialization
        ChatMessageDto dto = new ChatMessageDto(saved);

        // --- Push to recipient via SSE ---
        broadcaster.sendTo(saved.getReceiverId(), dto);

        // Optional: also push to sender (so they see the canonical, server-timestamped message)
        broadcaster.sendTo(saved.getSenderId(), dto);

        // --- Create/aggregate notifications ---
        User recipient = userRepo.findById(saved.getReceiverId())
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found"));
        User sender = userRepo.findById(saved.getSenderId())
                .orElseThrow(() -> new IllegalArgumentException("Sender not found"));

        notificationService.createOrAggregateChat(
                recipient,
                sender,
                saved.getId(),
                "You have a new message"
        );

        return saved;
    }
}