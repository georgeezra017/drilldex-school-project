package com.drilldex.drillbackend.chat;// src/main/java/com/drilldex/drillbackend/chat/ChatThreadController.java
import com.drilldex.drillbackend.auth.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatThreadController {

    private final ChatThreadService threads;

    @GetMapping("/threads")
    public List<ThreadSummary> myThreads(
            @RequestParam(required = false) Long userId,
            @AuthenticationPrincipal UserDetails principal
    ) {
        Long uid = userId;

        if (uid == null) {
            if (principal instanceof CustomUserDetails custom) {
                uid = custom.getUser().getId();
            } else {
                throw new IllegalArgumentException("userId must be provided or resolvable from auth");
            }
        }

        return threads.listThreads(uid);
    }
}