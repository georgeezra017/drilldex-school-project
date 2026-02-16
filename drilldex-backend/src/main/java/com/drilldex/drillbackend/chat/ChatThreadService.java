package com.drilldex.drillbackend.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatThreadService {

    private final ChatMessageRepository repo;

    public List<ThreadSummary> listThreads(Long userId) {
        List<ThreadRow> rows = repo.findThreads(userId);
        List<ThreadSummary> out = new ArrayList<>(rows.size());

        for (ThreadRow r : rows) {
            out.add(ThreadSummary.builder()
                    .partnerId(r.getPartnerId())
                    .partnerName(null)           // let FE resolve; or inject UserService to fill it here
                    .lastContent(r.getLastContent())
                    .lastTimestamp(r.getLastTime())
                    .unreadCount(0L)             // implement if you later track reads
                    .build());
        }
        return out;
    }


}