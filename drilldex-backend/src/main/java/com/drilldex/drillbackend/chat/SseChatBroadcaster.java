package com.drilldex.drillbackend.chat;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SseChatBroadcaster {

    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    // userId -> list of emitters (support multiple tabs/devices)
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));

        // Optional initial event so client knows it's alive
        try {
            emitter.send(SseEmitter.event().name("init").data("{\"ok\":true}"));
        } catch (IOException ignored) {}

        return emitter;
    }

    public void sendTo(Long userId, Object payload) {
        List<SseEmitter> list = emitters.get(userId);
        if (list == null || list.isEmpty()) return;

        for (SseEmitter em : new CopyOnWriteArrayList<>(list)) {
            try {
                em.send(SseEmitter.event().name("message").data(payload));
            } catch (IOException e) {
                // failed: clean it up
                em.complete();
                remove(userId, em);
            }
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(userId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(userId);
            }
        }
    }
}