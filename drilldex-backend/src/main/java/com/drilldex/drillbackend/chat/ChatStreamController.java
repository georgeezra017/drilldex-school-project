package com.drilldex.drillbackend.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatStreamController {

    private final SseChatBroadcaster broadcaster;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam Long userId) {
        return broadcaster.subscribe(userId);
    }
}