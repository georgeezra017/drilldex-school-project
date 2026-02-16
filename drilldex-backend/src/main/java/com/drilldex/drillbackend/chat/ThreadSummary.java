package com.drilldex.drillbackend.chat;

import java.time.Instant;
import lombok.*;

@Data @AllArgsConstructor @NoArgsConstructor @Builder
public class ThreadSummary {
    private Long partnerId;
    private String partnerName;      // optional; can be filled later on FE
    private String lastContent;
    private Instant lastTimestamp;
    private Long unreadCount;        // optional; 0 for now
}
